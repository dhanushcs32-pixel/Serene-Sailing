package com.serenesailing.entity;

import com.serenesailing.SereneSailing;
import com.serenesailing.event.ServerPhysicsHandler;
import com.serenesailing.registry.ModBlockEntities;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stern taffrail log. Measures the parent vessel's speed in m/s (pushed as
 * telemetry by the physics handler; 0 when mounted ashore) and exposes it in
 * nautical knots for the action-bar readout and the redstone comparator
 * signal (0-15 proportional to 0-15 knots).
 */
public class TaffrailLogBlockEntity extends BlockEntity {

    /** 1 m/s expressed in knots. */
    public static final float MS_TO_KNOTS = 1.944f;
    /** Comparator signal saturates at 15 knots. */
    public static final float MAX_KNOTS = 15.0f;

    /** Measured ship speed [m/s], pushed from the physics handler. */
    private float speedMs = 0.0f;
    /** Interpolation state for any future dial rendering. */
    private float prevSpeedMs = 0.0f;
    private int redstoneSignal = 0;
    private int tickCount = 0;

    public TaffrailLogBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TAFFRAIL_LOG.get(), pos, state);
    }

    /** Pushes live ship speed from the physics handler [m/s]. Negative = sternway. */
    public void setSpeedTelemetry(float speedMs) {
        this.speedMs = speedMs;
    }

    public float getSpeedMs() {
        return this.speedMs;
    }

    public float getSpeedKnots() {
        return this.speedMs * MS_TO_KNOTS;
    }

    public String getActionBarDisplay() {
        float absKnots = Math.abs(getSpeedKnots());
        float absMs = Math.abs(getSpeedMs());
        String direction = this.speedMs < -0.05f ? "\u00A7cSternway" : "\u00A7bShip Speed";
        return String.format("%s: %.1f knots (%.1f m/s)", direction, absKnots, absMs);
    }

    /** Comparator analog output: 0-15 proportional to 0-15 knots. */
    public int getRedstoneSignal() {
        return this.redstoneSignal;
    }

    /** Per-tick: polls vessel velocity from sub-level physics, updates analogSignal and telemetry. */
    public void tick() {
        this.prevSpeedMs = this.speedMs;
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

        double forwardSpeed = 0.0;
        if (subLevel != null) {
            // Read authoritative forward speed cached by ServerPhysicsHandler along vessel heading (localForward)
            forwardSpeed = ServerPhysicsHandler.getForwardSpeed(subLevel.getUniqueId());
            if (Math.abs(forwardSpeed) < 1.0e-5 && Math.abs(this.speedMs) > 1.0e-5) {
                forwardSpeed = this.speedMs;
            }
        } else {
            forwardSpeed = this.speedMs;
        }

        float knots = (float) (forwardSpeed * 1.944);
        this.speedMs = (float) forwardSpeed;

        if (this.tickCount++ % 20 == 0) {
            String dir = forwardSpeed < -0.05 ? "STERNWAY" : "FWD";
            SereneSailing.LOGGER.debug("[TAFFRAIL] {} ship speed: {} m/s ({} knots)", dir, 
                    String.format("%.2f", forwardSpeed), String.format("%.1f", Math.abs(knots)));
        }

        int signal = (int) Mth.clamp(Math.abs(knots), 0.0f, MAX_KNOTS);
        if (signal != this.redstoneSignal || Math.abs(this.speedMs - this.prevSpeedMs) > 0.05f) {
            this.redstoneSignal = signal;
            setChanged();
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("SpeedMs", this.speedMs);
        tag.putInt("Signal", this.redstoneSignal);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.speedMs = tag.getFloat("SpeedMs");
        if (Float.isNaN(this.speedMs) || Float.isInfinite(this.speedMs)) {
            this.speedMs = 0.0f;
        }
        this.prevSpeedMs = this.speedMs;
        this.redstoneSignal = Mth.clamp(tag.getInt("Signal"), 0, 15);
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
