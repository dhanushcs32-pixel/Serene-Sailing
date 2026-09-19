package com.serenesailing.event;

import com.serenesailing.SereneSailing;
import com.serenesailing.block.CapstanBlock;
import com.serenesailing.block.RudderBlock;
import com.serenesailing.block.SailBlock;
import com.serenesailing.config.SailingConfig;
import com.serenesailing.entity.CapstanBlockEntity;
import com.serenesailing.entity.HelmBlockEntity;
import com.serenesailing.entity.RudderBlockEntity;
import com.serenesailing.entity.WindVaneBlockEntity;
import com.serenesailing.physics.SailAerodynamics;
import com.serenesailing.registry.ModBlocks;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import weather2.util.WindReader;

import com.serenesailing.registry.ModSounds;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives vessels assembled through the Aeronautics Physics Assembler / Sable,
 * with every stability improvement retained: flotation gate, Weather2
 * apparent-wind thrust scaled by reefed sail area, dual linear+quadratic
 * hydrodynamic drag, pure local-frame yaw steering with roll/pitch
 * stabilization, and capstan anchor lock.
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID)
public class ServerPhysicsHandler {

    private static long stepCounter = 0L;

    // --- Runaway guardrails ---
    private static final double MAX_AERO_FORCE_N = 300.0;
    private static final double MAX_IMPULSE_PER_SUBSTEP = 150.0;
    /** Yaw impulse guard - sized so the steering torque stays proportional to
     *  v_fwd across all playable speeds (800 * v * sin(30 deg) * dt stays
     *  below this up to ~9 m/s), keeping the turn rate continuous rather than
     *  saturating into a fixed-radius circle. */
    private static final double MAX_YAW_IMPULSE_PER_SUBSTEP = 60.0;
    private static final double ROLL_STABILIZE_COEFF = 600.0;
    private static final double PITCH_STABILIZE_COEFF = 600.0;
    /** Anchor lock exponential rate (keep = e^(-rate*dt)). */
    private static final double ANCHOR_DAMPING_RATE = 20.0;
    /** Buoyancy ride-height bias [blocks]: equilibrium puts the hull bottom
     *  this far ABOVE the water surface so open cockpits stay dry. */
    private static final double RIDE_HEIGHT_BIAS = 0.7;
    /** Ride-height spring response [1/s^2 per block of submerged depth]. */
    private static final double RIDE_SPRING = 10.0;
    /** Minimum steering speed magnitude [m/s]: rudder water flow baseline so
     *  a deflected helm keeps turning authority even when the ship has fully
     *  stalled into the wind (v_fwd ~ 0). Sign of v_fwd is preserved, so
     *  sternway (backing) reverses the yaw torque like a real vessel. */
    private static final double MIN_STEER_SPEED = 0.4;
    /** Lateral keel resistance [N*s/m]: heavy damping of the hull's local X
     *  (sideways) velocity so the vessel cuts forward along local -Z instead
     *  of crabbing sideways and stalling the rudder. */
    private static final double KEEL_LATERAL_DAMPING = 400.0;

    /** Cached rudder: live block entity, application point, and facing. */
    private record RudderInfo(RudderBlockEntity blockEntity, Vector3d localPoint, Direction facing) {}

    /** Per-sub-level steering scan: rudders, helm stations, vanes, capstans, sails. */
    private record SteeringCache(List<RudderInfo> rudders, List<HelmBlockEntity> helms,
                                 List<WindVaneBlockEntity> vanes,
                                 List<CapstanBlockEntity> capstans,
                                 boolean anchorState,
                                 double sailAreaEfficiency) {}

    private static final Map<UUID, SteeringCache> STEERING_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STEERING_CACHE_STEP = new ConcurrentHashMap<>();
    /** Per-sublevel cooldown stamp for hull creak sounds. */
    private static final Map<UUID, Long> CREAK_COOLDOWN = new ConcurrentHashMap<>();
    private static final long STEERING_RESCAN_STEPS = 100L;

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
        if (WindReader.getWeatherManagerFor(serverLevel) == null) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null && WindReader.getWeatherManagerFor(overworld) != null) {
                return overworld;
            }
        }
        return serverLevel;
    }

    @SubscribeEvent
    public static void onPrePhysicsTick(ForgeSablePrePhysicsTickEvent event) {
        stepCounter++;
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

        for (ServerSubLevel subLevel : serverContainer.getAllSubLevels()) {
            UUID id = subLevel.getUniqueId();
            if (subLevel.isRemoved()) {
                STEERING_CACHE.remove(id);
                STEERING_CACHE_STEP.remove(id);
                CREAK_COOLDOWN.remove(id);
                continue;
            }
            try {
                applyShipPhysics(subLevel, level, dt);
            } catch (Exception ex) {
                SereneSailing.LOGGER.error("[SereneSailing] sailing physics failed for sub-level {}", id, ex);
            }
        }
    }

    private static void applyShipPhysics(ServerSubLevel subLevel, ServerLevel level, double dt) {
        RigidBodyHandle body = RigidBodyHandle.of(subLevel);
        if (!body.isValid()) {
            return;
        }

        Pose3dc pose = subLevel.logicalPose();
        Vector3dc worldPos = pose.position();

        // ---- Weather2 wind at the ship's WORLD position -------------------
        Vec3 samplePos = new Vec3(worldPos.x(), worldPos.y(), worldPos.z());
        ServerLevel weatherLevel = resolveWeatherQueryLevel(level);
        float rawWindAngle = weatherLevel != null
                ? WindReader.getWindAngle(weatherLevel, samplePos) : 0.0F;
        float rawWindSpeed = weatherLevel != null
                ? WindReader.getWindSpeed(weatherLevel, BlockPos.containing(samplePos)) : 0.0F;
        double effectiveWindSpeed = Math.max(SailingConfig.MIN_WIND_SPEED.get(), rawWindSpeed * 20.0);
        double windRad = Math.toRadians(rawWindAngle);
        Vector3d trueWindWorld = new Vector3d(-Math.sin(windRad), 0.0, Math.cos(windRad))
                .mul(effectiveWindSpeed);

        // ---- Ship state ----------------------------------------------------
        Vector3d shipVelWorld = new Vector3d(body.getLinearVelocity());
        Quaterniondc orientation = pose.orientation();
        Vector3d forwardWorld = new Vector3d(0.0, 0.0, -1.0).rotate(orientation);
        forwardWorld.y = 0.0;
        if (forwardWorld.lengthSquared() < 1.0e-4) {
            return;
        }
        forwardWorld.normalize();
        Vector3d starboardWorld = new Vector3d(forwardWorld).cross(0.0, 1.0, 0.0).normalize();

        SteeringCache steering = getSteeringCache(subLevel);

        // ---- Buoyancy / ride height -----------------------------------------
        // Vertical spring that lifts the hull so its resting waterline sits
        // well below the top deck: equilibrium puts the hull bottom ~0.7
        // blocks ABOVE the water surface, keeping open cockpits dry (no water
        // clipping into the hull).
        double waterSurfaceY = waterSurfaceY(level, subLevel);
        double bottomY = subLevel.boundingBox().minY();
        boolean afloat = waterSurfaceY > Double.NEGATIVE_INFINITY
                && bottomY < waterSurfaceY + 1.5; // hull still within the water band
        if (afloat) {
            applyRideHeightSpring(body, waterSurfaceY, bottomY, dt);
        }

        // ---- Flotation gate: beached ships get ground friction only --------
        if (!afloat && countSubmergedHullSamples(level, subLevel) == 0) {
            applyGroundFriction(body, dt);
            updateWindVanes(steering.vanes(), forwardWorld, trueWindWorld, shipVelWorld);
            return;
        }

        // ---- Anchor state: decided BEFORE any thrust or steering -------------
        boolean anchored = isAnchored(steering);

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
        if (!anchored && !calmWind && !noSailArea) {
            thrustWorld.set(SailAerodynamics.calculateSailForce(
                    trueWindWorld, shipVelWorld, forwardWorld, starboardWorld, 1.0));
            thrustWorld.mul(SailingConfig.SAIL_THRUST_MULTIPLIER.get()
                    * steering.sailAreaEfficiency());
            thrustMag = thrustWorld.length();
            if (thrustMag > MAX_AERO_FORCE_N) {
                thrustWorld.normalize().mul(MAX_AERO_FORCE_N);
                thrustMag = MAX_AERO_FORCE_N;
            }
        }
        if (thrustMag > 1.0e-9) {
            Vector3d thrustLocalImpulse = new Vector3d();
            pose.transformNormalInverse(thrustWorld, thrustLocalImpulse).mul(dt);
            if (thrustLocalImpulse.length() > MAX_IMPULSE_PER_SUBSTEP) {
                thrustLocalImpulse.normalize().mul(MAX_IMPULSE_PER_SUBSTEP);
            }
            body.applyLinearAndAngularImpulse(thrustLocalImpulse, new Vector3d(), true);
        }

        // ---- Drag / anchor lock ---------------------------------------------
        Vector3d omegaWorld = new Vector3d(body.getAngularVelocity());
        double planarSpeedSq = shipVelWorld.x * shipVelWorld.x + shipVelWorld.z * shipVelWorld.z;
        double planarSpeed = Math.sqrt(planarSpeedSq);

        if (anchored) {
            // Capstan anchor lock: exponential planar freeze + full yaw stop.
            // Thrust and steering torque are already bypassed above, so the
            // only forces here are damping ones.
            double keep = Math.exp(-ANCHOR_DAMPING_RATE * dt);
            Vector3d planarDelta = new Vector3d(
                    shipVelWorld.x * (keep - 1.0), 0.0, shipVelWorld.z * (keep - 1.0));
            if (planarSpeed < 0.02) {
                // Nearly stopped - snap completely to rest.
                planarDelta.set(-shipVelWorld.x, 0.0, -shipVelWorld.z);
            }
            double omegaY = body.getAngularVelocity().y();
            body.addLinearAndAngularVelocity(planarDelta,
                    new Vector3d(0.0, -omegaY, 0.0));
        } else if (planarSpeed > 1.0e-9 || omegaWorld.lengthSquared() > 1.0e-12) {
            applyHydrodynamicForces(body, pose, shipVelWorld, omegaWorld, planarSpeed,
                    thrustMag <= 1.0e-9, dt);
        }

        // ---- Stabilized pure-yaw steering (tau_y = 0 while anchored) --------
        if (!anchored) {
            applySteeringAndStabilization(subLevel, body, pose, level, shipVelWorld, omegaWorld, dt);
        }

        // ---- Nautical ambience: hull creaks under heavy stress ---------------
        // Fires when wave roll or yaw rate exceeds a threshold, with a
        // per-sublevel cooldown so the creak reads as occasional groaning
        // rather than a constant loop.
        if (!anchored && (Math.abs(omegaWorld.x) > 0.05 || Math.abs(omegaWorld.y) > 0.05)) {
            Long lastCreak = CREAK_COOLDOWN.get(subLevel.getUniqueId());
            if (lastCreak == null || stepCounter - lastCreak >= 60L) {
                CREAK_COOLDOWN.put(subLevel.getUniqueId(), stepCounter);
                level.playSound(null,
                        BlockPos.containing(worldPos.x(), worldPos.y(), worldPos.z()),
                        ModSounds.SHIP_CREAK.get(), SoundSource.AMBIENT,
                        0.6f, 0.8f + level.random.nextFloat() * 0.4f);
            }
        }

        // ---- Rest snapping -----------------------------------------------------
        if (!anchored && thrustMag <= 1.0e-9 && planarSpeed < 0.05) {
            double keep = Math.exp(-12.0 * dt);
            body.addLinearAndAngularVelocity(
                    new Vector3d(shipVelWorld.x * (keep - 1.0), 0.0,
                            shipVelWorld.z * (keep - 1.0)),
                    omegaWorld.mul(keep - 1.0, new Vector3d()));
        }

        // ---- Wind vanes ----------------------------------------------------------
        updateWindVanes(steering.vanes(), forwardWorld, trueWindWorld, shipVelWorld);

        if (stepCounter % 20L == 0L && thrustMag > 1.0e-9) {
            SereneSailing.LOGGER.info(String.format(
                    "[SereneSailing] %s | wind %.2f m/s @ %.0f deg | thrust %.0f N | speed %.2f m/s",
                    subLevel.getName(), effectiveWindSpeed, rawWindAngle,
                    thrustMag, shipVelWorld.length()));

            // Wind billow while the sails generate near-maximum lift.
            if (!anchored && thrustMag >= MAX_AERO_FORCE_N * 0.9 && stepCounter % 100L == 0L) {
                level.playSound(null,
                        BlockPos.containing(worldPos.x(), worldPos.y(), worldPos.z()),
                        ModSounds.WIND_BILLOW.get(), SoundSource.AMBIENT, 0.7f, 1.0f);
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
                                                double dt) {
        // Dual linear + quadratic planar drag opposing world-frame motion.
        if (planarSpeed > 1.0e-9) {
            double dragForceMag = SailingConfig.LINEAR_DRAG_MU.get() * planarSpeed
                    + SailingConfig.QUADRATIC_DRAG_MU.get() * planarSpeed * planarSpeed;
            Vector3d dragForceWorld = new Vector3d(
                    shipVelWorld.x / planarSpeed, 0.0, shipVelWorld.z / planarSpeed)
                    .mul(-dragForceMag);
            Vector3d dragImpulseLocal =
                    pose.transformNormalInverse(dragForceWorld, new Vector3d()).mul(dt);
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

        // Lateral keel resistance: v_lateral is the hull's local X velocity
        // (getLinearVelocity() is world-frame, so transform it into the body
        // frame first). Applied directly as a local-frame linear impulse.
        Vector3d localVel = pose.transformNormalInverse(shipVelWorld, new Vector3d());
        double vLateral = localVel.x;
        if (Math.abs(vLateral) > 1.0e-9) {
            body.applyLinearAndAngularImpulse(
                    new Vector3d(-KEEL_LATERAL_DAMPING * vLateral * dt, 0.0, 0.0),
                    new Vector3d(), true);
        }

        // ---- Zero-thrust glide-to-stop ---------------------------------------
        // With no sail force present, water resistance must actively bring the
        // hull completely to rest instead of letting it glide forever on the
        // residual quadratic-drag tail. Exponential planar decay, then a hard
        // snap below walking pace.
        if (zeroThrust) {
            double keep = Math.exp(-2.0 * dt);
            Vector3d planarDelta = new Vector3d(
                    shipVelWorld.x * (keep - 1.0), 0.0, shipVelWorld.z * (keep - 1.0));
            if (planarSpeed < 0.02) {
                planarDelta.set(-shipVelWorld.x, 0.0, -shipVelWorld.z);
            }
            body.addLinearAndAngularVelocity(planarDelta, new Vector3d());

            // Rotational yaw damping: a stationary (or centered-helm) ship
            // generates no rudder lift - there is no water flowing past the
            // foil - so leftover yaw angular velocity is damped hard.
            double omegaY = body.getAngularVelocity().y();
            body.addLinearAndAngularVelocity(new Vector3d(),
                    new Vector3d(0.0, -omegaY * (1.0 - Math.exp(-3.0 * dt)), 0.0));
        }
    }

    /**
     * Aeronautics-style stabilized turning: pure local-frame yaw torque
     * ({@code tau_y = -c_steer * v_fwd * sin(theta)}, scaled by submerged
     * rudder count) plus active roll/pitch rate damping. No offset point
     * forces, so no parasitic roll/pitch torques.
     */
    private static void applySteeringAndStabilization(ServerSubLevel subLevel,
                                                      RigidBodyHandle body,
                                                      Pose3dc pose,
                                                      ServerLevel level,
                                                      Vector3d shipVelWorld,
                                                      Vector3d omegaWorld,
                                                      double dt) {
        List<RudderInfo> rudders = getRudders(subLevel);

        // Active upright stabilization: damp local roll (x) / pitch (z) rates;
        // yaw (y) is owned by the steering torque below.
        Vector3d omegaLocal = pose.transformNormalInverse(omegaWorld, new Vector3d());
        double rollImpulse = -ROLL_STABILIZE_COEFF * omegaLocal.x * dt;
        double pitchImpulse = -PITCH_STABILIZE_COEFF * omegaLocal.z * dt;

        double yawTorque = 0.0;
        float unifiedSteeringAngle = getUnifiedSteeringAngle(subLevel, rudders);
        if (!rudders.isEmpty() && Math.abs(unifiedSteeringAngle) >= 0.5f) {
            // Local Body Frame velocity extraction: getLinearVelocity() is in
            // WORLD coordinates, so it must be rotated by the inverse pose
            // orientation before the forward component is read. Local -Z is
            // the bow, so v_fwd = -localVel.z stays positive no matter which
            // world direction (N/S/E/W) the ship is currently facing.
            Vector3d localVel = pose.transformNormalInverse(shipVelWorld, new Vector3d());
            localVel.y = 0.0;
            double vFwd = -localVel.z;

            // Effective steering speed: preserve the sign (sternway reverses
            // the rudder's yaw torque naturally) but guarantee a 0.4 m/s
            // baseline so a deflected helm retains turning authority even when
            // the ship is stationary or stalled in irons. This lets players
            // rotate the bow away from the wind and back out of a dead stop.
            double vSteer = vFwd >= 0.0
                    ? Math.max(vFwd, MIN_STEER_SPEED)
                    : Math.min(vFwd, -MIN_STEER_SPEED);

            int submergedCount = 0;
            double facingSignSum = 0.0;
            for (RudderInfo rudder : rudders) {
                Vector3d rudderWorld = pose.transformPosition(
                        new Vector3d(rudder.localPoint()), new Vector3d());
                if (!level.getFluidState(BlockPos.containing(
                        rudderWorld.x, rudderWorld.y, rudderWorld.z)).is(FluidTags.WATER)) {
                    continue;
                }
                submergedCount++;
                // The rudder's facing is FIXED in the ship's local frame (it
                // comes from the plot blockstate). Compare it against the
                // constant local aft axis (0, 0, 1) so the mounting sign
                // never changes. Comparing against the ship's rotating
                // WORLD-frame aft flipped the torque sign at +/-90 degrees,
                // locking vessels into quarter turns and preventing 180
                // degree U-turns.
                Direction facing = rudder.facing();
                facingSignSum += facing.getStepZ() >= 0 ? 1.0 : -1.0;
            }

            if (submergedCount > 0 && facingSignSum != 0.0) {
                double thetaRad = Math.toRadians(unifiedSteeringAngle);
                yawTorque = -SailingConfig.STEER_TORQUE_COEFF.get() * vSteer
                        * Math.sin(thetaRad) * facingSignSum;
            }
        }

        double yawImpulse = Math.max(-MAX_YAW_IMPULSE_PER_SUBSTEP,
                Math.min(MAX_YAW_IMPULSE_PER_SUBSTEP, yawTorque * dt));
        body.applyLinearAndAngularImpulse(new Vector3d(),
                new Vector3d(rollImpulse, yawImpulse, pitchImpulse), true);
    }

    /** True when any capstan on this vessel has its anchor dropped. Checks the
     *  live block entity flag AND the placed {@link CapstanBlock#ANCHORED}
     *  blockstate, so a stale/desynced block entity cannot keep a moored ship
     *  under sail. */
    private static boolean isAnchored(SteeringCache steering) {
        if (steering.anchorState()) {
            return true;
        }
        for (CapstanBlockEntity capstan : steering.capstans()) {
            if (capstan.isAnchored()) {
                return true;
            }
        }
        return false;
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
        if (cached != null && !cached.rudders().isEmpty() && stamp != null
                && (stepCounter - stamp) < STEERING_RESCAN_STEPS) {
            return cached;
        }
        SteeringCache scanned = scanSteering(subLevel);
        STEERING_CACHE.put(id, scanned);
        STEERING_CACHE_STEP.put(id, stepCounter);
        return scanned;
    }

    private static List<RudderInfo> getRudders(ServerSubLevel subLevel) {
        return getSteeringCache(subLevel).rudders();
    }

    /** Helm angle if a wheel is present; otherwise the first rudder's own angle. */
    private static float getUnifiedSteeringAngle(ServerSubLevel subLevel, List<RudderInfo> rudders) {
        List<HelmBlockEntity> helms = getSteeringCache(subLevel).helms();
        if (!helms.isEmpty()) {
            return helms.get(0).getSteeringAngle();
        }
        return rudders.isEmpty() ? 0.0f : rudders.get(0).blockEntity().getSteeringAngle();
    }

    /**
     * Scans plot chunks for rudders (with facings), helm stations, wind vanes,
     * capstans, and aggregates sail reefing efficiency.
     */
    private static SteeringCache scanSteering(ServerSubLevel subLevel) {
        List<RudderInfo> rudders = new ArrayList<>();
        List<HelmBlockEntity> helms = new ArrayList<>();
        List<WindVaneBlockEntity> vanes = new ArrayList<>();
        List<CapstanBlockEntity> capstans = new ArrayList<>();
        boolean anchorState = false;
        double sailAreaEfficiency = 0.0;
        var plot = subLevel.getPlot();
        if (plot != null) {
            Block sailBlock = ModBlocks.CANVAS_SAIL.get();
            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk == null) {
                    continue;
                }
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos p = entry.getKey();
                    if (entry.getValue() instanceof RudderBlockEntity rudder) {
                        BlockState rs = entry.getValue().getBlockState();
                        Direction facing = rs.hasProperty(RudderBlock.FACING)
                                ? rs.getValue(RudderBlock.FACING) : Direction.NORTH;
                        rudders.add(new RudderInfo(rudder,
                                new Vector3d(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5),
                                facing));
                    } else if (entry.getValue() instanceof HelmBlockEntity helm) {
                        helms.add(helm);
                    } else if (entry.getValue() instanceof WindVaneBlockEntity vane) {
                        vanes.add(vane);
                    } else if (entry.getValue() instanceof CapstanBlockEntity capstan) {
                        capstans.add(capstan);
                        // Cross-check the placed blockstate: if the ANCHORED
                        // property is set, the vessel is moored even if the
                        // block entity's cached flag has gone stale.
                        BlockState cs = capstan.getBlockState();
                        if (cs.getBlock() instanceof CapstanBlock
                                && cs.hasProperty(CapstanBlock.ANCHORED)
                                && cs.getValue(CapstanBlock.ANCHORED)) {
                            anchorState = true;
                        }
                    }
                }

                for (LevelChunkSection section : chunk.getSections()) {
                    if (section == null || section.hasOnlyAir()
                            || !section.getStates().maybeHas(s -> s.getBlock() == sailBlock)) {
                        continue;
                    }
                    for (int sy = 0; sy < 16; sy++) {
                        for (int sz = 0; sz < 16; sz++) {
                            for (int sx = 0; sx < 16; sx++) {
                                BlockState s = section.getBlockState(sx, sy, sz);
                                if (s.getBlock() == sailBlock) {
                                    sailAreaEfficiency += s.getValue(SailBlock.SAIL_STATE) * 0.5;
                                }
                            }
                        }
                    }
                }
            }
        }
        return new SteeringCache(rudders, helms, vanes, capstans, anchorState, sailAreaEfficiency);
    }

    /**
     * Samples the hull waterline: bottom-center plus four inset corners of the
     * sub-level's world bounding box.
     *
     * @return submerged sample count; zero means the ship is beached.
     */
    private static int countSubmergedHullSamples(ServerLevel level, ServerSubLevel subLevel) {
        var bounds = subLevel.boundingBox();
        double waterlineY = bounds.minY() + 0.25;
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
     * the topmost water block (with non-water above) within the scan band.
     *
     * @return surface Y, or {@link Double#NEGATIVE_INFINITY} when no water
     *         surface is found near the hull (beached / airborne).
     */
    private static double waterSurfaceY(ServerLevel level, ServerSubLevel subLevel) {
        var bounds = subLevel.boundingBox();
        double centerX = (bounds.minX() + bounds.maxX()) * 0.5;
        double centerZ = (bounds.minZ() + bounds.maxZ()) * 0.5;
        int lo = (int) Math.floor(bounds.minY()) - 2;
        int hi = (int) Math.ceil(bounds.minY()) + 6;
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
     * hull bottom settles ~{@link #RIDE_HEIGHT_BIAS} blocks above the water
     * surface. Damped so it settles without porpoising; capped per step so a
     * deep-dropped spawn does not catapult the ship.
     */
    private static void applyRideHeightSpring(RigidBodyHandle body, double waterSurfaceY,
                                              double bottomY, double dt) {
        double depth = (waterSurfaceY + RIDE_HEIGHT_BIAS) - bottomY;
        if (depth <= 0.0) {
            // Already riding at or above the equilibrium height.
            return;
        }
        Vector3d vel = new Vector3d(body.getLinearVelocity());
        double liftDv = Math.min(depth, 3.0) * RIDE_SPRING * dt;
        // Damp the resulting vertical velocity so the spring settles.
        double vy = (vel.y + liftDv) * Math.exp(-4.0 * dt);
        body.addLinearAndAngularVelocity(new Vector3d(0.0, vy - vel.y, 0.0),
                new Vector3d());
    }

    private static boolean isWaterAt(ServerLevel level, double x, double y, double z) {
        return level.getFluidState(BlockPos.containing(x, y, z)).is(FluidTags.WATER);
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
}

