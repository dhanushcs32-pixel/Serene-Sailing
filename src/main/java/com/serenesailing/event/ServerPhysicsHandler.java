package com.serenesailing.event;

import com.serenesailing.SereneSailing;
import com.serenesailing.block.CapstanBlock;
import com.serenesailing.block.HelmBlock;
import com.serenesailing.block.RudderBlock;
import com.serenesailing.block.SailBlock;
import com.serenesailing.config.SailingConfig;
import com.serenesailing.entity.CapstanBlockEntity;
import com.serenesailing.entity.HelmBlockEntity;
import com.serenesailing.entity.RudderBlockEntity;
import com.serenesailing.entity.TaffrailLogBlockEntity;
import com.serenesailing.entity.WindVaneBlockEntity;
import com.serenesailing.physics.SailAerodynamics;
import com.serenesailing.compat.weather2.Weather2Compat;
import com.serenesailing.hold.HoldRegistry;
import com.serenesailing.hold.HoldScanner;
import com.serenesailing.physics.ShipHullAirPocketHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import com.serenesailing.physics.waves.GerstnerWaveEngine;
import com.serenesailing.registry.ModBlocks;
import java.util.Set;
import java.util.HashSet;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.serenesailing.compat.weather2.Weather2Wind;

import com.serenesailing.registry.ModSounds;
import net.minecraft.sounds.SoundSource;

import org.slf4j.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives vessels assembled through the Aeronautics Physics Assembler / Sable,
 * with every stability improvement retained: flotation gate, Weather2
 * apparent-wind thrust scaled by reefed sail area, dual linear+quadratic
 * hydrodynamic drag, pure local-frame yaw steering with roll/pitch
 * stabilization, and capstan anchor lock.
 *
 * <p>This is the <b>only</b> ship physics that runs in-game. The separate engine in
 * {@code com.serenesailing.physics} ({@code SailingPhysicsEngine}, {@code ShipPhysicsEngine},
 * {@code WaterlineProvider}, {@code SailingPhysicsConfig}, {@code BlockMassTable}) is not
 * called from here and is kept for reference only.
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID)
public class ServerPhysicsHandler {

    private static final Logger LOGGER = SereneSailing.LOGGER;
    private static final Map<UUID, Long> PHYS_DEBUG_TICK = new ConcurrentHashMap<>();
    private static final AtomicLong stepCounter = new AtomicLong(0L);

    // --- Runaway guardrails & Fossen / MMG Hydrodynamics ---
    private static final double FORCE_PER_FULL_SAIL_N = 750.0;
    /** Yaw impulse guard - sized so the steering torque stays proportional to
     *  v_fwd across all playable speeds (800 * v * sin(30 deg) * dt stays
     *  below this up to ~9 m/s), keeping the turn rate continuous rather than
     *  saturating into a fixed-radius circle. */
    private static final double MAX_YAW_IMPULSE_PER_SUBSTEP = 60.0;
    private static final double K_ROLL_SPRING = 85.0;   // Restoring stiffness against heel
    private static final double C_ROLL_DAMP   = 14.0;   // Damping against roll oscillation
    private static final double K_PITCH_SPRING = 240.0; // Pitch is stiffer due to long hull (doubled to resist bow-dive)
    private static final double C_PITCH_DAMP   = 28.0;  // Increased to damp pitchpole oscillation
    /** Anchor lock exponential rate (keep = e^(-rate*dt)). */
    private static final double ANCHOR_DAMPING_RATE = 20.0;
    /** Target hull draft depth [blocks]: equilibrium sits the hull bottom
     *  this far below the water surface so planks are naturally submerged. */
    private static final float TARGET_HULL_DRAFT = 0.40f;
    /** Ride-height spring response [1/s^2 per block of submerged depth]. */
    private static final double RIDE_SPRING = 10.0;

    /** Lateral keel resistance [N*s/m]: heavy damping of the hull's local X
     *  (sideways) velocity so the vessel cuts forward along local -Z instead
     *  of crabbing sideways and stalling the rudder. */
    private static final double KEEL_LATERAL_DAMPING = 400.0;

    /** Cached rudder: live block entity, application point, and facing. */
    private record RudderInfo(RudderBlockEntity blockEntity, Vector3d localPoint, Direction facing) {}

    /** Per-sub-level steering scan: rudders, helm stations, vanes, capstans, taffrails, sails, blockCount, sailFacing, sailCount,
     *  keelLocalPoint, minBlockY, maxBlockY, targetDraft, interiorAirVolume, hullHeight, hullLength, hullBeam. */
    private record SteeringCache(List<RudderInfo> rudders, List<HelmBlockEntity> helms,
                                 List<WindVaneBlockEntity> vanes,
                                 List<CapstanBlockEntity> capstans,
                                 List<TaffrailLogBlockEntity> taffrails,
                                 boolean anchorState,
                                 double sailAreaEfficiency,
                                 int blockCount,
                                 Direction sailFacing,
                                 int sailCount,
                                 Vector3d keelLocalPoint,
                                 int minBlockY,
                                 int maxBlockY,
                                 float targetDraft,
                                 int interiorAirVolume,
                                 double hullHeight,
                                 double hullLength,
                                 double hullBeam) {}

    private static final Map<UUID, SteeringCache> STEERING_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STEERING_CACHE_STEP = new ConcurrentHashMap<>();
    /** Per-sublevel cooldown stamp for hull creak sounds. */
    private static final Map<UUID, Long> CREAK_COOLDOWN = new ConcurrentHashMap<>();
    private static final long STEERING_RESCAN_STEPS = 100L;

    /** Authoritative helm angle cached per sub-level UUID to bridge sleep/virtualized block entities. */
    private static final Map<UUID, Float> HELM_ANGLE_CACHE = new ConcurrentHashMap<>();
    /** Real-time world linear velocity vector cached per sub-level UUID for telemetry sensors. */
    private static final Map<UUID, Vector3d> VESSEL_VELOCITY_CACHE = new ConcurrentHashMap<>();
    /** Forward speed [m/s] along vessel heading cached per sub-level UUID. */
    private static final Map<UUID, Double> FORWARD_SPEED_CACHE = new ConcurrentHashMap<>();
    /** Previous tick forward heading cached per sub-level UUID for momentum turning redirection. */
    private static final Map<UUID, Vector3d> PREV_HEADING_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_BILGE_PURGE_TICK = new ConcurrentHashMap<>();

    /** Cached wind speed and angle sampled on the main server thread. */
    public record CachedWind(float angle, float speed) {
        public static final CachedWind ZERO = new CachedWind(0.0f, 0.0f);
    }

    /** Dimension-level wind cache keyed by Dimension ResourceKey sampled on the main server thread. */
    private static final Map<ResourceKey<Level>, CachedWind> DIMENSION_WIND_CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, double[]> DIMENSION_WIND_VEC_CACHE = new ConcurrentHashMap<>();

    public static CachedWind getDimensionWind(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return CachedWind.ZERO;
        }
        return DIMENSION_WIND_CACHE.getOrDefault(dimension, CachedWind.ZERO);
    }

    public static void setHelmAngle(UUID subLevelId, float angle) {
        if (subLevelId == null) {
            return;
        }
        if (Float.isNaN(angle) || Float.isInfinite(angle)) {
            angle = 0.0f;
        }
        HELM_ANGLE_CACHE.put(subLevelId, angle);
    }

    public static float getHelmAngle(UUID subLevelId) {
        if (subLevelId == null) {
            return 0.0f;
        }
        return HELM_ANGLE_CACHE.getOrDefault(subLevelId, 0.0f);
    }

    public static Vector3d getVesselVelocity(UUID subLevelId) {
        if (subLevelId == null) {
            return new Vector3d(0.0, 0.0, 0.0);
        }
        return VESSEL_VELOCITY_CACHE.getOrDefault(subLevelId, new Vector3d(0.0, 0.0, 0.0));
    }

    public static double getForwardSpeed(UUID subLevelId) {
        if (subLevelId == null) {
            return 0.0;
        }
        return FORWARD_SPEED_CACHE.getOrDefault(subLevelId, 0.0);
    }

    /**
     * Resolves the {@link ServerLevel} that actually holds Weather2 weather data
     * (falls back to the Overworld manager for unregistered dimensions).
     */
    public static ServerLevel resolveWeatherQueryLevel(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return level.getServer() != null
                    ? level.getServer().getLevel(Level.OVERWORLD)
                    : null;
        }
        if (!Weather2Wind.isLoaded()) {
            return serverLevel; // optional dependency absent
        }
        if (!Weather2Wind.hasManager(serverLevel)) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null && Weather2Wind.hasManager(overworld)) {
                return overworld;
            }
        }
        return serverLevel;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }

        // Low-pass filter wind vector: 0.985 * current + 0.015 * target in Cartesian space
        // Prevents angle jumping, sudden 180 flips, and ensures natural gradual drift over minutes
        for (ServerLevel serverLevel : server.getAllLevels()) {
            ServerLevel weatherLevel = resolveWeatherQueryLevel(serverLevel);
            final boolean hasWeather2 = weatherLevel != null && Weather2Wind.isLoaded();
            float targetAngle = hasWeather2 ? Weather2Wind.windAngle(weatherLevel) : 0.0f;
            float targetSpeed = hasWeather2 ? Weather2Wind.windSpeed(weatherLevel) : 0.0f;

            double targetRad = Math.toRadians(targetAngle);
            double targetX = -Math.sin(targetRad) * targetSpeed;
            double targetZ = Math.cos(targetRad) * targetSpeed;

            double[] currentVec = DIMENSION_WIND_VEC_CACHE.computeIfAbsent(serverLevel.dimension(),
                    k -> new double[] { targetX, targetZ });
            currentVec[0] = 0.985 * currentVec[0] + 0.015 * targetX;
            currentVec[1] = 0.985 * currentVec[1] + 0.015 * targetZ;

            double smoothedSpeed = Math.sqrt(currentVec[0] * currentVec[0] + currentVec[1] * currentVec[1]);
            float smoothedAngle = (float) Math.toDegrees(Math.atan2(-currentVec[0], currentVec[1]));
            DIMENSION_WIND_CACHE.put(serverLevel.dimension(), new CachedWind(smoothedAngle, (float) smoothedSpeed));
        }
    }

    @SubscribeEvent
    public static void onPrePhysicsTick(ForgeSablePrePhysicsTickEvent event) {
        stepCounter.incrementAndGet();
        SubLevelPhysicsSystem system = event.getPhysicsSystem();
        ServerLevel level = system.getLevel();
        double dt = event.getTimeStep();
        if (dt <= 0.0) {
            return;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container instanceof ServerSubLevelContainer serverContainer)) {
            return;
        }

        Set<UUID> activeSubLevelIds = new HashSet<>();
        for (ServerSubLevel subLevel : serverContainer.getAllSubLevels()) {
            UUID id = subLevel.getUniqueId();
            if (subLevel.isRemoved()) {
                STEERING_CACHE.remove(id);
                STEERING_CACHE_STEP.remove(id);
                CREAK_COOLDOWN.remove(id);
                HELM_ANGLE_CACHE.remove(id);
                VESSEL_VELOCITY_CACHE.remove(id);
                FORWARD_SPEED_CACHE.remove(id);
                PREV_HEADING_CACHE.remove(id);
                PHYS_DEBUG_TICK.remove(id);
                LAST_BILGE_PURGE_TICK.remove(id);
                continue;
            }
            activeSubLevelIds.add(id);
            try {
                applyShipPhysics(subLevel, level, dt);
            } catch (Exception ex) {
                SereneSailing.LOGGER.error("[SereneSailing] sailing physics failed for sub-level {}", id, ex);
            }
        }

        // Periodic orphan cache eviction (every 100 ticks): clean up disassembled or unloaded contraptions
        if (stepCounter.get() % 100 == 0) {
            // This event fires once per physics substep for EVERY dimension's physics system (also ones with no ships),
            // and the caches are shared by all dimensions. Retain the ships of every level, not just this one, or a
            // ship-less dimension wipes the helm angle / velocity / heading of ships sailing elsewhere.
            final Set<UUID> allActive = new HashSet<>(activeSubLevelIds);
            final MinecraftServer server = level.getServer();
            if (server != null) {
                for (ServerLevel other : server.getAllLevels()) {
                    if (other == level) {
                        continue;
                    }
                    if (SubLevelContainer.getContainer(other) instanceof ServerSubLevelContainer otherContainer) {
                        for (ServerSubLevel otherSub : otherContainer.getAllSubLevels()) {
                            if (!otherSub.isRemoved()) {
                                allActive.add(otherSub.getUniqueId());
                            }
                        }
                    }
                }
            }
            STEERING_CACHE.keySet().retainAll(allActive);
            STEERING_CACHE_STEP.keySet().retainAll(allActive);
            CREAK_COOLDOWN.keySet().retainAll(allActive);
            HELM_ANGLE_CACHE.keySet().retainAll(allActive);
            VESSEL_VELOCITY_CACHE.keySet().retainAll(allActive);
            FORWARD_SPEED_CACHE.keySet().retainAll(allActive);
            PREV_HEADING_CACHE.keySet().retainAll(allActive);
            PHYS_DEBUG_TICK.keySet().retainAll(allActive);
            LAST_BILGE_PURGE_TICK.keySet().retainAll(allActive);
        }
    }

    private static void applyShipPhysics(ServerSubLevel subLevel, ServerLevel level, double dt) {
        RigidBodyHandle body = RigidBodyHandle.of(subLevel);
        if (!body.isValid()) {
            return;
        }

        Pose3dc pose = subLevel.logicalPose();
        Vector3dc worldPos = pose.position();

        // ---- Read strictly from thread-safe dimension wind cache (Decoupled from Weather2 Netty thread) ----
        CachedWind cachedWind = getDimensionWind(level.dimension());
        float rawWindAngle = cachedWind.angle();
        float rawWindSpeed = cachedWind.speed();
        double effectiveWindSpeed = Math.max(SailingConfig.MIN_WIND_SPEED.get(), rawWindSpeed * 20.0);
        double windRad = Math.toRadians(rawWindAngle);
        Vector3d trueWindWorld = new Vector3d(-Math.sin(windRad), 0.0, Math.cos(windRad))
                .mul(effectiveWindSpeed);

        // ---- Ship state & heading fallback ---------------------------------
        Vector3d shipVelWorld = new Vector3d(body.getLinearVelocity());
        Quaterniondc orientation = pose.orientation();

        SteeringCache steering = getSteeringCache(subLevel);

        // Task 3.2: Heading Fallback: If no HelmBlock is present on the contraption,
        // resolve craft's forward vector from SailBlock facing direction (sailFacing.getOpposite()),
        // falling back to local (0, 0, -1).
        Vector3d localForward;
        if (!steering.helms().isEmpty()) {
            Direction helmFacing = steering.helms().get(0).getBlockState().hasProperty(HelmBlock.FACING)
                    ? steering.helms().get(0).getBlockState().getValue(HelmBlock.FACING) : Direction.NORTH;
            localForward = new Vector3d(helmFacing.getStepX(), helmFacing.getStepY(), helmFacing.getStepZ());
        } else if (steering.sailFacing() != null) {
            Direction bowDir = steering.sailFacing().getOpposite();
            localForward = new Vector3d(bowDir.getStepX(), bowDir.getStepY(), bowDir.getStepZ());
        } else {
            localForward = new Vector3d(0.0, 0.0, -1.0);
        }
        if (localForward.lengthSquared() < 1.0e-4) {
            localForward = new Vector3d(0.0, 0.0, -1.0);
        } else {
            localForward.normalize();
        }

        Vector3d localUp = new Vector3d(0.0, 1.0, 0.0);
        Vector3d localRight = new Vector3d(localForward).cross(localUp);
        if (localRight.lengthSquared() < 1.0e-4) {
            localRight.set(1.0, 0.0, 0.0);
        } else {
            localRight.normalize();
        }

        Vector3d forwardWorld = new Vector3d(localForward).rotate(orientation);
        forwardWorld.y = 0.0;
        if (forwardWorld.lengthSquared() < 1.0e-4) {
            return;
        }
        forwardWorld.normalize();
        Vector3d starboardWorld = new Vector3d(forwardWorld).cross(0.0, 1.0, 0.0).normalize();

        // ---- Rigid body mass & inertia guardrails (Fossen / MMG model) -----
        double calculatedMass = 0.0;
        var massTracker = subLevel.getMassTracker();
        if (massTracker != null && !massTracker.isInvalid()) {
            calculatedMass = massTracker.getMass();
        }
        int blockCount = steering.blockCount();
        // If rigid body mass is uninitialized or defaulting below 800 kg for an assembled
        // multi-block vessel, ensure a realistic minimum mass based on total block count:
        double minMass = blockCount > 0 ? Math.max(800.0, blockCount * 80.0) : 800.0;
        double totalMass = Math.max(calculatedMass, minMass);
        double massScale = (calculatedMass > 0.0 && calculatedMass < totalMass)
                ? (calculatedMass / totalMass)
                : 1.0;

        UUID subLevelId = subLevel.getUniqueId();
        VESSEL_VELOCITY_CACHE.put(subLevelId, new Vector3d(shipVelWorld));
        // FIX (Bug 7): Allow negative speed (sternway) so taffrail and
        // telemetry accurately show backing. Only the absolute value clamp
        // was preventing sternway display.
        double fwdSpeed = shipVelWorld.dot(forwardWorld);
        FORWARD_SPEED_CACHE.put(subLevelId, fwdSpeed);
        for (TaffrailLogBlockEntity taffrail : steering.taffrails()) {
            taffrail.setSpeedTelemetry((float) fwdSpeed);
        }

        // Record initial forward velocity for momentum inertia guard
        double initialFwdSpeed = fwdSpeed;

        // Redirect Forward Momentum When Turning (Keel Hydrodynamic Lift):
        // When the ship's heading rotates by dYaw while underway, rotate the horizontal
        // velocity vector toward the new forwardHeading by 80% (75%-85%) of dYaw.
        // This prevents artificial sway drag from destroying forward momentum on direction change!
        boolean anchored = isAnchored(steering);
        Vector3d prevHeading = PREV_HEADING_CACHE.get(subLevelId);
        if (!anchored && prevHeading != null && Math.abs(initialFwdSpeed) > 0.05) {
            Vector3d cross = new Vector3d(prevHeading).cross(forwardWorld);
            double dYaw = Math.atan2(cross.y, prevHeading.dot(forwardWorld));
            if (Math.abs(dYaw) > 1.0e-5) {
                Vector3d horizontalVel = new Vector3d(shipVelWorld.x, 0.0, shipVelWorld.z);
                horizontalVel.rotateY(dYaw * 0.80);
                Vector3d redirectDelta = new Vector3d(horizontalVel.x - shipVelWorld.x, 0.0, horizontalVel.z - shipVelWorld.z);
                body.addLinearAndAngularVelocity(redirectDelta, new Vector3d());
                shipVelWorld.x = horizontalVel.x;
                shipVelWorld.z = horizontalVel.z;
                fwdSpeed = shipVelWorld.dot(forwardWorld);
            }
        }
        PREV_HEADING_CACHE.put(subLevelId, new Vector3d(forwardWorld));

        // ---- Buoyancy / ride height (Keel Tracking & Hollow Hull Draft) ---
        // Upright vertical offset from SubLevel's local rotation origin (pose.rotationPoint()) to keel bottom:
        // Must be invariant to roll/pitch angle to prevent a positive feedback loop
        // where rolling the ship raises the transformed keel position and tricks
        // the ride height spring into dragging the entire vessel underwater!
        double keelOffsetFromOriginY = steering.keelLocalPoint().y() - pose.rotationPoint().y();
        double keelWorldY = worldPos.y() + keelOffsetFromOriginY;
        double hullHeight = steering.hullHeight();
        double hullLength = steering.hullLength();
        double hullBeam = steering.hullBeam();
        boolean isShallowDraft = hullHeight <= 1.5;
        float targetDraft = steering.targetDraft();

        double baseWaterSurfaceY = waterSurfaceY(level, subLevel, keelWorldY, hullHeight);

        // 4-point wave surface sampling around hull extremes (bow, stern, port, starboard)
        double halfLength = hullLength * 0.5;
        double halfBeam = hullBeam * 0.5;

        Vector3d hullCenterWorld = new Vector3d(worldPos.x(), keelWorldY, worldPos.z());
        Vector3d bowPos = new Vector3d(hullCenterWorld).add(new Vector3d(forwardWorld).mul(halfLength));
        Vector3d sternPos = new Vector3d(hullCenterWorld).sub(new Vector3d(forwardWorld).mul(halfLength));
        Vector3d portPos = new Vector3d(hullCenterWorld).sub(new Vector3d(starboardWorld).mul(halfBeam));
        Vector3d starPos = new Vector3d(hullCenterWorld).add(new Vector3d(starboardWorld).mul(halfBeam));

        double time = level != null ? (double) level.getGameTime() : 0.0;
        double offsetBow = WAVE_HEIGHT_HOOK.getWaveHeight(level, bowPos.x, bowPos.z, time);
        double offsetStern = WAVE_HEIGHT_HOOK.getWaveHeight(level, sternPos.x, sternPos.z, time);
        double offsetPort = WAVE_HEIGHT_HOOK.getWaveHeight(level, portPos.x, portPos.z, time);
        double offsetStarboard = WAVE_HEIGHT_HOOK.getWaveHeight(level, starPos.x, starPos.z, time);

        double stormIntensity = GerstnerWaveEngine.getStormIntensity(level);
        boolean isCalmWeather = (level != null && !level.isRaining() && !level.isThundering()) && (stormIntensity <= 0.18);

        boolean hasActiveWaves = !isCalmWeather && (Math.abs(offsetBow) > 1.0e-4 || Math.abs(offsetStern) > 1.0e-4
                || Math.abs(offsetPort) > 1.0e-4 || Math.abs(offsetStarboard) > 1.0e-4);

        // Heavy hull inertia filtering: large multi-deck ships span wave crests and cut through swells
        double heaveAttenuation = 1.0 / (1.0 + (steering.blockCount() / 400.0));

        double wavePitchTarget = 0.0;
        double waveRollTarget = 0.0;
        if (hasActiveWaves) {
            double effectiveLength = Math.max(6.0, hullLength * 0.7);
            double effectiveBeam = Math.max(4.0, hullBeam * 0.7);
            double rotAtten = Math.max(0.35, heaveAttenuation);
            wavePitchTarget = Mth.clamp(Math.atan2(offsetBow - offsetStern, effectiveLength) * rotAtten, -0.35, 0.35);
            waveRollTarget = Mth.clamp(Math.atan2(offsetPort - offsetStarboard, effectiveBeam) * rotAtten, -0.40, 0.40);
        }

        double rawMeanWaveOffset = (offsetBow + offsetStern + offsetPort + offsetStarboard) * 0.25;
        double attenuatedWaveOffset = isCalmWeather ? 0.0 : (rawMeanWaveOffset * heaveAttenuation);

        // 3-point longitudinal keel slope sampling (Bow, Midships, Stern) for surge/sway wave slope dynamics
        var waveState = GerstnerWaveEngine.getDimensionState(level);
        Vector3d slopeBow = hasActiveWaves ? GerstnerWaveEngine.evaluateWaveSlopeAndDrift(
                bowPos.x, bowPos.z, time * 0.05, stormIntensity,
                waveState.smoothedWindDirX, waveState.smoothedWindDirZ) : new Vector3d();
        Vector3d slopeMid = hasActiveWaves ? GerstnerWaveEngine.evaluateWaveSlopeAndDrift(
                hullCenterWorld.x, hullCenterWorld.z, time * 0.05, stormIntensity,
                waveState.smoothedWindDirX, waveState.smoothedWindDirZ) : new Vector3d();
        Vector3d slopeStern = hasActiveWaves ? GerstnerWaveEngine.evaluateWaveSlopeAndDrift(
                sternPos.x, sternPos.z, time * 0.05, stormIntensity,
                waveState.smoothedWindDirX, waveState.smoothedWindDirZ) : new Vector3d();

        double avgSlopeX = (slopeBow.x + slopeMid.x + slopeStern.x) / 3.0;
        double avgSlopeZ = (slopeBow.z + slopeMid.z + slopeStern.z) / 3.0;

        double meanWaterSurfaceY = baseWaterSurfaceY;
        if (isCalmWeather) {
            // Calm-Weather Deadzone: static integer water surface (63.0) when afloat
            if (baseWaterSurfaceY > Double.NEGATIVE_INFINITY) {
                meanWaterSurfaceY = 63.0;
            }
        } else if (hasActiveWaves && baseWaterSurfaceY > Double.NEGATIVE_INFINITY) {
            // Anti-flooding floor: clamp negative wave trough offset on meanWaterSurfaceY to >= baseWaterSurfaceY - 0.35,
            // while allowing full positive heave lift (+0.0 to +2.5m) on wave crests!
            double clampedWaveOffset = Math.max(-0.35, attenuatedWaveOffset);
            meanWaterSurfaceY = baseWaterSurfaceY + clampedWaveOffset;
        }

        // Automatic Sub-Level Interior Bilge Purge on Assembly & Periodically every 40 ticks
        Long lastPurge = LAST_BILGE_PURGE_TICK.get(subLevelId);
        long currentStep = stepCounter.get();
        if (lastPurge == null || (currentStep - lastPurge) >= 40) {
            purgeSubLevelHoldWater(subLevel);
            LAST_BILGE_PURGE_TICK.put(subLevelId, currentStep);
        }

        boolean hasEnclosedHold = steering.interiorAirVolume() > 50 || steering.hullHeight() >= 3.0;
        // anchored is already evaluated above for momentum redirection

        // Weather2 Storm & Tornado Physics Interaction
        Weather2Compat.StormData nearestStorm = Weather2Compat.getNearestStorm(level, worldPos.x(), worldPos.z(), 256.0);
        StormPhysicsResult stormResult = applyStormAndTornadoForces(
                body, nearestStorm, worldPos, shipVelWorld,
                steering.blockCount(), hasEnclosedHold, anchored,
                steering.sailAreaEfficiency(), steering.sailCount(),
                time, dt
        );

        // Task 3.1: Raft Flotation Check: Flat vessels with minimal draft (T < 0.5m)
        // are recognized as floating whenever any hull block intersects water.
        int submergedSamples = countSubmergedHullSamples(level, subLevel, keelWorldY, targetDraft, isShallowDraft);
        boolean waterContact = meanWaterSurfaceY > Double.NEGATIVE_INFINITY
                && (keelWorldY <= meanWaterSurfaceY + 0.5 || (isShallowDraft && keelWorldY <= meanWaterSurfaceY + 0.3));
        boolean afloat = waterContact || (submergedSamples > 0);
        if (afloat && meanWaterSurfaceY > Double.NEGATIVE_INFINITY && !stormResult.isLiftedByTornado()) {
            applyRideHeightSpring(body, meanWaterSurfaceY, keelWorldY, targetDraft, dt, isCalmWeather);
        }

        // ---- Flotation gate: beached ships get ground friction only (unless airborne in tornado) --------
        if (!afloat && submergedSamples == 0 && !stormResult.isLiftedByTornado()) {
            applyGroundFriction(body, dt);
            updateWindVanes(steering.vanes(), forwardWorld, trueWindWorld, shipVelWorld);
            return;
        }

        // ---- Strict zero-wind / zero-sail thrust guard -----------------------
        // Below the calm threshold there is no aerodynamic force at all, and
        // with every sail furled the effective area is zero. There is NO
        // synthetic baseline forward drift anywhere in this handler: the only
        // forward force is the apparent-wind sail force, which is exactly
        // zero in both cases.
        boolean calmWind = effectiveWindSpeed < 0.05;
        boolean noSailArea = steering.sailAreaEfficiency() <= 0.001;

        // ---- Sail thrust scaled by reefed area ------------------------------
        // An anchored (moored) vessel generates zero thrust.
        Vector3d thrustWorld = new Vector3d();
        double thrustMag = 0.0;
        double sailAreaEfficiency = steering.sailAreaEfficiency();
        // Scale purely with active sail area (e.g., ~450 N max thrust per fully deployed sail block)
        double maxAeroForce = sailAreaEfficiency * FORCE_PER_FULL_SAIL_N;
        if (!anchored && !calmWind && !noSailArea) {
            thrustWorld.set(SailAerodynamics.calculateSailForce(
                    trueWindWorld, shipVelWorld, forwardWorld, starboardWorld, 1.0));
            thrustWorld.mul(SailingConfig.SAIL_THRUST_MULTIPLIER.get()
                    * sailAreaEfficiency);
            thrustMag = thrustWorld.length();

            // Strict linear scaling from zero based on active sail area
            if (thrustMag > maxAeroForce && thrustMag > 1.0e-6) {
                thrustWorld.mul(maxAeroForce / thrustMag);
                thrustMag = maxAeroForce;
            }
        }
        boolean hasActiveThrust = thrustMag > 1.0 || (steering.sailAreaEfficiency() > 0.0 && !calmWind && !anchored);
        double thrustForceN = 0.0;
        Vector3d localLinearImpulse = new Vector3d(0.0, 0.0, 0.0);
        if (thrustMag > 1.0e-9) {
            thrustForceN = thrustMag;

            // Task 1: Strict Body-Fixed Propulsion Vector (Fossen model)
            // Forward thrust vector strictly in local body coordinates along the craft's surge axis.
            // Eliminates cross-coupled frame leakage into sway (Fx) and roll (K).
            localLinearImpulse.set(
                    localForward.x * thrustForceN * dt,
                    0.0,
                    localForward.z * thrustForceN * dt
            );

            double maxImpulse = maxAeroForce * dt;
            double impulseLen = localLinearImpulse.length();
            if (impulseLen > maxImpulse && impulseLen > 1.0e-6) {
                localLinearImpulse.mul(maxImpulse / impulseLen);
            }

            // Ensure aerodynamic linear acceleration satisfies a = F_thrust / M_total,
            // giving smooth, gradual acceleration rather than instant velocity spikes.
            // When Sable divides impulse by its internal rigid body mass (calculatedMass),
            // scale impulse by (calculatedMass / totalMass) so effective a = F / M_total.
            if (calculatedMass > 0.0) {
                localLinearImpulse.mul(massScale);
                // Decouple aerodynamic thrust from yaw torque: sail yaw torque is explicitly 0.0
                double sailYawTorque = 0.0;
                Vector3d localAngularImpulse = new Vector3d(0.0, sailYawTorque * dt, 0.0);
                body.applyLinearAndAngularImpulse(localLinearImpulse, localAngularImpulse, true);
            } else {
                Vector3d deltaVLocal = new Vector3d(localLinearImpulse).div(totalMass);
                Vector3d deltaVWorld = pose.transformNormal(deltaVLocal, new Vector3d());
                body.addLinearAndAngularVelocity(deltaVWorld, new Vector3d());
            }
        }

        // ---- Drag / anchor lock ---------------------------------------------
        Vector3d omegaWorld = new Vector3d(body.getAngularVelocity());
        double planarSpeedSq = shipVelWorld.x * shipVelWorld.x + shipVelWorld.z * shipVelWorld.z;
        double planarSpeed = Math.sqrt(planarSpeedSq);
        boolean anchorDropping = isAnchorDropping(steering);

        // Fossen Wetted Surface Approximation: Hull drag scales modestly with vessel displacement (volume^(2/3) or mass^(1/3))
        double hullDragScale = Math.pow(totalMass / 10000.0, 0.33);
        double dragForceMag = (SailingConfig.LINEAR_DRAG_MU.get() * planarSpeed
                + SailingConfig.QUADRATIC_DRAG_MU.get() * planarSpeed * planarSpeed) * hullDragScale;

        if (anchored) {
            // Capstan anchor lock: exponential planar freeze + full yaw/roll/pitch stabilization + downward hold.
            // 1. Planar freeze
            double keep = Math.exp(-ANCHOR_DAMPING_RATE * dt);
            Vector3d planarDelta = new Vector3d(
                    shipVelWorld.x * (keep - 1.0), 0.0, shipVelWorld.z * (keep - 1.0));
            if (planarSpeed < 0.02) {
                // Nearly stopped - snap completely to rest.
                planarDelta.set(-shipVelWorld.x, 0.0, -shipVelWorld.z);
            }

            // 2. Downward hold: tethers the hull in water, damping vertical heave and bobbing
            double keepY = Math.exp(-ANCHOR_DAMPING_RATE * 0.5 * dt);
            double deltaY = shipVelWorld.y * (keepY - 1.0);

            // 3. Full angular damping (yaw, roll, pitch) to hold the vessel stable in water
            Vector3d omegaDelta = new Vector3d(
                    -omegaWorld.x * 0.8,
                    -omegaWorld.y,
                    -omegaWorld.z * 0.8);

            body.addLinearAndAngularVelocity(
                    new Vector3d(planarDelta.x, deltaY, planarDelta.z),
                    omegaDelta);
        } else if (anchorDropping && planarSpeed > 1.0e-4) {
            // Anchor is falling through the water column: progressive drag slows ship
            double dropDrag = 120.0;
            for (CapstanBlockEntity capstan : steering.capstans()) {
                if (capstan.isAnchored() && !capstan.isHolding()) {
                    dropDrag += 80.0 * (capstan.getCurrentChainLength() + 1.0);
                }
            }
            Vector3d dragForce = new Vector3d(
                    -shipVelWorld.x / planarSpeed, 0.0, -shipVelWorld.z / planarSpeed)
                    .mul(dropDrag * planarSpeed);
            Vector3d dragImpulse = pose.transformNormalInverse(dragForce, new Vector3d()).mul(dt * massScale);
            body.applyLinearAndAngularImpulse(dragImpulse, new Vector3d(), true);

            float activeHelmAngle = getUnifiedSteeringAngle(subLevel, getRudders(subLevel));
            applyHydrodynamicForces(body, pose, shipVelWorld, omegaWorld, planarSpeed,
                    !hasActiveThrust, activeHelmAngle, dt, massScale, dragForceMag, localRight);
        } else if (planarSpeed > 1.0e-9 || omegaWorld.lengthSquared() > 1.0e-12) {
            // FIX: Pass the unified helm angle so zero-thrust yaw damping is
            // suppressed when the rudder is deflected (otherwise it wipes out
            // the steering torque applied immediately afterward).
            float activeHelmAngle = !anchored
                    ? getUnifiedSteeringAngle(subLevel, getRudders(subLevel)) : 0.0f;
            applyHydrodynamicForces(body, pose, shipVelWorld, omegaWorld, planarSpeed,
                    !hasActiveThrust, activeHelmAngle, dt, massScale, dragForceMag, localRight);
        }

        // ---- Wave-Slope Surge/Sway Decomposition & Anti-Stall Decoupling ----
        if (!anchored && afloat && hasActiveWaves) {
            Vector3d waveSlopeForceWorld = new Vector3d(
                    -totalMass * 9.81 * avgSlopeX * heaveAttenuation,
                    0.0,
                    -totalMass * 9.81 * avgSlopeZ * heaveAttenuation
            );
            Vector3d waveSlopeForceLocal = pose.transformNormalInverse(waveSlopeForceWorld, new Vector3d());
            double surgeForce = waveSlopeForceLocal.dot(localForward); // positive = forward push, negative = opposing
            double swayForce = waveSlopeForceLocal.dot(localRight);

            // Never allow opposing wave slopes to act as a brick wall against forward motion
            if (surgeForce < 0.0) {
                if ((hasActiveThrust && thrustMag > 1.0) || fwdSpeed > 0.15) {
                    // Attenuate opposing longitudinal wave-slope force by 88%
                    surgeForce *= 0.12;
                    // Hard-clamp maximum opposing wave-slope retardation to at most 15% of active forward sail thrust
                    double maxRetardation = Math.max(0.0, 0.15 * thrustMag);
                    if (-surgeForce > maxRetardation) {
                        surgeForce = -maxRetardation;
                    }
                }
            }
            // Downhill / surfing assistance (surgeForce >= 0) is fully preserved

            Vector3d filteredSlopeForceLocal = new Vector3d(localForward).mul(surgeForce)
                    .add(new Vector3d(localRight).mul(swayForce));
            Vector3d slopeImpulseLocal = filteredSlopeForceLocal.mul(dt * massScale);
            body.applyLinearAndAngularImpulse(slopeImpulseLocal, new Vector3d(), true);
        }

        // ---- Storm Wave Orbital Push (Stokes Drift) -------------------------
        // During storms (S > 0.4), apply a horizontal Stokes wave drift force along wave propagation direction
        if (!anchored && stormIntensity > 0.4 && afloat) {
            double stokesForceMag = (stormIntensity - 0.4) * 350.0 * Math.sqrt(totalMass / 1000.0);
            Vector3d stokesForceWorld = new Vector3d(
                    waveState.smoothedWindDirX * stokesForceMag,
                    0.0,
                    waveState.smoothedWindDirZ * stokesForceMag
            );
            Vector3d stokesLocal = pose.transformNormalInverse(stokesForceWorld, new Vector3d());
            double stokesSurge = stokesLocal.dot(localForward);
            double stokesSway = stokesLocal.dot(localRight);

            // Attenuate opposing Stokes drift when the vessel is actively under sail (> 1.0 N thrust)
            if (hasActiveThrust && thrustMag > 1.0) {
                if (stokesSurge < 0.0) {
                    stokesSurge *= 0.12; // 88% attenuation of opposing drift
                    double maxStokesBrake = 0.10 * thrustMag;
                    if (-stokesSurge > maxStokesBrake) {
                        stokesSurge = -maxStokesBrake;
                    }
                }
            }

            Vector3d filteredStokesLocal = new Vector3d(localForward).mul(stokesSurge)
                    .add(new Vector3d(localRight).mul(stokesSway));
            Vector3d stokesImpulseLocal = filteredStokesLocal.mul(dt * massScale);
            body.applyLinearAndAngularImpulse(stokesImpulseLocal, new Vector3d(), true);
        }

        // ---- Stabilized pure-yaw steering & righting torque -----------------
        boolean sailsCatchingWind = thrustMag > 1.0e-9;
        applySteeringAndStabilization(subLevel, body, pose, level, forwardWorld, shipVelWorld, omegaWorld, planarSpeed, sailsCatchingWind, anchored, dt,
                steering, thrustForceN, dragForceMag, totalMass, massScale, localForward, localRight, localLinearImpulse, hasActiveThrust,
                wavePitchTarget, waveRollTarget);

        // ---- Nautical ambience: hull creaks under heavy stress ---------------
        // Fires when wave roll or yaw rate exceeds a threshold, with a
        // per-sublevel cooldown so the creak reads as occasional groaning
        // rather than a constant loop.
        if (!anchored && (Math.abs(omegaWorld.x) > 0.05 || Math.abs(omegaWorld.y) > 0.05 || Math.abs(omegaWorld.z) > 0.05)) {
            Long lastCreak = CREAK_COOLDOWN.get(subLevel.getUniqueId());
            if (lastCreak == null || stepCounter.get() - lastCreak >= 60L) {
                CREAK_COOLDOWN.put(subLevel.getUniqueId(), stepCounter.get());
                level.playSound(null,
                        BlockPos.containing(worldPos.x(), worldPos.y(), worldPos.z()),
                        ModSounds.SHIP_CREAK.get(), SoundSource.AMBIENT,
                        0.6f, 0.8f + level.random.nextFloat() * 0.4f);
            }
        }

        // ---- Rest snapping (Karnopp model: conditionally bypassed under active thrust) --------
        // Task 2: Under active thrust, do NOT apply deadband velocity clamping or exponential
        // decay to linear velocity, allowing single-tick accelerations to build naturally.
        if (!anchored && !hasActiveThrust && planarSpeed < 0.05) {
            Vector3d worldUpSnap = new Vector3d(0.0, 1.0, 0.0);
            Vector3d localGravityUpSnap = pose.transformNormalInverse(worldUpSnap, new Vector3d());
            double snapRoll = localGravityUpSnap.dot(localRight);
            double snapPitch = localGravityUpSnap.dot(localForward);

            double keep = Math.exp(-12.0 * dt);
            Vector3d planarDelta = new Vector3d(
                    shipVelWorld.x * (keep - 1.0), 0.0,
                    shipVelWorld.z * (keep - 1.0));
            Vector3d angDelta = new Vector3d();
            if (Math.abs(snapRoll) < 0.03 && Math.abs(snapPitch) < 0.03) {
                angDelta.set(omegaWorld.x * (keep - 1.0), 0.0,
                             omegaWorld.z * (keep - 1.0));
            }
            body.addLinearAndAngularVelocity(planarDelta, angDelta);
        }

        // ---- Wind vanes ----------------------------------------------------------
        updateWindVanes(steering.vanes(), forwardWorld, trueWindWorld, shipVelWorld);

        if (stepCounter.get() % 20L == 0L && thrustMag > 1.0e-9) {
            SereneSailing.LOGGER.debug(String.format(
                    "[SereneSailing] %s | wind %.2f m/s @ %.0f deg | thrust %.0f N | speed %.2f m/s",
                    subLevel.getName(), effectiveWindSpeed, rawWindAngle,
                    thrustMag, shipVelWorld.length()));

            // Wind billow while the sails generate near-maximum lift.
            double maxAeroRef = steering.sailAreaEfficiency() * FORCE_PER_FULL_SAIL_N;
            if (!anchored && maxAeroRef > 0.0 && thrustMag >= maxAeroRef * 0.75 && stepCounter.get() % 100L == 0L) {
                level.playSound(null,
                        BlockPos.containing(worldPos.x(), worldPos.y(), worldPos.z()),
                        ModSounds.WIND_BILLOW.get(), SoundSource.AMBIENT, 0.7f, 1.0f);
            }
        }

        // ---- Momentum Inertia Guard (Maximum Per-Tick Deceleration Clamp) ----
        // Unless the Capstan Anchor is locked or the ship collides with solid world terrain,
        // horizontal speed (fwdSpeed) must NEVER be reduced by hydrodynamic/wave/damping
        // forces by more than 2.5% per tick (maxDeltaV = -0.025 * currentSpeed).
        // Works bidirectionally in both forward headway and reverse sternway.
        if (!anchored && Math.abs(initialFwdSpeed) > 0.05) {
            Vector3d currentVelWorld = new Vector3d(body.getLinearVelocity());
            double currentFwdSpeed = currentVelWorld.dot(forwardWorld);
            double deltaFwd = currentFwdSpeed - initialFwdSpeed;
            double maxLoss = 0.025 * Math.abs(initialFwdSpeed) * (dt / 0.05);
            if (initialFwdSpeed > 0.0 && deltaFwd < -maxLoss) {
                double clampedFwdSpeed = initialFwdSpeed - maxLoss;
                double restore = clampedFwdSpeed - currentFwdSpeed;
                Vector3d restoreImpulse = new Vector3d(forwardWorld).mul(restore);
                body.addLinearAndAngularVelocity(restoreImpulse, new Vector3d());
                shipVelWorld.add(restoreImpulse);
                fwdSpeed = clampedFwdSpeed;
                FORWARD_SPEED_CACHE.put(subLevelId, fwdSpeed);
            } else if (initialFwdSpeed < 0.0 && deltaFwd > maxLoss) {
                double clampedFwdSpeed = initialFwdSpeed + maxLoss;
                double restore = clampedFwdSpeed - currentFwdSpeed;
                Vector3d restoreImpulse = new Vector3d(forwardWorld).mul(restore);
                body.addLinearAndAngularVelocity(restoreImpulse, new Vector3d());
                shipVelWorld.add(restoreImpulse);
                fwdSpeed = clampedFwdSpeed;
                FORWARD_SPEED_CACHE.put(subLevelId, fwdSpeed);
            }
        }
    }

    /**
     * Planar hydrodynamic resistance for a hull under way, applied every
     * physics sub-step:
     * <ul>
     *   <li>Dual linear + quadratic drag opposing the planar velocity,</li>
     *   <li>rotational water damping (all axes),</li>
     *   <li>heavy lateral keel resistance on the hull's <b>local X</b> axis
     *       ({@code F = -400 * v_lateral}) so apparent-wind thrust and heel
     *       convert into forward motion along local -Z instead of sideslip
     *       that would stall the rudder's steering authority.</li>
     * </ul>
     */
    private static void applyHydrodynamicForces(RigidBodyHandle body,
                                                Pose3dc pose,
                                                Vector3d shipVelWorld,
                                                Vector3d omegaWorld,
                                                double planarSpeed,
                                                boolean zeroThrust,
                                                float activeHelmAngle,
                                                double dt,
                                                double massScale,
                                                double dragForceMag,
                                                Vector3d localRight) {
        // Dual linear + quadratic planar drag opposing world-frame motion.
        if (planarSpeed > 1.0e-9) {
            Vector3d dragForceWorld = new Vector3d(
                    shipVelWorld.x / planarSpeed, 0.0, shipVelWorld.z / planarSpeed)
                    .mul(-dragForceMag);
            Vector3d dragImpulseLocal =
                    pose.transformNormalInverse(dragForceWorld, new Vector3d()).mul(dt * massScale);
            body.applyLinearAndAngularImpulse(dragImpulseLocal, new Vector3d(), true);
        }

        // Rotational water damping (all axes, world frame -> local impulse).
        if (omegaWorld.lengthSquared() > 1.0e-12) {
            Vector3d dragTorqueWorld = omegaWorld.mul(
                    -SailingConfig.ANGULAR_DRAG_MU.get(), new Vector3d());
            Vector3d dragTorqueLocal =
                    pose.transformNormalInverse(dragTorqueWorld, new Vector3d()).mul(dt);
            body.applyLinearAndAngularImpulse(new Vector3d(), dragTorqueLocal, true);
        }

        // Lateral keel resistance: vLateral is the projection of localVel onto localRight
        // Applied directly as a local-frame linear impulse scaled by massScale.
        Vector3d localVel = pose.transformNormalInverse(shipVelWorld, new Vector3d());
        double vLateral = localVel.dot(localRight);
        if (Math.abs(vLateral) > 1.0e-9) {
            double lateralDamping = KEEL_LATERAL_DAMPING;
            if (Math.abs(activeHelmAngle) >= 0.5f || Math.abs(omegaWorld.y) > 0.01) {
                lateralDamping *= 0.30; // 70% reduction during active turning to preserve speed
            }
            Vector3d swayImpulse = new Vector3d(localRight).mul(-lateralDamping * vLateral * dt * massScale);
            body.applyLinearAndAngularImpulse(swayImpulse, new Vector3d(), true);
        }

        // ---- Zero-thrust glide-to-stop ---------------------------------------
        // When planarSpeed > 0.05 m/s, an unpowered ship coasts with low longitudinal drag through turns/tacks.
        // Exponential stopping decay only engages when the hull has already slowed to near-rest (<= 0.05 m/s).
        if (zeroThrust && planarSpeed <= 0.05) {
            double keep = Math.exp(-2.0 * dt);
            Vector3d planarDelta = new Vector3d(
                    shipVelWorld.x * (keep - 1.0), 0.0, shipVelWorld.z * (keep - 1.0));
            if (planarSpeed < 0.02) {
                planarDelta.set(-shipVelWorld.x, 0.0, -shipVelWorld.z);
            }
            body.addLinearAndAngularVelocity(planarDelta, new Vector3d());

            // Rotational yaw damping: a stationary AND centered-helm ship
            // generates no rudder lift — there is no water flowing past the
            // foil — so leftover yaw angular velocity is damped hard.
            // FIX: Skip this when the rudder is deflected, otherwise the
            // damping wipes out the steering torque applied right after.
            if (Math.abs(activeHelmAngle) < 0.5f) {
                double omegaY = body.getAngularVelocity().y();
                body.addLinearAndAngularVelocity(new Vector3d(),
                        new Vector3d(0.0, -omegaY * (1.0 - Math.exp(-3.0 * dt)), 0.0));
            }
        }
    }

    /**
     * Aeronautics-style stabilized turning: calls RudderHydrodynamics.calculateRudderForces()
     * per submerged rudder, applying pure local-frame yaw torque plus active roll/pitch rate damping.
     * Guarantees low-speed maneuvering flow velocity when underway or catching wind.
     */
    private static void applySteeringAndStabilization(ServerSubLevel subLevel,
                                                      RigidBodyHandle body,
                                                      Pose3dc pose,
                                                      ServerLevel level,
                                                      Vector3d forwardWorld,
                                                      Vector3d shipVelWorld,
                                                      Vector3d omegaWorld,
                                                      double planarSpeed,
                                                      boolean sailsCatchingWind,
                                                      boolean anchored,
                                                      double dt,
                                                      SteeringCache steering,
                                                      double thrustForceN,
                                                      double dragForceMag,
                                                      double totalMass,
                                                      double massScale,
                                                      Vector3d localForward,
                                                      Vector3d localRight,
                                                      Vector3d localLinearImpulse,
                                                      boolean hasActiveThrust,
                                                      double wavePitchTarget,
                                                      double waveRollTarget) {
        List<RudderInfo> rudders = getRudders(subLevel);

        // 1. Transform world gravity vertical (0, 1, 0) into ship body-local space
        Vector3d worldUp = new Vector3d(0.0, 1.0, 0.0);
        Vector3d localGravityUp = pose.transformNormalInverse(worldUp, new Vector3d());

        // 2. Measure true inclination angles relative to gravity:
        // Roll is rotation about localForward; Pitch is rotation about localRight
        double rollAngle = Mth.clamp(localGravityUp.dot(localRight), -1.0, 1.0);
        double pitchAngle = Mth.clamp(localGravityUp.dot(localForward), -1.0, 1.0);

        // 3. Local angular velocities around longitudinal (forward) and transverse (right) axes
        Vector3d omegaLocal = pose.transformNormalInverse(omegaWorld, new Vector3d());
        double omegaRoll = omegaLocal.dot(localForward);
        double omegaPitch = omegaLocal.dot(localRight);

        // 4. Velocity-Space PD Attitude Control:
        // Direct velocity-space stabilization is completely independent of Rapier's internal inertia tensor,
        // preventing numerical impulse overshoot (which caused large multi-deck ships to topple).
        // Roll requires +4.0 * (rollAngle - waveRollTarget) by right-hand rule on localForward!
        double targetOmegaRoll = 4.0 * (rollAngle - waveRollTarget);
        // Pitch requires -4.0 * (pitchAngle - wavePitchTarget) by right-hand rule on localRight!
        double targetOmegaPitch = -4.0 * (pitchAngle - wavePitchTarget);

        double deltaOmegaRoll = (targetOmegaRoll - omegaRoll) * 0.35;
        double deltaOmegaPitch = (targetOmegaPitch - omegaPitch) * 0.35;

        Vector3d deltaOmegaLocal = new Vector3d(localForward).mul(deltaOmegaRoll)
                .add(new Vector3d(localRight).mul(deltaOmegaPitch));
        Vector3d deltaOmegaWorld = pose.transformNormal(deltaOmegaLocal, new Vector3d());
        body.addLinearAndAngularVelocity(new Vector3d(0.0, 0.0, 0.0), deltaOmegaWorld);

        double yawTorque = 0.0;
        float unifiedSteeringAngle = 0.0f;
        int submergedRudderCount = 0;
        if (!anchored) {
            unifiedSteeringAngle = getUnifiedSteeringAngle(subLevel, rudders);
            // Ensure all active contraption rudders are synchronized to the unified helm deflection angle
            for (RudderInfo rudder : rudders) {
                if (rudder.blockEntity().getSteeringAngle() != unifiedSteeringAngle) {
                    rudder.blockEntity().setSteeringAngle(unifiedSteeringAngle);
                    rudder.blockEntity().setChanged();
                    Level rLvl = rudder.blockEntity().getLevel() != null ? rudder.blockEntity().getLevel() : level;
                    rLvl.sendBlockUpdated(rudder.blockEntity().getBlockPos(), rudder.blockEntity().getBlockState(), rudder.blockEntity().getBlockState(), 3);
                }
            }
            if (!rudders.isEmpty() && Math.abs(unifiedSteeringAngle) >= 0.5f) {
                // Local Body Frame velocity extraction: getLinearVelocity() is in
                // WORLD coordinates, so rotate into body frame to read forward speed.
                Vector3d localVel = pose.transformNormalInverse(shipVelWorld, new Vector3d());
                localVel.y = 0.0;
                double vFwd = localVel.dot(localForward);
                double forwardSpeedKnots = vFwd * 1.944;

                // Clamp speed factor between 0 and 12 knots for rudder lift calculations
                double effectiveRudderSpeed = Mth.clamp(Math.abs(forwardSpeedKnots), 0.5, 12.0);
                double vSteer = Math.copySign(effectiveRudderSpeed, vFwd);

                double facingSignSum = 0.0;
                for (RudderInfo rudder : rudders) {
                    Vector3d rudderWorld = pose.transformPosition(
                            new Vector3d(rudder.localPoint()), new Vector3d());

                    // MMG 4-point vertical blade submersion ratio:
                    // Blade Deep Reach  (rudderWorld.y - 1.50): weight 0.25 (detects water for rudders mounted 1-2 blocks above keel on tall sterns)
                    // Blade Bottom Tip  (rudderWorld.y - 0.65): weight 0.35
                    // Blade Mid-Section (rudderWorld.y - 0.15): weight 0.25
                    // Blade Top Stock   (rudderWorld.y + 0.35): weight 0.15
                    double rudderSubmersion = 0.0;
                    if (isPointSubmerged(level, rudderWorld.x, rudderWorld.y - 1.50, rudderWorld.z)) {
                        rudderSubmersion += 0.25;
                    }
                    if (isPointSubmerged(level, rudderWorld.x, rudderWorld.y - 0.65, rudderWorld.z)) {
                        rudderSubmersion += 0.35;
                    }
                    if (isPointSubmerged(level, rudderWorld.x, rudderWorld.y - 0.15, rudderWorld.z)) {
                        rudderSubmersion += 0.25;
                    }
                    if (isPointSubmerged(level, rudderWorld.x, rudderWorld.y + 0.35, rudderWorld.z)) {
                        rudderSubmersion += 0.15;
                    }

                    if (rudderSubmersion <= 0.0) {
                        continue;
                    }
                    submergedRudderCount++;

                    Direction facing = rudder.facing();
                    Vector3d rudderDir = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
                    double sign = (rudderDir.dot(localForward) <= 0.0) ? 1.0 : -1.0;
                    facingSignSum += sign * rudderSubmersion;
                }

                if (submergedRudderCount > 0 && Math.abs(facingSignSum) > 1.0e-5) {
                    // FIX: Direct inline torque formula — STEER_TORQUE_COEFF is
                    // applied directly as tau = -coeff * v * sin(theta) * facingSignSum.
                    // The old RudderHydrodynamics bridge divided by 800, zeroing
                    // the result. This formula is proven and well-scaled.
                    double thetaRad = Math.toRadians(unifiedSteeringAngle);
                    yawTorque = -SailingConfig.STEER_TORQUE_COEFF.get() * vSteer
                            * Math.sin(thetaRad) * facingSignSum;
                }
            }
        }

        double yawImpulse = Math.max(-MAX_YAW_IMPULSE_PER_SUBSTEP,
                Math.min(MAX_YAW_IMPULSE_PER_SUBSTEP, yawTorque * dt));

        // Hydrodynamic Hull Yaw Damping (Nr cross-flow derivative - Fossen / MMG model)
        // Local yaw angular velocity (rotation about +Y axis)
        double omegaYaw = omegaLocal.y;

        // Linear + quadratic water drag resisting spin
        double YAW_DAMP_LINEAR = 65.0;
        double YAW_DAMP_QUAD   = 35.0;
        double yawDampingTorque = -(YAW_DAMP_LINEAR * omegaYaw + YAW_DAMP_QUAD * omegaYaw * Math.abs(omegaYaw));

        double yawDampingImpulse = yawDampingTorque * dt;
        yawImpulse += yawDampingImpulse;

        // 6. Apply pure yaw steering torque to rigid body
        Vector3d yawTorqueVec = new Vector3d(0.0, yawImpulse, 0.0);
        body.applyLinearAndAngularImpulse(new Vector3d(0.0, 0.0, 0.0), yawTorqueVec, true);

        // ---- In-Game Runtime Diagnostic Telemetry (throttled ~1 Hz / 20 ticks) ----
        long lastPhysTick = PHYS_DEBUG_TICK.getOrDefault(subLevel.getUniqueId(), -1L);
        if (level.getGameTime() % 20 == 0 && level.getGameTime() != lastPhysTick && hasActiveThrust) {
            PHYS_DEBUG_TICK.put(subLevel.getUniqueId(), level.getGameTime());
            LOGGER.debug("[PHYS-DEBUG] Speed: {} m/s ({} kts) | ThrustN: {} | DragN: {} | MassScale: {}",
                planarSpeed, planarSpeed * 1.94384, thrustForceN, dragForceMag, massScale);
            LOGGER.debug("[PHYS-DEBUG] ActiveSails: {} | AreaEff: {} | ThrustN: {} | BodyMass: {}",
                steering.sailCount(), steering.sailAreaEfficiency(), thrustForceN, totalMass);
            LOGGER.debug("[PHYS-DEBUG] localForward: ({}, {}, {}) | LinImpulse: ({}, {}, {})",
                localForward.x, localForward.y, localForward.z,
                localLinearImpulse.x, localLinearImpulse.y, localLinearImpulse.z);
            LOGGER.debug("[PHYS-DEBUG] Angles -> Roll: {} rad, Pitch: {} rad | OmegaLocal: ({}, {}, {})",
                rollAngle, pitchAngle, omegaLocal.x, omegaLocal.y, omegaLocal.z);
            LOGGER.debug("[PHYS-DEBUG] YawImpulse: {}, DeltaOmegaWorld: ({}, {}, {})",
                yawImpulse, deltaOmegaWorld.x, deltaOmegaWorld.y, deltaOmegaWorld.z);
        }

        // ---- Diagnostic telemetry (throttled ~1 Hz) -------------------------
        if (stepCounter.get() % 20 == 0 && Math.abs(unifiedSteeringAngle) >= 0.5f) {
            Vector3d angVelAfter = new Vector3d(body.getAngularVelocity());
            SereneSailing.LOGGER.debug(String.format(
                    "[STEER-DIAG] SubLevel: %s | HelmAngle: %.1f deg | SubmergedRudders: %d | "
                  + "ForwardSpeed: %.3f m/s | RawTorque: %.2f | YawImpulse: %.4f | "
                  + "AngVelY: %.4f rad/s | sailsCatchingWind: %s",
                    subLevel.getUniqueId(),
                    unifiedSteeringAngle,
                    submergedRudderCount,
                    shipVelWorld.dot(forwardWorld),
                    yawTorque,
                    yawImpulse,
                    angVelAfter.y,
                    sailsCatchingWind));
        }
    }

    /**
     * True when any capstan on this vessel is actively deployed and anchored.
     * As soon as the anchor status changes to WEIGHED / RETRACTING, the hard
     * mooring position lock is immediately released so the vessel can drift or sail.
    /**
     * True when any capstan on this vessel is actively deployed and biting the seabed (moored).
     * While the anchor is still dropping through the water column, it drags through water
     * before grabbing the bottom.
     */
    private static boolean isAnchored(SteeringCache steering) {
        if (!steering.capstans().isEmpty()) {
            for (CapstanBlockEntity capstan : steering.capstans()) {
                if (capstan.isHolding()) {
                    return true;
                }
            }
            return false;
        }
        // Fallback to blockstate anchorState if capstan BEs have not yet been indexed
        return steering.anchorState();
    }

    private static boolean isAnchorDropping(SteeringCache steering) {
        if (!steering.capstans().isEmpty()) {
            for (CapstanBlockEntity capstan : steering.capstans()) {
                if (capstan.isAnchored() && !capstan.isHolding()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void invalidateSteeringCache(UUID subLevelId) {
        if (subLevelId == null) {
            return;
        }
        STEERING_CACHE.remove(subLevelId);
        STEERING_CACHE_STEP.remove(subLevelId);
    }

    /** Publishes live wind telemetry into every masthead wind vane. */
    private static void updateWindVanes(List<WindVaneBlockEntity> vanes,
                                        Vector3d forwardWorld,
                                        Vector3d trueWindWorld,
                                        Vector3d shipVelWorld) {
        for (WindVaneBlockEntity vane : vanes) {
            vane.updateWindTelemetry(forwardWorld, trueWindWorld, shipVelWorld);
        }
    }

    /** Returns the cached {@link SteeringCache}, rescanning when stale. */
    private static SteeringCache getSteeringCache(ServerSubLevel subLevel) {
        UUID id = subLevel.getUniqueId();
        SteeringCache cached = STEERING_CACHE.get(id);
        Long stamp = STEERING_CACHE_STEP.get(id);
        // FIX (Bug 6): Always reuse cache for STEERING_RESCAN_STEPS regardless
        // of rudder count. The old condition `!cached.rudders().isEmpty()` caused
        // every physics step to rescan O(chunks×16³) on rudderless vessels.
        if (cached != null && stamp != null
                && (stepCounter.get() - stamp) < STEERING_RESCAN_STEPS) {
            return cached;
        }
        SteeringCache scanned = scanSteering(subLevel);
        STEERING_CACHE.put(id, scanned);
        STEERING_CACHE_STEP.put(id, stepCounter.get());
        return scanned;
    }

    private static List<RudderInfo> getRudders(ServerSubLevel subLevel) {
        return getSteeringCache(subLevel).rudders();
    }

    /** Helm angle if cached or a wheel is present; otherwise the first rudder's own angle. */
    private static float getUnifiedSteeringAngle(ServerSubLevel subLevel, List<RudderInfo> rudders) {
        Float cached = HELM_ANGLE_CACHE.get(subLevel.getUniqueId());
        if (cached != null) {
            return cached;
        }
        List<HelmBlockEntity> helms = getSteeringCache(subLevel).helms();
        if (!helms.isEmpty()) {
            float angle = helms.get(0).getSteeringAngle();
            HELM_ANGLE_CACHE.put(subLevel.getUniqueId(), angle);
            return angle;
        }
        return rudders.isEmpty() ? 0.0f : rudders.get(0).blockEntity().getSteeringAngle();
    }

    /**
     * Calculates the hydrostatic equilibrium draft [m] from the keel upwards by integrating
     * slice volumes layer-by-layer until displaced water weight equals total vessel mass.
     * If the vessel is overloaded (required volume exceeds total hull displacement), returns
     * a draft exceeding hull height, signaling negative buoyancy / sinking.
     */
    public static double calculateArchimedesDraft(double[] sliceVolumes, double totalMassKg, double waterDensityKgM3) {
        if (sliceVolumes == null || sliceVolumes.length == 0 || totalMassKg <= 0.0) {
            return 0.0;
        }
        double waterDensity = waterDensityKgM3 > 0.0 ? waterDensityKgM3 : 1000.0;
        double reqVolume = totalMassKg / waterDensity;
        double accumulatedVolume = 0.0;
        double draft = 0.0;
        int n = sliceVolumes.length;

        for (int i = 0; i < n; i++) {
            double v = sliceVolumes[i];
            if (v <= 0.0) {
                draft += 1.0;
                continue;
            }
            if (accumulatedVolume + v < reqVolume) {
                accumulatedVolume += v;
                draft += 1.0;
            } else {
                double fraction = (reqVolume - accumulatedVolume) / v;
                draft += fraction;
                return draft;
            }
        }

        // Negative buoyancy: mass exceeds total displacement volume
        double lastWaterplaneArea = 1.0;
        for (int i = n - 1; i >= 0; i--) {
            if (sliceVolumes[i] > 0.0) {
                lastWaterplaneArea = sliceVolumes[i];
                break;
            }
        }
        double excessVolume = reqVolume - accumulatedVolume;
        draft = n + (excessVolume / lastWaterplaneArea);
        return draft;
    }

    /**
     * Scans plot chunks for rudders (with facings), helm stations, wind vanes,
     * capstans, taffrail logs, and computes exact slice-by-slice Archimedes draft.
     */
    public record StormPhysicsResult(boolean isLiftedByTornado, double verticalLiftVelocity, double stormWindSpeed) {}

    public static StormPhysicsResult applyStormAndTornadoForces(
            RigidBodyHandle body,
            Weather2Compat.StormData storm,
            org.joml.Vector3dc worldPos,
            Vector3d shipVelWorld,
            int blockCount,
            boolean hasEnclosedHold,
            boolean anchored,
            double sailAreaEfficiency,
            int sailCount,
            double time,
            double dt
    ) {
        if (storm == null || storm.intensityStage() <= 0) {
            return new StormPhysicsResult(false, 0.0, 0.0);
        }

        double stormX = storm.stormX();
        double stormZ = storm.stormZ();
        double dist = storm.distance();
        double radius = Math.max(30.0, storm.radius());
        int stage = storm.intensityStage();

        double dx = stormX - worldPos.x();
        double dz = stormZ - worldPos.z();
        double dLen = Math.sqrt(dx * dx + dz * dz);
        Vector3d inwardDir = dLen > 0.1 ? new Vector3d(dx / dLen, 0.0, dz / dLen) : new Vector3d(0.0, 0.0, 0.0);
        // Tangential cyclonic direction (counter-clockwise)
        Vector3d tangentDir = new Vector3d(-inwardDir.z, 0.0, inwardDir.x);

        double influenceRadius = radius * 2.0;
        if (dist > influenceRadius) {
            return new StormPhysicsResult(false, 0.0, 0.0);
        }

        double proximity = Mth.clamp(1.0 - (dist / influenceRadius), 0.0, 1.0);
        double vortexRadius = radius * 1.5;
        double vortexProx = Mth.clamp(1.0 - (dist / vortexRadius), 0.0, 1.0);
        boolean isLightVessel = blockCount <= 150 || !hasEnclosedHold;

        boolean isLifted = false;
        double liftVy = 0.0;
        double stormWindSpeed = (15.0 + stage * 8.0) * proximity;

        // Stage 1 — Severe Gale / Squall (F0–F2 or Heavy Storm: stage 1..3)
        if (stage >= 1) {
            Vector3d stormWind = new Vector3d(tangentDir).mul(0.7).add(new Vector3d(inwardDir).mul(0.3));
            if (stormWind.lengthSquared() > 1e-4) stormWind.normalize();

            // Tangential cyclonic wind gusts spiraling into storm center
            Vector3d gustImpulse = new Vector3d(stormWind).mul(stormWindSpeed * (isLightVessel ? 0.25 : 0.15) * dt);
            if (body != null) {
                body.addLinearAndAngularVelocity(gustImpulse, new Vector3d());
            }

            // Violent wave heeling / pitching buffets:
            // Tall-masted brigs list heavily (15°–25°) unless sails are reefed (HALF) or furled (ROLLED)
            double deploymentRatio = sailCount > 0 ? (sailAreaEfficiency / (double) sailCount) : 0.25;
            double listTargetRad = Math.toRadians(15.0 + Math.min(stage, 3) * 3.5) * deploymentRatio;
            double heelRollRate = Math.sin(time * 0.15) * listTargetRad * 2.5 * dt;
            double pitchBuffetRate = Math.cos(time * 0.2) * 0.12 * dt;
            if (body != null) {
                body.addLinearAndAngularVelocity(new Vector3d(), new Vector3d(pitchBuffetRate, 0.0, heelRollRate));
            }
        }

        // Stage 2 — Major Tornado (F3: stage == 4)
        if (stage == 4) {
            double suctionSpeed = (isLightVessel ? 5.0 : 2.0) * proximity;
            Vector3d suctionDelta = new Vector3d(inwardDir).mul(suctionSpeed * dt);
            double spinYawRate = (isLightVessel ? 1.5 : 0.5) * proximity;
            double waveSlam = (Math.sin(time * 0.35) > 0.4 ? 2.5 : -1.2) * proximity * dt;
            if (body != null) {
                body.addLinearAndAngularVelocity(new Vector3d(suctionDelta.x, waveSlam, suctionDelta.z),
                        new Vector3d(0.0, spinYawRate * dt, 0.0));
            }
        }

        // Stage 3 — Devastating Tornado (F4 & F5: stage >= 5)
        if (stage >= 5 && dist <= vortexRadius) {
            if (isLightVessel) {
                isLifted = true;
                // Upward Suction Lift (+Y):
                // vy climbing up to +4.0 to +12.0 m/s, lifting the raft 15 to 50+ blocks high!
                double maxTargetVy = 4.0 + (stage - 4) * 4.0 * vortexProx; // 8.0 m/s for F4, 12.0 m/s for F5
                double currentVy = shipVelWorld.y;
                double deltaVy;
                if (currentVy < maxTargetVy) {
                    deltaVy = Math.min(maxTargetVy - currentVy, (12.0 + (stage - 4) * 8.0) * dt);
                } else {
                    deltaVy = 0.0;
                }
                liftVy = currentVy + deltaVy;

                // Tangential vortex spin & inward pull (X, Z + Yaw/Roll/Pitch):
                double tangentialSpeed = (14.0 + (stage - 4) * 6.0) * vortexProx;
                double inwardSpeed = (5.0 + (stage - 4) * 3.0) * vortexProx;
                Vector3d vortexHoriz = new Vector3d(tangentDir).mul(tangentialSpeed)
                        .add(new Vector3d(inwardDir).mul(inwardSpeed));

                Vector3d horizDelta = new Vector3d(vortexHoriz.x - shipVelWorld.x, 0.0, vortexHoriz.z - shipVelWorld.z)
                        .mul(Math.min(1.0, 3.5 * dt));

                // If raft climbs high into the funnel cloud (> 100 blocks altitude), fling it outward
                if (worldPos.y() > 100.0) {
                    horizDelta.add(new Vector3d(inwardDir).mul(-15.0 * dt));
                }

                double spinYaw = (3.0 + (stage - 4) * 1.5) * dt;
                double spinRoll = Math.sin(time * 0.25) * 2.0 * dt;
                double spinPitch = Math.cos(time * 0.25) * 1.5 * dt;

                if (body != null) {
                    body.addLinearAndAngularVelocity(
                            new Vector3d(horizDelta.x, deltaVy, horizDelta.z),
                            new Vector3d(spinPitch, spinYaw, spinRoll)
                    );
                }
            } else {
                // Heavy Brigs / Large Ships:
                // Violent cyclonic whirlpool dragging toward core
                Vector3d whirlpool = new Vector3d(tangentDir).mul(6.0 * vortexProx)
                        .add(new Vector3d(inwardDir).mul(3.0 * vortexProx));
                Vector3d dragDelta = new Vector3d(whirlpool.x - shipVelWorld.x, 0.0, whirlpool.z - shipVelWorld.z)
                        .mul(Math.min(1.0, 1.5 * dt));

                // Extreme heeling (up to 35°):
                double heelAngle35 = Math.toRadians(35.0) * vortexProx;
                double heelImpulse = (Math.sin(time * 0.2) > 0 ? 1.0 : -1.0) * heelAngle35 * 0.6 * dt;

                // Violent vertical heave surges (+2.0 to +4.5m)
                double heaveSurge = (2.0 + (stage - 4) * 1.25) * vortexProx;
                double heaveImpulse = Math.sin(time * 0.3) * heaveSurge * dt;

                // Involuntary yaw spinning unless anchored
                double involuntaryYaw = anchored ? 0.0 : (0.6 * vortexProx * dt);

                if (body != null) {
                    body.addLinearAndAngularVelocity(
                            new Vector3d(dragDelta.x, heaveImpulse, dragDelta.z),
                            new Vector3d(0.0, involuntaryYaw, heelImpulse)
                    );
                }
            }
        }

        return new StormPhysicsResult(isLifted, liftVy, stormWindSpeed);
    }

    public static Vector3d simulateVesselStormStep(
            Vector3d pos,
            Vector3d vel,
            Weather2Compat.StormData storm,
            int blockCount,
            boolean hasEnclosedHold,
            boolean anchored,
            double time,
            double dt
    ) {
        StormPhysicsResult res = applyStormAndTornadoForces(
                null, storm, pos, vel, blockCount, hasEnclosedHold, anchored,
                1.0, 4, time, dt
        );

        Vector3d nextVel = new Vector3d(vel);
        if (res.isLiftedByTornado()) {
            double maxTargetVy = 4.0 + (storm.intensityStage() - 4) * 4.0;
            double deltaVy = Math.min(maxTargetVy - nextVel.y, (12.0 + (storm.intensityStage() - 4) * 8.0) * dt);
            nextVel.y += Math.max(0.0, deltaVy);

            double vortexRadius = Math.max(30.0, storm.radius()) * 1.5;
            double vortexProx = Mth.clamp(1.0 - (storm.distance() / vortexRadius), 0.0, 1.0);
            double dx = storm.stormX() - pos.x;
            double dz = storm.stormZ() - pos.z;
            double dLen = Math.sqrt(dx * dx + dz * dz);
            Vector3d inwardDir = dLen > 0.1 ? new Vector3d(dx / dLen, 0.0, dz / dLen) : new Vector3d(0, 0, 0);
            Vector3d tangentDir = new Vector3d(-inwardDir.z, 0.0, inwardDir.x);
            double tangentialSpeed = (14.0 + (storm.intensityStage() - 4) * 6.0) * vortexProx;
            double inwardSpeed = (5.0 + (storm.intensityStage() - 4) * 3.0) * vortexProx;
            Vector3d vortexHoriz = new Vector3d(tangentDir).mul(tangentialSpeed)
                    .add(new Vector3d(inwardDir).mul(inwardSpeed));
            Vector3d horizDelta = new Vector3d(vortexHoriz.x - nextVel.x, 0.0, vortexHoriz.z - nextVel.z)
                    .mul(Math.min(1.0, 3.5 * dt));
            nextVel.x += horizDelta.x;
            nextVel.z += horizDelta.z;
        } else {
            // Large ship or outside vortex: heave surge and settling toward water level 63.0
            double heaveSurge = (2.0 + (storm.intensityStage() - 4) * 1.25) * Math.sin(time * 0.3) * dt;
            double targetY = 63.0;
            double depth = targetY - pos.y;
            double accelY = (depth * 10.0 - nextVel.y * 3.0);
            accelY = Mth.clamp(accelY, -16.0, 16.0);
            nextVel.y += accelY * dt + heaveSurge;
            nextVel.y = Mth.clamp(nextVel.y, -16.0, 16.0);
        }

        return nextVel;
    }

    /**
     * Purges water and waterlogged state from the interior of a registered sunken hold.
     *
     * <p>Works in plot-local space on the scanned hull volume ({@link HoldRegistry}): the chunk-section filter is
     * the volume's own local Y range, never a hard-coded world-Y window (plot-local Y is not world Y), and the
     * per-block test is the same sealed volume the renderer and entity physics use. The two outer wall columns
     * of each row (the hull skin) are left alone.
     */
    public static int purgeSubLevelHoldWater(ServerSubLevel subLevel) {
        if (subLevel == null) return 0;
        ServerLevel subLevelWorld = subLevel.getLevel();
        if (subLevelWorld == null) return 0;
        var plot = subLevel.getPlot();
        if (plot == null) return 0;
        final HoldScanner.Envelope env = HoldRegistry.activeEnvelope(subLevel);
        if (env == null || !env.hasVolume()) return 0;

        int purged = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            final int chunkMinX = chunk.getPos().getMinBlockX();
            final int chunkMinZ = chunk.getPos().getMinBlockZ();
            if (chunkMinX + 15 < env.volumeMinX() || chunkMinX > env.volumeMaxX()
                    || chunkMinZ + 15 < env.volumeMinZ() || chunkMinZ > env.volumeMaxZ()) continue;
            LevelChunkSection[] sections = chunk.getSections();
            for (int secIdx = 0; secIdx < sections.length; secIdx++) {
                LevelChunkSection section = sections[secIdx];
                if (section == null || section.hasOnlyAir()) continue;
                int sectionY = chunk.getSectionYFromSectionIndex(secIdx);
                int baseBlockY = sectionY << 4;
                if (baseBlockY > env.volumeMaxY() || (baseBlockY + 15) < env.volumeMinY()) continue;
                for (int sy = 0; sy < 16; sy++) {
                    for (int sz = 0; sz < 16; sz++) {
                        for (int sx = 0; sx < 16; sx++) {
                            BlockState state = section.getBlockState(sx, sy, sz);
                            // Any block that carries water: plain / flowing water, bubble columns, waterlogged blocks AND
                            // water plants (seagrass, kelp), which hold water without a WATERLOGGED property. Sable's
                            // entity swimming samples this plot-level water, so missing it leaves a ship with "wet" cells.
                            if (!state.getFluidState().is(FluidTags.WATER)) continue;
                            boolean isWaterlogged = state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
                            boolean isFluid = !isWaterlogged && (state.is(Blocks.WATER) || state.is(Blocks.BUBBLE_COLUMN)
                                    || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer);
                            if (isFluid || isWaterlogged) {
                                pos.set(chunkMinX + sx, baseBlockY + sy, chunkMinZ + sz);
                                // Whole sealed volume plus one column around it, hull skin included: a waterlogged stair or slab
                                // in the wall is a water SOURCE that keeps spilling flowing water into the hold after the
                                // interior has been purged.
                                if (env.containsPadded(pos.getX(), pos.getY(), pos.getZ(), 1)) {
                                    if (isFluid) {
                                        subLevelWorld.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                                    } else {
                                        subLevelWorld.setBlock(pos, state.setValue(BlockStateProperties.WATERLOGGED, false), 2);
                                    }
                                    purged++;
                                }
                            }
                        }
                    }
                }
            }
        }
        return purged;
    }

    public static int purgeHoldWater(Level level, Iterable<BlockPos> positions) {
        return ShipHullAirPocketHelper.purgeHoldWater(level, positions);
    }
    private static SteeringCache scanSteering(ServerSubLevel subLevel) {
        List<RudderInfo> rudders = new ArrayList<>();
        List<HelmBlockEntity> helms = new ArrayList<>();
        List<WindVaneBlockEntity> vanes = new ArrayList<>();
        List<CapstanBlockEntity> capstans = new ArrayList<>();
        List<TaffrailLogBlockEntity> taffrails = new ArrayList<>();
        boolean anchorState = false;
        double sailAreaEfficiency = 0.0;
        int sailCount = 0;
        int blockCount = 0;
        Direction sailFacing = null;
        var plot = subLevel.getPlot();
        if (plot == null) {
            return new SteeringCache(rudders, helms, vanes, capstans, taffrails, anchorState,
                    sailAreaEfficiency, blockCount, sailFacing, sailCount,
                    new Vector3d(0.0, 0.0, 0.0), 0, 0, TARGET_HULL_DRAFT, 0, 1.0, 1.0, 1.0);
        }

        Block sailBlock = ModBlocks.CANVAS_SAIL.get();

        int minBlockX = Integer.MAX_VALUE;
        int maxBlockX = Integer.MIN_VALUE;
        int minBlockY = Integer.MAX_VALUE;
        int maxBlockY = Integer.MIN_VALUE;
        int minBlockZ = Integer.MAX_VALUE;
        int maxBlockZ = Integer.MIN_VALUE;

        double keelSumX = 0;
        double keelSumZ = 0;
        int keelCount = 0;

        // Pass 1: Scan block entities and identify 3D extents across ALL loaded chunks
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }
            try {
                for (var entry : new ArrayList<>(chunk.getBlockEntities().entrySet())) {
                    BlockPos p = entry.getKey();
                    if (entry.getValue() instanceof RudderBlockEntity rudder) {
                        BlockState rs = entry.getValue().getBlockState();
                        Direction facing = rs.hasProperty(RudderBlock.FACING)
                                ? rs.getValue(RudderBlock.FACING) : Direction.NORTH;
                        // FIX: Use block BOTTOM Y (p.getY()) for the rudder's
                        // local point so the submergence water check reliably
                        // finds water even with shallow TARGET_HULL_DRAFT.
                        rudders.add(new RudderInfo(rudder,
                                new Vector3d(p.getX() + 0.5, p.getY(), p.getZ() + 0.5),
                                facing));
                    } else if (entry.getValue() instanceof HelmBlockEntity helm) {
                        helms.add(helm);
                    } else if (entry.getValue() instanceof WindVaneBlockEntity vane) {
                        vanes.add(vane);
                    } else if (entry.getValue() instanceof CapstanBlockEntity capstan) {
                        capstans.add(capstan);
                        BlockState cs = capstan.getBlockState();
                        if (cs.getBlock() instanceof CapstanBlock
                                && cs.hasProperty(CapstanBlock.ANCHORED)
                                && cs.getValue(CapstanBlock.ANCHORED)) {
                            anchorState = true;
                        }
                    } else if (entry.getValue() instanceof TaffrailLogBlockEntity taffrail) {
                        taffrails.add(taffrail);
                    }
                }
            } catch (java.util.ConcurrentModificationException ignored) {
                // Main thread may be mutating chunk block entities during off-thread scan
            }

            LevelChunkSection[] sections = chunk.getSections();
            for (int secIdx = 0; secIdx < sections.length; secIdx++) {
                LevelChunkSection section = sections[secIdx];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                int sectionY = chunk.getSectionYFromSectionIndex(secIdx);
                int baseBlockY = sectionY << 4;
                for (int sy = 0; sy < 16; sy++) {
                    for (int sz = 0; sz < 16; sz++) {
                        for (int sx = 0; sx < 16; sx++) {
                            BlockState s = section.getBlockState(sx, sy, sz);
                            if (!s.isAir()) {
                                blockCount++;
                                if (s.getBlock() == sailBlock) {
                                    sailCount++;
                                    sailAreaEfficiency += s.getValue(SailBlock.SAIL_STATE) * 0.5;
                                    if (sailFacing == null && s.hasProperty(SailBlock.FACING)) {
                                        sailFacing = s.getValue(SailBlock.FACING);
                                    }
                                }

                                int blockX = chunk.getPos().getMinBlockX() + sx;
                                int blockY = baseBlockY + sy;
                                int blockZ = chunk.getPos().getMinBlockZ() + sz;

                                if (blockX < minBlockX) minBlockX = blockX;
                                if (blockX > maxBlockX) maxBlockX = blockX;
                                if (blockY < minBlockY) {
                                    minBlockY = blockY;
                                    keelSumX = blockX + 0.5;
                                    keelSumZ = blockZ + 0.5;
                                    keelCount = 1;
                                } else if (blockY == minBlockY) {
                                    keelSumX += blockX + 0.5;
                                    keelSumZ += blockZ + 0.5;
                                    keelCount++;
                                }
                                if (blockY > maxBlockY) maxBlockY = blockY;
                                if (blockZ < minBlockZ) minBlockZ = blockZ;
                                if (blockZ > maxBlockZ) maxBlockZ = blockZ;
                            }
                        }
                    }
                }
            }
        }

        if (blockCount == 0 || minBlockY == Integer.MAX_VALUE) {
            return new SteeringCache(rudders, helms, vanes, capstans, taffrails, anchorState,
                    sailAreaEfficiency, blockCount, sailFacing, sailCount,
                    new Vector3d(0.0, 0.0, 0.0), 0, 0, TARGET_HULL_DRAFT, 0, 1.0, 1.0, 1.0);
        }

        double keelCenterX = keelCount > 0 ? (keelSumX / keelCount) : 0.0;
        double keelCenterZ = keelCount > 0 ? (keelSumZ / keelCount) : 0.0;
        double keelLocalY = minBlockY;
        Vector3d keelLocalPoint = new Vector3d(keelCenterX, keelLocalY, keelCenterZ);

        int gridWidth = maxBlockX - minBlockX + 1;
        int gridHeight = maxBlockY - minBlockY + 1;
        int gridDepth = maxBlockZ - minBlockZ + 1;

        Vector3d localForward;
        if (!helms.isEmpty()) {
            Direction helmFacing = helms.get(0).getBlockState().hasProperty(HelmBlock.FACING)
                    ? helms.get(0).getBlockState().getValue(HelmBlock.FACING) : Direction.NORTH;
            localForward = new Vector3d(helmFacing.getStepX(), helmFacing.getStepY(), helmFacing.getStepZ());
        } else if (sailFacing != null) {
            Direction bowDir = sailFacing.getOpposite();
            localForward = new Vector3d(bowDir.getStepX(), bowDir.getStepY(), bowDir.getStepZ());
        } else {
            localForward = new Vector3d(0.0, 0.0, -1.0);
        }
        if (localForward.lengthSquared() < 1.0e-4) {
            localForward = new Vector3d(0.0, 0.0, -1.0);
        } else {
            localForward.normalize();
        }
        Vector3d localUp = new Vector3d(0.0, 1.0, 0.0);
        Vector3d localRight = new Vector3d(localForward).cross(localUp);
        if (localRight.lengthSquared() < 1.0e-4) {
            localRight.set(1.0, 0.0, 0.0);
        } else {
            localRight.normalize();
        }

        double hullLength = Math.max(1.0, Math.abs(localForward.x) * gridWidth + Math.abs(localForward.z) * gridDepth);
        double hullBeam = Math.max(1.0, Math.abs(localRight.x) * gridWidth + Math.abs(localRight.z) * gridDepth);
        double totalStructureHeight = Math.max(1.0, gridHeight);

        boolean trackGrid = gridHeight <= 256 && gridWidth <= 512 && gridDepth <= 512
                && ((long) gridWidth * gridHeight * gridDepth) <= 16000000L;

        double[] sliceVolumes = new double[gridHeight];
        int totalEnclosedAirVolume = 0;

        if (trackGrid) {
            BitSet solidMask = new BitSet(gridWidth * gridHeight * gridDepth);
            short[][] minXMap = new short[gridHeight][gridDepth];
            short[][] maxXMap = new short[gridHeight][gridDepth];
            short[][] minZMap = new short[gridHeight][gridWidth];
            short[][] maxZMap = new short[gridHeight][gridWidth];
            int[] solidInLayer = new int[gridHeight];
            int[] layerMinX = new int[gridHeight];
            int[] layerMaxX = new int[gridHeight];
            int[] layerMinZ = new int[gridHeight];
            int[] layerMaxZ = new int[gridHeight];

            for (int y = 0; y < gridHeight; y++) {
                Arrays.fill(minXMap[y], Short.MAX_VALUE);
                Arrays.fill(maxXMap[y], Short.MIN_VALUE);
                Arrays.fill(minZMap[y], Short.MAX_VALUE);
                Arrays.fill(maxZMap[y], Short.MIN_VALUE);
            }
            Arrays.fill(layerMinX, Integer.MAX_VALUE);
            Arrays.fill(layerMaxX, Integer.MIN_VALUE);
            Arrays.fill(layerMinZ, Integer.MAX_VALUE);
            Arrays.fill(layerMaxZ, Integer.MIN_VALUE);

            // Pass 2: Fill solidMask, minX/maxX, minZ/maxZ, and solidInLayer
            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int secIdx = 0; secIdx < sections.length; secIdx++) {
                    LevelChunkSection section = sections[secIdx];
                    if (section == null || section.hasOnlyAir()) continue;
                    int sectionY = chunk.getSectionYFromSectionIndex(secIdx);
                    int baseBlockY = sectionY << 4;
                    for (int sy = 0; sy < 16; sy++) {
                        for (int sz = 0; sz < 16; sz++) {
                            for (int sx = 0; sx < 16; sx++) {
                                BlockState s = section.getBlockState(sx, sy, sz);
                                if (!s.isAir()) {
                                    int blockX = chunk.getPos().getMinBlockX() + sx;
                                    int blockY = baseBlockY + sy;
                                    int blockZ = chunk.getPos().getMinBlockZ() + sz;

                                    int rx = blockX - minBlockX;
                                    int ry = blockY - minBlockY;
                                    int rz = blockZ - minBlockZ;

                                    if (rx >= 0 && rx < gridWidth && ry >= 0 && ry < gridHeight && rz >= 0 && rz < gridDepth) {
                                        solidMask.set((ry * gridDepth + rz) * gridWidth + rx);
                                        if (rx < minXMap[ry][rz]) minXMap[ry][rz] = (short) rx;
                                        if (rx > maxXMap[ry][rz]) maxXMap[ry][rz] = (short) rx;
                                        if (rz < minZMap[ry][rx]) minZMap[ry][rx] = (short) rz;
                                        if (rz > maxZMap[ry][rx]) maxZMap[ry][rx] = (short) rz;
                                        if (rx < layerMinX[ry]) layerMinX[ry] = rx;
                                        if (rx > layerMaxX[ry]) layerMaxX[ry] = rx;
                                        if (rz < layerMinZ[ry]) layerMinZ[ry] = rz;
                                        if (rz > layerMaxZ[ry]) layerMaxZ[ry] = rz;
                                        solidInLayer[ry]++;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            int lowerHullLayers = Math.max(1, (int) Math.ceil(gridHeight * 0.40));
            boolean hadEnclosedAir = false;
            boolean deckCeilingReached = false;

            for (int ry = 0; ry < gridHeight; ry++) {
                int enclosedAir = 0;
                if (!deckCeilingReached) {
                    for (int rz = 0; rz < gridDepth; rz++) {
                        short minX = minXMap[ry][rz];
                        short maxX = maxXMap[ry][rz];
                        if (maxX > minX + 1) {
                            for (int rx = minX + 1; rx < maxX; rx++) {
                                if (!solidMask.get((ry * gridDepth + rz) * gridWidth + rx)) {
                                    short minZ = minZMap[ry][rx];
                                    short maxZ = maxZMap[ry][rx];
                                    if (rz > minZ && rz < maxZ) {
                                        enclosedAir++;
                                    }
                                }
                            }
                        }
                    }
                }
                if (enclosedAir > 0) {
                    hadEnclosedAir = true;
                } else if (hadEnclosedAir) {
                    // Enclosed air ceased -> main deck ceiling reached
                    deckCeilingReached = true;
                }
                totalEnclosedAirVolume += enclosedAir;
                double countedVolume = solidInLayer[ry] + enclosedAir;

                // For lower hull layers (bottom 40% of structure), ensure displacement reflects
                // the outer hull bounding footprint with naval prismatic block coefficient C_B = 0.75.
                // This prevents cabin window openings, gunports, and stairs from collapsing the buoyant volume.
                if (ry < lowerHullLayers && layerMinX[ry] <= layerMaxX[ry] && layerMinZ[ry] <= layerMaxZ[ry]) {
                    int layerSpanX = layerMaxX[ry] - layerMinX[ry] + 1;
                    int layerSpanZ = layerMaxZ[ry] - layerMinZ[ry] + 1;
                    double footprintDisplacement = layerSpanX * layerSpanZ * 0.75;
                    sliceVolumes[ry] = Math.max(countedVolume, footprintDisplacement);
                } else {
                    sliceVolumes[ry] = countedVolume;
                }
            }
        } else {
            // Fallback: estimate slice volume evenly
            double avgSlice = (double) blockCount / gridHeight;
            Arrays.fill(sliceVolumes, avgSlice);
        }

        double calculatedMass = 0.0;
        var massTracker = subLevel.getMassTracker();
        if (massTracker != null && !massTracker.isInvalid()) {
            calculatedMass = massTracker.getMass();
        }
        double minMass = blockCount > 0 ? Math.max(800.0, blockCount * 80.0) : 800.0;
        double totalMass = Math.max(calculatedMass, minMass);

        double targetDraft = calculateArchimedesDraft(sliceVolumes, totalMass, 1000.0);
        boolean isShallowDraft = totalStructureHeight <= 1.5;
        if (isShallowDraft) {
            targetDraft = Math.min(targetDraft, 0.15);
        } else if (targetDraft <= totalStructureHeight) {
            // For any buoyant wooden vessel (not overloaded/sinking), cap the equilibrium draft
            // to at most 1.25 blocks from the bottom of the keel (minBlockY).
            // With waterSurfaceY at 63.0, the keel rests at Y = 61.75, keeping the lower
            // walkable cabin deck (minBlockY + 2) at Y = 63.75 - well above Overworld ocean water (62.88).
            targetDraft = Math.min(targetDraft, 1.25);
        }

        return new SteeringCache(rudders, helms, vanes, capstans, taffrails, anchorState,
                sailAreaEfficiency, blockCount, sailFacing, sailCount,
                keelLocalPoint, minBlockY, maxBlockY, (float) targetDraft, totalEnclosedAirVolume,
                totalStructureHeight, hullLength, hullBeam);
    }

    /**
     * Samples the hull waterline around the keel: bottom-center plus four inset corners of the
     * sub-level's world bounding box.
     *
     * @return submerged sample count; zero means the ship is beached.
     */
    private static int countSubmergedHullSamples(ServerLevel level, ServerSubLevel subLevel,
                                                 double keelWorldY, float targetDraft, boolean isShallowDraft) {
        var bounds = subLevel.boundingBox();
        double waterlineY = isShallowDraft
                ? keelWorldY + 0.1
                : keelWorldY + targetDraft;
        double centerX = (bounds.minX() + bounds.maxX()) * 0.5;
        double centerZ = (bounds.minZ() + bounds.maxZ()) * 0.5;
        double insetX = (bounds.maxX() - bounds.minX()) * 0.25;
        double insetZ = (bounds.maxZ() - bounds.minZ()) * 0.25;

        int submerged = 0;
        if (isWaterAt(level, centerX, waterlineY, centerZ)) submerged++;
        if (isWaterAt(level, bounds.minX() + insetX, waterlineY, bounds.minZ() + insetZ)) submerged++;
        if (isWaterAt(level, bounds.maxX() - insetX, waterlineY, bounds.minZ() + insetZ)) submerged++;
        if (isWaterAt(level, bounds.minX() + insetX, waterlineY, bounds.maxZ() - insetZ)) submerged++;
        if (isWaterAt(level, bounds.maxX() - insetX, waterlineY, bounds.maxZ() - insetZ)) submerged++;
        return submerged;
    }

    /**
     * Finds the Y coordinate of the water surface under the hull's center:
     * the topmost water block (with non-water above) within the scan band near the keel.
     *
     * @return surface Y, or {@link Double#NEGATIVE_INFINITY} when no water
     *         surface is found near the hull (beached / airborne).
     */
    private static double waterSurfaceY(ServerLevel level, ServerSubLevel subLevel, double keelWorldY, double hullHeight) {
        var bounds = subLevel.boundingBox();
        double centerX = (bounds.minX() + bounds.maxX()) * 0.5;
        double centerZ = (bounds.minZ() + bounds.maxZ()) * 0.5;
        int lo = (int) Math.floor(keelWorldY) - 3;
        int hi = (int) Math.ceil(keelWorldY + Math.min(hullHeight, 10.0)) + 2;
        for (int y = hi; y >= lo; y--) {
            if (isWaterAt(level, centerX, y, centerZ)
                    && !isWaterAt(level, centerX, y + 1, centerZ)) {
                return y + 1.0;
            }
        }
        return Double.NEGATIVE_INFINITY;
    }

    /**
     * Vertical spring lifting the hull to its ride-height equilibrium: the
     * keel settles at {@code targetKeelY = waterSurfaceY - targetDraft},
     * drafted according to hollow-hull Archimedes displacement.
     * Incorporates a strict calm-weather deadzone so motionless docked vessels have zero vertical bobbing.
     */
    private static void applyRideHeightSpring(RigidBodyHandle body, double waterSurfaceY,
                                              double keelWorldY, float targetDraft, double dt,
                                              boolean isCalmWeather) {
        float targetKeelY = (float) (waterSurfaceY - targetDraft);
        float currentKeelY = (float) keelWorldY;
        double depth = targetKeelY - currentKeelY;
        Vector3d vel = new Vector3d(body.getLinearVelocity());

        // Hydrodynamic splashdown impact cushioning: if falling or plunging into water at high downward speed,
        // cushion the plunge so vessels smoothly settle without bottoming out or punching into the seabed.
        if (vel.y < -4.0) {
            double cushionedVy = Math.max(-4.0, vel.y * Math.exp(-12.0 * dt));
            body.addLinearAndAngularVelocity(new Vector3d(0.0, cushionedVy - vel.y, 0.0), new Vector3d());
            vel.y = cushionedVy;
        }

        // In calm water near equilibrium (depth < 0.05) and low vertical velocity (vy < 0.08),
        // smoothly damp vy (vy *= 0.5) without applying oscillating impulses so docked ships sit 100% motionless!
        if (isCalmWeather && Math.abs(depth) < 0.05 && Math.abs(vel.y) < 0.08) {
            double dampedVy = vel.y * 0.5;
            body.addLinearAndAngularVelocity(new Vector3d(0.0, dampedVy - vel.y, 0.0), new Vector3d());
            return;
        }

        // Softened spring response (0.5x RIDE_SPRING) and heavy velocity damping (exp(-6.0*dt))
        // prevents rapid pogo-stick bobbing in waves while smoothly restoring equilibrium
        if (depth > 0.0) {
            // Below equilibrium: lift up (capped at 2 blocks to prevent catapult)
            double liftDv = Math.min(depth, 2.0) * (RIDE_SPRING * 0.5) * dt;
            double vy = (vel.y + liftDv) * Math.exp(-6.0 * dt);
            body.addLinearAndAngularVelocity(new Vector3d(0.0, vy - vel.y, 0.0),
                    new Vector3d());
        } else if (depth < -0.05) {
            // Above equilibrium: pull back down with soft constant and heavy damping
            double pullDv = Math.max(depth, -1.0) * (RIDE_SPRING * 0.25) * dt;
            double vy = (vel.y + pullDv) * Math.exp(-6.0 * dt);
            body.addLinearAndAngularVelocity(new Vector3d(0.0, vy - vel.y, 0.0),
                    new Vector3d());
        }
    }

    @FunctionalInterface
    public interface WaveHeightHook {
        double getWaveHeight(Level level, double x, double z, double time);
    }

    public static volatile WaveHeightHook WAVE_HEIGHT_HOOK = GerstnerWaveEngine::getWaveHeightOffset;

    public static void setWaveHeightHook(WaveHeightHook hook) {
        WAVE_HEIGHT_HOOK = hook != null ? hook : GerstnerWaveEngine::getWaveHeightOffset;
    }

    /**
     * Samples the water surface height taking into account any active wave hook offset.
     */
    public static double sampleWaveSurfaceY(Level level, double x, double z, double defaultWaterSurfaceY) {
        if (defaultWaterSurfaceY <= Double.NEGATIVE_INFINITY) {
            return Double.NEGATIVE_INFINITY;
        }
        double time = level != null ? (double) level.getGameTime() : 0.0;
        double waveOffset = WAVE_HEIGHT_HOOK.getWaveHeight(level, x, z, time);
        return defaultWaterSurfaceY + waveOffset;
    }

    /**
     * Helper to test if a given world point is submerged in water, respecting wave displacements.
     */
    public static boolean isPointSubmerged(Level level, double x, double y, double z) {
        if (level == null) {
            return false;
        }
        double time = (double) level.getGameTime();
        double waveOffset = WAVE_HEIGHT_HOOK.getWaveHeight(level, x, z, time);
        BlockPos probe = BlockPos.containing(x, y - waveOffset, z);
        // A hull sample point can lie in an unloaded chunk; Level#getFluidState would load / generate it synchronously
        // from inside the physics step.
        if (!level.hasChunkAt(probe)) {
            return false;
        }
        return level.getFluidState(probe).is(FluidTags.WATER);
    }

    private static boolean isWaterAt(ServerLevel level, double x, double y, double z) {
        return isPointSubmerged(level, x, y, z);
    }

    /** Heavy ground friction for beached hulls (mass-independent velocity damping). */
    private static void applyGroundFriction(RigidBodyHandle body, double dt) {
        Vector3d vel = new Vector3d(body.getLinearVelocity());
        Vector3d angVel = new Vector3d(body.getAngularVelocity());
        double keep = Math.exp(-8.0 * dt);
        body.addLinearAndAngularVelocity(
                new Vector3d(vel.x * (keep - 1.0), 0.0, vel.z * (keep - 1.0)),
                angVel.mul(keep - 1.0, new Vector3d()));
    }

    /**
     * Simulates a single physics tick of turning dynamics and momentum retention
     * for unit testing and numerical verification.
     */
    public static Vector3d updateTurningMomentum(
            Vector3d vel,
            Vector3d prevHeading,
            Vector3d currentHeading,
            float activeHelmAngle,
            double omegaY,
            double dt,
            double massScale,
            double thrustMag,
            boolean anchored
    ) {
        if (anchored) {
            double keep = Math.exp(-ANCHOR_DAMPING_RATE * dt);
            return new Vector3d(vel.x * keep, vel.y, vel.z * keep);
        }

        Vector3d resultVel = new Vector3d(vel);
        double initialFwdSpeed = resultVel.x * currentHeading.x + resultVel.z * currentHeading.z;

        // 1. Keel Hydrodynamic Lift / Momentum Redirection (80% of dYaw)
        if (!anchored && prevHeading != null && Math.abs(initialFwdSpeed) > 0.05) {
            Vector3d cross = new Vector3d(prevHeading).cross(currentHeading);
            double dYaw = Math.atan2(cross.y, prevHeading.dot(currentHeading));
            if (Math.abs(dYaw) > 1.0e-5) {
                Vector3d horizontalVel = new Vector3d(resultVel.x, 0.0, resultVel.z);
                horizontalVel.rotateY(dYaw * 0.80);
                resultVel.x = horizontalVel.x;
                resultVel.z = horizontalVel.z;
            }
        }

        // 2. Lateral Keel Resistance / Sway Damping (reduced by 70% during active turns)
        Vector3d localRight = new Vector3d(currentHeading).cross(0.0, 1.0, 0.0);
        if (localRight.lengthSquared() < 1.0e-4) {
            localRight.set(1.0, 0.0, 0.0);
        } else {
            localRight.normalize();
        }
        double vLateral = resultVel.dot(localRight);
        if (Math.abs(vLateral) > 1.0e-9) {
            double lateralDamping = KEEL_LATERAL_DAMPING;
            if (Math.abs(activeHelmAngle) >= 0.5f || Math.abs(omegaY) > 0.01) {
                lateralDamping *= 0.30;
            }
            Vector3d swayImpulse = new Vector3d(localRight).mul(-lateralDamping * vLateral * dt * massScale);
            resultVel.add(swayImpulse);
        }

        // 3. Low-Speed Stopping Damping (Karnopp model): only when nearly stationary
        double planarSpeed = Math.sqrt(resultVel.x * resultVel.x + resultVel.z * resultVel.z);
        if (thrustMag <= 1.0 && planarSpeed <= 0.05) {
            double keep = Math.exp(-2.0 * dt);
            resultVel.x *= keep;
            resultVel.z *= keep;
            if (planarSpeed < 0.02) {
                resultVel.x = 0.0;
                resultVel.z = 0.0;
            }
        }

        // 4. Momentum Inertia Guard: speed never drops by > 2.5% per tick (works in headway and sternway)
        if (Math.abs(initialFwdSpeed) > 0.05) {
            double currentFwdSpeed = resultVel.x * currentHeading.x + resultVel.z * currentHeading.z;
            double deltaFwd = currentFwdSpeed - initialFwdSpeed;
            double maxLoss = 0.025 * Math.abs(initialFwdSpeed) * (dt / 0.05);
            if (initialFwdSpeed > 0.0 && deltaFwd < -maxLoss) {
                double clampedFwdSpeed = initialFwdSpeed - maxLoss;
                double restore = clampedFwdSpeed - currentFwdSpeed;
                resultVel.add(new Vector3d(currentHeading).mul(restore));
            } else if (initialFwdSpeed < 0.0 && deltaFwd > maxLoss) {
                double clampedFwdSpeed = initialFwdSpeed + maxLoss;
                double restore = clampedFwdSpeed - currentFwdSpeed;
                resultVel.add(new Vector3d(currentHeading).mul(restore));
            }
        }

        return resultVel;
    }

    @SubscribeEvent
    public static void onLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            DIMENSION_WIND_CACHE.remove(serverLevel.dimension());
            DIMENSION_WIND_VEC_CACHE.remove(serverLevel.dimension());
            // FIX (Audit 106): Only evict sub-level entries belonging to the
            // unloading dimension. The old code called .clear() which wiped
            // ALL cached physics state across ALL dimensions whenever a SINGLE
            // dimension unloaded (e.g. entering/leaving the Nether). This
            // destroyed velocity, heading, and steering caches for vessels
            // sailing in the Overworld when the player briefly visited the End.
            SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
            if (container instanceof ServerSubLevelContainer serverContainer) {
                Set<UUID> toRemove = new HashSet<>();
                for (ServerSubLevel sl : serverContainer.getAllSubLevels()) {
                    toRemove.add(sl.getUniqueId());
                }
                STEERING_CACHE.keySet().removeAll(toRemove);
                STEERING_CACHE_STEP.keySet().removeAll(toRemove);
                CREAK_COOLDOWN.keySet().removeAll(toRemove);
                HELM_ANGLE_CACHE.keySet().removeAll(toRemove);
                VESSEL_VELOCITY_CACHE.keySet().removeAll(toRemove);
                FORWARD_SPEED_CACHE.keySet().removeAll(toRemove);
                PREV_HEADING_CACHE.keySet().removeAll(toRemove);
                PHYS_DEBUG_TICK.keySet().removeAll(toRemove);
                LAST_BILGE_PURGE_TICK.keySet().removeAll(toRemove);
            } else {
                // Fallback for edge case where container is unavailable
                STEERING_CACHE.clear();
                STEERING_CACHE_STEP.clear();
                CREAK_COOLDOWN.clear();
                HELM_ANGLE_CACHE.clear();
                VESSEL_VELOCITY_CACHE.clear();
                FORWARD_SPEED_CACHE.clear();
                PREV_HEADING_CACHE.clear();
                PHYS_DEBUG_TICK.clear();
                LAST_BILGE_PURGE_TICK.clear();
                DIMENSION_WIND_VEC_CACHE.clear();
            }
        }
    }

    private static final List<ResourceLocation> SERENE_RECIPES = List.of(
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "canvas_sail"),
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "rudder"),
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "helm"),
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "wind_vane"),
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "capstan"),
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "taffrail_log"),
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "shipwright_spectacles")
    );

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            serverPlayer.awardRecipesByKey(SERENE_RECIPES);
        }
    }
}

