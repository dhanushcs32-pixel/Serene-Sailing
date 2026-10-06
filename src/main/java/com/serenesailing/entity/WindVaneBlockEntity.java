package com.serenesailing.entity;

import com.serenesailing.SereneSailing;
import com.serenesailing.block.WindVaneBlock;
import com.serenesailing.config.SailingConfig;
import com.serenesailing.event.ServerPhysicsHandler;
import com.serenesailing.registry.ModBlockEntities;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import weather2.util.WindReader;

/**
 * Masthead Wind Vane. {@code ServerPhysicsHandler} and internal tickers refresh the live wind
 * telemetry; right-clicking the block reads it out.
 */
public class WindVaneBlockEntity extends BlockEntity {

    private float trueWindSpeed;
    private float apparentWindSpeed;
    /** Signed relative wind angle in degrees: negative = wind on port side, positive = starboard. */
    private float relativeWindAngleDeg;
    private String pointOfSail = "unknown";
    /** Server-side throttle counter for client telemetry sync (~5 Hz). */
    private int telemetryTick;
    private int tickCount = 0;

    // Smooth angular interpolation state (degrees, world-Y rotation).
    /** Angle last tick, for partial-tick interpolation. */
    private float prevApparentWindAngle;
    /** Current authoritative angle (synced via NBT update packets). */
    private float apparentWindAngle;

    public WindVaneBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WIND_VANE.get(), pos, state);
    }

    /**
     * Called by the physics bridge each step with live world-frame vectors.
     *
     * @param forwardWorld  ship bow direction (planar, normalized)
     * @param trueWindWorld true wind velocity (m/s, world frame)
     * @param shipVelWorld  ship linear velocity (m/s, world frame)
     */
    public void updateWindTelemetry(org.joml.Vector3dc forwardWorld,
                                    org.joml.Vector3dc trueWindWorld,
                                    org.joml.Vector3dc shipVelWorld) {
        this.trueWindSpeed = (float) Math.sqrt(
                trueWindWorld.x() * trueWindWorld.x() + trueWindWorld.z() * trueWindWorld.z());

        // Apparent wind = true wind - ship velocity (planar).
        double ax = trueWindWorld.x() - shipVelWorld.x();
        double az = trueWindWorld.z() - shipVelWorld.z();
        this.apparentWindSpeed = (float) Math.sqrt(ax * ax + az * az);

        if (apparentWindSpeed < 1.0e-4) {
            this.relativeWindAngleDeg = 0.0f;
            this.apparentWindAngle = 0.0f;
            this.pointOfSail = "Calm";
            return;
        }

        double fwdLen = Math.sqrt(forwardWorld.x() * forwardWorld.x()
                + forwardWorld.z() * forwardWorld.z());
        if (fwdLen < 1.0e-6) {
            this.pointOfSail = "Calm";
            return;
        }
        double fx = forwardWorld.x() / fwdLen;
        double fz = forwardWorld.z() / fwdLen;
        double adx = ax / apparentWindSpeed;
        double adz = az / apparentWindSpeed;

        // Signed angle from bow to apparent wind (CCW positive when viewed
        // from above): cos = dot, sin = cross-y component.
        double dot = fx * adx + fz * adz;
        double sin = fx * adz - fz * adx; // y-component of forward x apparent
        double angle = Math.toDegrees(Math.atan2(sin, dot));
        this.relativeWindAngleDeg = (float) angle;
        this.apparentWindAngle = (float) angle;

        double abs = Math.abs(angle);
        String side = angle < 0 ? "Port" : "Starboard";
        if (abs < 30.0) {
            this.pointOfSail = "In Irons (" + (int) abs + "\u00B0 " + side + ")";
        } else if (abs < 60.0) {
            this.pointOfSail = "Close-Hauled (" + (int) abs + "\u00B0 " + side + ")";
        } else if (abs < 120.0) {
            this.pointOfSail = "Beam Reach (" + (int) abs + "\u00B0 " + side + ")";
        } else if (abs < 150.0) {
            this.pointOfSail = "Broad Reach (" + (int) abs + "\u00B0 " + side + ")";
        } else {
            this.pointOfSail = "Running / Dead Downwind";
        }

        // Throttled server->client sync (~5 Hz): the masthead pennant is
        // rendered client-side by the BER from the relative wind angle, and
        // physics steps run far faster than vanilla block updates.
        if (level != null && !level.isClientSide && ++this.telemetryTick % 4 == 0) {
            this.setChanged();
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(),
                    this.getBlockState(), 3);
        }
    }

    public float getTrueWindSpeed() {
        return trueWindSpeed;
    }

    public float getApparentWindSpeed() {
        return apparentWindSpeed;
    }

    public String getPointOfSail() {
        return pointOfSail;
    }

    /**
     * @return signed apparent wind angle in degrees relative to the ship's
     *         bow (negative = wind on port, positive = starboard). Drives the
     *         rotating pennant in {@code WindVaneRenderer}.
     */
    public float getApparentWindAngle() {
        return this.apparentWindAngle;
    }

    /**
     * Per-tick interpolation bookkeeping and authoritative wind telemetry resolution.
     */
    public void tick() {
        this.prevApparentWindAngle = this.apparentWindAngle;
        if (this.level == null || this.level.isClientSide) {
            return;
        }

        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }

        ServerSubLevel subLevel = null;
        SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        if (container instanceof ServerSubLevelContainer serverContainer) {
            LevelPlot plot = serverContainer.getPlot(new ChunkPos(this.worldPosition));
            if (plot != null && plot.getSubLevel() instanceof ServerSubLevel ssl) {
                subLevel = ssl;
            } else {
                for (ServerSubLevel ssl : serverContainer.getAllSubLevels()) {
                    LevelPlot subPlot = ssl.getPlot();
                    if (subPlot != null && subPlot.contains(new ChunkPos(this.worldPosition))) {
                        subLevel = ssl;
                        break;
                    }
                }
            }
        }

        Vec3 samplePos;
        BlockPos queryPos;
        if (subLevel != null) {
            Pose3dc pose = subLevel.logicalPose();
            samplePos = new Vec3(pose.position().x(), pose.position().y(), pose.position().z());
            queryPos = BlockPos.containing(samplePos);
        } else {
            samplePos = Vec3.atCenterOf(this.worldPosition);
            queryPos = this.worldPosition;
        }

        ServerLevel queryLevel = ServerPhysicsHandler.resolveWeatherQueryLevel(serverLevel);
        if (queryLevel == null) {
            queryLevel = serverLevel;
        }

        float trueSpeed = WindReader.getWindSpeed(queryLevel, queryPos);
        float trueAngle = WindReader.getWindAngle(queryLevel, samplePos);

        if (this.tickCount++ % 20 == 0) {
            SereneSailing.LOGGER.info("[WINDVANE] True Wind: speed={}, angle={} | Pos: {} | Level: {}",
                    trueSpeed, trueAngle, queryPos, queryLevel.dimension().location());
        }

        double effectiveWindSpeed = Math.max(SailingConfig.MIN_WIND_SPEED.get(), trueSpeed * 20.0);
        double windRad = Math.toRadians(trueAngle);
        Vector3d trueWindWorld = new Vector3d(-Math.sin(windRad), 0.0, Math.cos(windRad)).mul(effectiveWindSpeed);

        Vector3d shipVelWorld;
        Vector3d forwardWorld;
        if (subLevel != null) {
            RigidBodyHandle body = RigidBodyHandle.of(subLevel);
            if (body != null && body.isValid()) {
                shipVelWorld = new Vector3d(body.getLinearVelocity());
            } else {
                shipVelWorld = ServerPhysicsHandler.getVesselVelocity(subLevel.getUniqueId());
            }
            Quaterniondc orientation = subLevel.logicalPose().orientation();
            forwardWorld = new Vector3d(0.0, 0.0, -1.0).rotate(orientation);
            forwardWorld.y = 0.0;
            if (forwardWorld.lengthSquared() > 1.0e-4) {
                forwardWorld.normalize();
            } else {
                forwardWorld.set(0.0, 0.0, -1.0);
            }
        } else {
            shipVelWorld = new Vector3d(0.0, 0.0, 0.0);
            Direction facing = getBlockState().hasProperty(WindVaneBlock.FACING)
                    ? getBlockState().getValue(WindVaneBlock.FACING)
                    : Direction.NORTH;
            forwardWorld = new Vector3d(facing.getStepX(), 0.0, facing.getStepZ());
            if (forwardWorld.lengthSquared() > 1.0e-4) {
                forwardWorld.normalize();
            } else {
                forwardWorld.set(0.0, 0.0, -1.0);
            }
        }

        updateWindTelemetry(forwardWorld, trueWindWorld, shipVelWorld);
    }

    /**
     * @return the apparent wind angle interpolated between the previous and
     *         current tick for smooth, frame-rate-independent pennant motion.
     */
    public float getInterpolatedWindAngle(float partialTick) {
        return net.minecraft.util.Mth.rotLerp(partialTick,
                this.prevApparentWindAngle, this.apparentWindAngle);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("TrueWind", this.trueWindSpeed);
        tag.putFloat("ApparentWind", this.apparentWindSpeed);
        tag.putFloat("RelWindAngle", this.relativeWindAngleDeg);
        tag.putString("PointOfSail", this.pointOfSail);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.trueWindSpeed = tag.getFloat("TrueWind");
        this.apparentWindSpeed = tag.getFloat("ApparentWind");
        this.relativeWindAngleDeg = tag.getFloat("RelWindAngle");
        this.pointOfSail = tag.getString("PointOfSail");
        // Avoid a visible spin from 0 deg on (re)load: snap both interp states.
        this.apparentWindAngle = this.relativeWindAngleDeg;
        this.prevApparentWindAngle = this.relativeWindAngleDeg;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
