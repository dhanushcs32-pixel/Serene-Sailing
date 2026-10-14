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
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import weather2.util.WindReader;

import com.serenesailing.registry.ModSounds;
import net.minecraft.sounds.SoundSource;

import org.slf4j.Logger;
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

    private static final Logger LOGGER = SereneSailing.LOGGER;
    private static final Map<UUID, Long> PHYS_DEBUG_TICK = new ConcurrentHashMap<>();
    private static long stepCounter = 0L;

    // --- Runaway guardrails & Fossen / MMG Hydrodynamics ---
    private static final double FORCE_PER_FULL_SAIL_N = 450.0;
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

    /** Per-sub-level steering scan: rudders, helm stations, vanes, capstans, taffrails, sails, blockCount, sailFacing, sailCount. */
    private record SteeringCache(List<RudderInfo> rudders, List<HelmBlockEntity> helms,
                                 List<WindVaneBlockEntity> vanes,
                                 List<CapstanBlockEntity> capstans,
                                 List<TaffrailLogBlockEntity> taffrails,
                                 boolean anchorState,
                                 double sailAreaEfficiency,
                                 int blockCount,
                                 Direction sailFacing,
                                 int sailCount) {}

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

    public static void setHelmAngle(UUID subLevelId, float angle) {
        HELM_ANGLE_CACHE.put(subLevelId, angle);
    }

    public static float getHelmAngle(UUID subLevelId) {
        return HELM_ANGLE_CACHE.getOrDefault(subLevelId, 0.0f);
    }

    public static Vector3d getVesselVelocity(UUID subLevelId) {
        return VESSEL_VELOCITY_CACHE.getOrDefault(subLevelId, new Vector3d(0.0, 0.0, 0.0));
    }

    public static double getForwardSpeed(UUID subLevelId) {
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
                HELM_ANGLE_CACHE.remove(id);
                VESSEL_VELOCITY_CACHE.remove(id);
                FORWARD_SPEED_CACHE.remove(id);
                PHYS_DEBUG_TICK.remove(id);
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
        Vector3d localRight = new Vector3d(localForward).cross(localUp).normalize();

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
        // If rigid body mass is uninitialized or defaulting below 2,000 kg for an assembled
        // multi-block vessel, ensure a realistic minimum mass based on total block count:
        double minMass = blockCount > 0 ? Math.max(2000.0, blockCount * 450.0) : 2000.0;
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

        // ---- Buoyancy / ride height -----------------------------------------
        double waterSurfaceY = waterSurfaceY(level, subLevel);
        var bounds = subLevel.boundingBox();
        double rawBottomY = bounds.minY();
        double hullHeight = bounds.maxY() - bounds.minY();
        boolean isShallowDraft = hullHeight <= 1.5;

        // Clamp bottomY so a deployed anchor chain cannot fool the spring solver
        double bottomY = Math.max(rawBottomY, waterSurfaceY - TARGET_HULL_DRAFT - 2.0);

        // Task 3.1: Raft Flotation Check: Flat vessels with minimal draft (T < 0.5m)
        // are recognized as floating whenever any hull block intersects water.
        int submergedSamples = countSubmergedHullSamples(level, subLevel);
        boolean waterContact = waterSurfaceY > Double.NEGATIVE_INFINITY
                && (rawBottomY < waterSurfaceY + 1.5 || (isShallowDraft && rawBottomY <= waterSurfaceY + 0.5));
        boolean afloat = waterContact || (submergedSamples > 0);
        if (afloat && waterSurfaceY > Double.NEGATIVE_INFINITY) {
            applyRideHeightSpring(body, waterSurfaceY, bottomY, isShallowDraft, dt);
        }

        // ---- Flotation gate: beached ships get ground friction only --------
        if (!afloat && submergedSamples == 0) {
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
            if (thrustMag > maxAeroForce) {
                thrustWorld.normalize().mul(maxAeroForce);
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
            if (localLinearImpulse.length() > maxImpulse) {
                localLinearImpulse.normalize().mul(maxImpulse);
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

        // ---- Stabilized pure-yaw steering & righting torque -----------------
        boolean sailsCatchingWind = thrustMag > 1.0e-9;
        applySteeringAndStabilization(subLevel, body, pose, level, forwardWorld, shipVelWorld, omegaWorld, planarSpeed, sailsCatchingWind, anchored, dt,
                steering, thrustForceN, dragForceMag, totalMass, massScale, localForward, localRight, localLinearImpulse, hasActiveThrust);

        // ---- Nautical ambience: hull creaks under heavy stress ---------------
        // Fires when wave roll or yaw rate exceeds a threshold, with a
        // per-sublevel cooldown so the creak reads as occasional groaning
        // rather than a constant loop.
        if (!anchored && (Math.abs(omegaWorld.x) > 0.05 || Math.abs(omegaWorld.y) > 0.05 || Math.abs(omegaWorld.z) > 0.05)) {
            Long lastCreak = CREAK_COOLDOWN.get(subLevel.getUniqueId());
            if (lastCreak == null || stepCounter - lastCreak >= 60L) {
                CREAK_COOLDOWN.put(subLevel.getUniqueId(), stepCounter);
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

        if (stepCounter % 20L == 0L && thrustMag > 1.0e-9) {
            SereneSailing.LOGGER.info(String.format(
                    "[SereneSailing] %s | wind %.2f m/s @ %.0f deg | thrust %.0f N | speed %.2f m/s",
                    subLevel.getName(), effectiveWindSpeed, rawWindAngle,
                    thrustMag, shipVelWorld.length()));

            // Wind billow while the sails generate near-maximum lift.
            double maxAeroRef = steering.sailAreaEfficiency() * FORCE_PER_FULL_SAIL_N;
            if (!anchored && maxAeroRef > 0.0 && thrustMag >= maxAeroRef * 0.75 && stepCounter % 100L == 0L) {
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
            Vector3d swayImpulse = new Vector3d(localRight).mul(-KEEL_LATERAL_DAMPING * vLateral * dt * massScale);
            body.applyLinearAndAngularImpulse(swayImpulse, new Vector3d(), true);
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
                                                      boolean hasActiveThrust) {
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

        // 4. Restoring Impulses (PD controller: -k*theta - c*omega)
        // Base specific stiffness (normalized per tonne of displacement)
        double massRatio = Math.max(1.0, totalMass / 2000.0);

        double kRoll  = K_ROLL_SPRING * massRatio;
        double cRoll  = C_ROLL_DAMP * massRatio;
        double kPitch = K_PITCH_SPRING * massRatio;
        double cPitch = C_PITCH_DAMP * massRatio;

        // Restoring vector impulses:
        Vector3d rollTorqueVec = new Vector3d(localForward).mul((kRoll * rollAngle - cRoll * omegaRoll) * dt);
        Vector3d pitchTorqueVec = new Vector3d(localRight).mul((-kPitch * pitchAngle - cPitch * omegaPitch) * dt);

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
                    // FIX: Transform the rudder's local position to world space,
                    // then sample water at the block bottom (localPoint Y is the
                    // block bottom, not center — see scan fix below).
                    Vector3d rudderWorld = pose.transformPosition(
                            new Vector3d(rudder.localPoint()), new Vector3d());
                    if (!level.getFluidState(BlockPos.containing(
                            rudderWorld.x, rudderWorld.y, rudderWorld.z)).is(FluidTags.WATER)) {
                        continue;
                    }
                    submergedRudderCount++;

                    Direction facing = rudder.facing();
                    Vector3d rudderDir = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
                    facingSignSum += (rudderDir.dot(localForward) <= 0.0) ? 1.0 : -1.0;
                }

                if (submergedRudderCount > 0 && facingSignSum != 0.0) {
                    // FIX: Direct inline torque formula — STEER_TORQUE_COEFF is
                    // applied directly as tau = -coeff * v * sin(theta) * N_rudders.
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

        // 6. Apply to rigid body with body-fixed 3D axis mapping
        Vector3d yawTorqueVec = new Vector3d(0.0, yawImpulse, 0.0);
        Vector3d totalAngularImpulse = new Vector3d(rollTorqueVec).add(pitchTorqueVec).add(yawTorqueVec);
        body.applyLinearAndAngularImpulse(new Vector3d(0.0, 0.0, 0.0), totalAngularImpulse, true);

        // ---- In-Game Runtime Diagnostic Telemetry (throttled ~1 Hz / 20 ticks) ----
        long lastPhysTick = PHYS_DEBUG_TICK.getOrDefault(subLevel.getUniqueId(), -1L);
        if (level.getGameTime() % 20 == 0 && level.getGameTime() != lastPhysTick && hasActiveThrust) {
            PHYS_DEBUG_TICK.put(subLevel.getUniqueId(), level.getGameTime());
            LOGGER.info("[PHYS-DEBUG] Speed: {} m/s ({} kts) | ThrustN: {} | DragN: {} | MassScale: {}",
                planarSpeed, planarSpeed * 1.94384, thrustForceN, dragForceMag, massScale);
            LOGGER.info("[PHYS-DEBUG] ActiveSails: {} | AreaEff: {} | ThrustN: {} | BodyMass: {}",
                steering.sailCount(), steering.sailAreaEfficiency(), thrustForceN, totalMass);
            LOGGER.info("[PHYS-DEBUG] localForward: ({}, {}, {}) | LinImpulse: ({}, {}, {})",
                localForward.x, localForward.y, localForward.z,
                localLinearImpulse.x, localLinearImpulse.y, localLinearImpulse.z);
            LOGGER.info("[PHYS-DEBUG] Angles -> Roll: {} rad, Pitch: {} rad | OmegaLocal: ({}, {}, {})",
                rollAngle, pitchAngle, omegaLocal.x, omegaLocal.y, omegaLocal.z);
            LOGGER.info("[PHYS-DEBUG] Impulses -> Pitch(X): {}, Yaw(Y): {}, Roll(Z): {}",
                totalAngularImpulse.x, totalAngularImpulse.y, totalAngularImpulse.z);
        }

        // ---- Diagnostic telemetry (throttled ~1 Hz) -------------------------
        if (stepCounter % 20 == 0 && Math.abs(unifiedSteeringAngle) >= 0.5f) {
            Vector3d angVelAfter = new Vector3d(body.getAngularVelocity());
            SereneSailing.LOGGER.info(String.format(
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
     * Scans plot chunks for rudders (with facings), helm stations, wind vanes,
     * capstans, taffrail logs, and aggregates sail reefing efficiency.
     */
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
                        // Cross-check the placed blockstate: if the ANCHORED
                        // property is set, the vessel is moored even if the
                        // block entity's cached flag has gone stale.
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

                for (LevelChunkSection section : chunk.getSections()) {
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
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
                                }
                            }
                        }
                    }
                }
            }
        }
        return new SteeringCache(rudders, helms, vanes, capstans, taffrails, anchorState, sailAreaEfficiency, blockCount, sailFacing, sailCount);
    }

    /**
     * Samples the hull waterline: bottom-center plus four inset corners of the
     * sub-level's world bounding box.
     *
     * @return submerged sample count; zero means the ship is beached.
     */
    private static int countSubmergedHullSamples(ServerLevel level, ServerSubLevel subLevel) {
        var bounds = subLevel.boundingBox();
        double midY = (bounds.minY() + bounds.maxY()) * 0.5;
        double hullHeight = bounds.maxY() - bounds.minY();
        // Task 3.1: For flat rafts (height <= 1.5), sample at vertical bottom + 0.1 to reliably detect water intersection
        double waterlineY = hullHeight <= 1.5
                ? bounds.minY() + 0.1
                : midY - (hullHeight * 0.4) + 0.25;
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
        // FIX (Task 1): Use the vertical center of the bounding box
        // instead of minY for the scan band origin. When an anchor
        // chain extends downward, minY plummets to the seabed, causing
        // the water surface scan to miss the actual waterline entirely.
        double midY = (bounds.minY() + bounds.maxY()) * 0.5;
        int lo = (int) Math.floor(midY) - 6;
        int hi = (int) Math.ceil(midY) + 4;
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
     * hull bottom settles at {@code targetBottomY = waterSurfaceY - targetDraft},
     * drafted ~0.4 blocks into the water (or ~0.15 blocks for shallow-draft flat rafts).
     */
    private static void applyRideHeightSpring(RigidBodyHandle body, double waterSurfaceY,
                                              double bottomY, boolean isShallowDraft, double dt) {
        float targetDraft = isShallowDraft ? 0.15f : TARGET_HULL_DRAFT;
        float targetBottomY = (float) (waterSurfaceY - targetDraft);
        float currentBottomY = (float) bottomY;
        double depth = targetBottomY - currentBottomY;
        Vector3d vel = new Vector3d(body.getLinearVelocity());

        // FIX (Bug 5): Symmetric spring — applies both upward lift when too
        // deep AND downward pull when riding too high (e.g. after a collision
        // or buoyancy overshoot). Without the downward component, the ship
        // porpoises above equilibrium with no active restoring force.
        if (depth > 0.0) {
            // Below equilibrium: lift up (capped at 3 blocks to prevent catapult)
            double liftDv = Math.min(depth, 3.0) * RIDE_SPRING * dt;
            double vy = (vel.y + liftDv) * Math.exp(-4.0 * dt);
            body.addLinearAndAngularVelocity(new Vector3d(0.0, vy - vel.y, 0.0),
                    new Vector3d());
        } else if (depth < -0.1) {
            // Above equilibrium by more than 0.1 blocks: pull back down.
            // Softer constant (0.5x RIDE_SPRING) to avoid slamming the hull,
            // capped at 1.5 blocks of overshoot contribution.
            double pullDv = Math.max(depth, -1.5) * RIDE_SPRING * 0.5 * dt;
            double vy = (vel.y + pullDv) * Math.exp(-4.0 * dt);
            body.addLinearAndAngularVelocity(new Vector3d(0.0, vy - vel.y, 0.0),
                    new Vector3d());
        }
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

