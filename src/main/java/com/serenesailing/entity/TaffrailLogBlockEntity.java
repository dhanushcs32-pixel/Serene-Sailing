package com.serenesailing.entity;

import com.serenesailing.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
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

    public TaffrailLogBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TAFFRAIL_LOG.get(), pos, state);
    }

    /** Pushes live ship speed from the physics handler [m/s]. */
    public void setSpeedTelemetry(float speedMs) {
        this.speedMs = Math.max(0.0f, speedMs);
    }

    public float getSpeedMs() {
        return this.speedMs;
    }

    public float getSpeedKnots() {
        return this.speedMs * MS_TO_KNOTS;
    }

    /** Comparator analog output: 0-15 proportional to 0-15 knots. */
    public int getRedstoneSignal() {
        return this.redstoneSignal;
    }

    /** Per-tick: updates the comparator signal (server authoritative). */
    public void tick() {
        this.prevSpeedMs = this.speedMs;
        int signal = (int) Mth.clamp(getSpeedKnots(), 0.0f, MAX_KNOTS);
        if (level != null && !level.isClientSide && signal != this.redstoneSignal) {
            this.redstoneSignal = signal;
            setChanged();
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
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
        this.prevSpeedMs = this.speedMs;
        this.redstoneSignal = tag.getInt("Signal");
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
