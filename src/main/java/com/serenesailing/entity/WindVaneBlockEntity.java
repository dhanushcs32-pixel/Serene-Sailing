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
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * Masthead Wind Vane. Computes live wind telemetry in local coordinate frame
 * and smoothly tracks apparent wind direction without wrap-around flipping.
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

    // Smooth angular interpolation state (degrees).
    private float prevAngle;
    private float currentAngle;
    private float targetAngle;

    public WindVaneBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WIND_VANE.get(), pos, state);
    }

    /**
     * Called by the physics bridge each step with live world-frame vectors.
     */
    public void updateWindTelemetry(org.joml.Vector3dc forwardWorld,
                                    org.joml.Vector3dc trueWindWorld,
                                    org.joml.Vector3dc shipVelWorld) {
        this.trueWindSpeed = (float) Math.sqrt(
                trueWindWorld.x() * trueWindWorld.x() + trueWindWorld.z() * trueWindWorld.z());

        double ax = trueWindWorld.x() - shipVelWorld.x();
        double az = trueWindWorld.z() - shipVelWorld.z();
        this.apparentWindSpeed = (float) Math.sqrt(ax * ax + az * az);

        if (this.apparentWindSpeed < 1.0e-4) {
            this.relativeWindAngleDeg = 0.0f;
            this.targetAngle = 0.0f;
            this.pointOfSail = "Calm";
            return;
        }

        double fwdLen = Math.sqrt(forwardWorld.x() * forwardWorld.x() + forwardWorld.z() * forwardWorld.z());
        if (fwdLen < 1.0e-6) {
            this.pointOfSail = "Calm";
            return;
        }
        double fx = forwardWorld.x() / fwdLen;
        double fz = forwardWorld.z() / fwdLen;
        double adx = -ax / apparentWindSpeed;
        double adz = -az / apparentWindSpeed;

        double dot = fx * adx + fz * adz;
        double sin = fx * adz - fz * adx;
        double angle = Math.toDegrees(Math.atan2(sin, dot));
        this.targetAngle = (float) angle;

        float diff = Mth.wrapDegrees(this.targetAngle - this.currentAngle);
        this.prevAngle = this.currentAngle;
        this.currentAngle = Mth.wrapDegrees(this.currentAngle + diff * 0.12f);
        this.relativeWindAngleDeg = this.currentAngle;

        double abs = Math.abs(this.currentAngle);
        String side = this.currentAngle < 0 ? "Port" : "Starboard";
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

        if (level != null && !level.isClientSide && ++this.telemetryTick % 4 == 0) {
            this.setChanged();
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
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

    public float getPrevAngle() {
        return this.prevAngle;
    }

    public float getCurrentAngle() {
        return this.currentAngle;
    }

    public float getApparentWindAngle() {
        return this.currentAngle;
    }

    /**
     * @return the apparent wind angle interpolated between previous and
     *         current tick with shortest-arc rotational interpolation.
     */
    public float getInterpolatedWindAngle(float partialTick) {
        return Mth.rotLerp(partialTick, this.prevAngle, this.currentAngle);
    }

    /**
     * Per-tick interpolation bookkeeping and authoritative wind telemetry resolution.
     */
    public void tick() {
        this.prevAngle = this.currentAngle;

        if (this.level != null && this.level.isClientSide) {
            float diff = Mth.wrapDegrees(this.targetAngle - this.currentAngle);
            this.currentAngle = Mth.wrapDegrees(this.currentAngle + diff * 0.12f);
            return;
        }

        if (this.level == null || !(this.level instanceof ServerLevel serverLevel)) {
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

        ServerPhysicsHandler.CachedWind cachedWind = ServerPhysicsHandler.getDimensionWind(serverLevel.dimension());
        float trueSpeed = cachedWind.speed();
        float trueAngle = cachedWind.angle();

        double effectiveWindSpeed = Math.max(SailingConfig.MIN_WIND_SPEED.get(), trueSpeed * 20.0);
        double windRad = Math.toRadians(trueAngle);
        Vector3d trueWindWorld = new Vector3d(-Math.sin(windRad), 0.0, Math.cos(windRad)).mul(effectiveWindSpeed);

        Vector3d shipVelWorld;
        if (subLevel != null) {
            RigidBodyHandle body = RigidBodyHandle.of(subLevel);
            if (body != null && body.isValid()) {
                shipVelWorld = new Vector3d(body.getLinearVelocity());
            } else {
                shipVelWorld = ServerPhysicsHandler.getVesselVelocity(subLevel.getUniqueId());
            }
        } else {
            shipVelWorld = new Vector3d(0.0, 0.0, 0.0);
        }

        double ax = trueWindWorld.x - shipVelWorld.x;
        double az = trueWindWorld.z - shipVelWorld.z;
        this.trueWindSpeed = (float) Math.sqrt(trueWindWorld.x * trueWindWorld.x + trueWindWorld.z * trueWindWorld.z);
        this.apparentWindSpeed = (float) Math.sqrt(ax * ax + az * az);

        // Transform apparent wind vector into sub-level local coordinate frame
        double localWindX = ax;
        double localWindZ = az;
        if (subLevel != null) {
            Pose3dc pose = subLevel.logicalPose();
            Vector3d localWind = pose.transformNormalInverse(new Vector3d(ax, 0.0, az), new Vector3d());
            localWindX = localWind.x;
            localWindZ = localWind.z;
        }

        if (this.apparentWindSpeed < 1.0e-4) {
            this.pointOfSail = "Calm";
            this.targetAngle = 0.0f;
        } else {
            this.targetAngle = (float) Math.toDegrees(Math.atan2(-localWindX, localWindZ));

            double abs = Math.abs(this.currentAngle);
            String side = this.currentAngle < 0 ? "Port" : "Starboard";
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
        }

        float diff = Mth.wrapDegrees(this.targetAngle - this.currentAngle);
        this.currentAngle = Mth.wrapDegrees(this.currentAngle + diff * 0.12f);
        this.relativeWindAngleDeg = this.currentAngle;

        if (serverLevel.getServer() != null && serverLevel.getServer().getTickCount() % 4 == 0) {
            this.setChanged();
            serverLevel.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("TrueWind", this.trueWindSpeed);
        tag.putFloat("ApparentWind", this.apparentWindSpeed);
        tag.putFloat("RelWindAngle", this.relativeWindAngleDeg);
        tag.putFloat("TargetAngle", this.targetAngle);
        tag.putString("PointOfSail", this.pointOfSail);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.trueWindSpeed = tag.getFloat("TrueWind");
        this.apparentWindSpeed = tag.getFloat("ApparentWind");
        this.relativeWindAngleDeg = tag.getFloat("RelWindAngle");
        this.targetAngle = tag.contains("TargetAngle") ? tag.getFloat("TargetAngle") : this.relativeWindAngleDeg;
        this.pointOfSail = tag.getString("PointOfSail");

        if (Float.isNaN(this.trueWindSpeed) || Float.isInfinite(this.trueWindSpeed)) this.trueWindSpeed = 0.0f;
        if (Float.isNaN(this.apparentWindSpeed) || Float.isInfinite(this.apparentWindSpeed)) this.apparentWindSpeed = 0.0f;
        if (Float.isNaN(this.relativeWindAngleDeg) || Float.isInfinite(this.relativeWindAngleDeg)) this.relativeWindAngleDeg = 0.0f;
        if (Float.isNaN(this.targetAngle) || Float.isInfinite(this.targetAngle)) this.targetAngle = 0.0f;

        if (this.currentAngle == 0.0f && this.prevAngle == 0.0f) {
            this.currentAngle = this.targetAngle;
            this.prevAngle = this.targetAngle;
        }
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
