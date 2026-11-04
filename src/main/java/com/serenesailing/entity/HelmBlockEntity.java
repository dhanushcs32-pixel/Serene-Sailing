package com.serenesailing.entity;

import com.serenesailing.SereneSailing;
import com.serenesailing.event.ServerPhysicsHandler;
import com.serenesailing.registry.ModBlockEntities;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Ship's Wheel steering station. Holds the authoritative helm angle for the
 * assembled contraption; propagates this angle to every attached
 * {@link RudderBlockEntity} so the blades follow the wheel.
 *
 * <p>Tracks both a {@code targetRudderAngle} (set directly by client input)
 * and a smoothly interpolated {@code currentRudderAngle} used for rendering.
 * The visual wheel rotation is 4x the rudder angle (±35° rudder → ±140° wheel).</p>
 */
public class HelmBlockEntity extends BlockEntity {

    /** Maximum rudder deflection in either direction, degrees. */
    public static final float MAX_RUDDER_ANGLE = 35.0f;

    /** Visual wheel rotation = rudder angle × this ratio. */
    public static final float STEERING_RATIO = 4.0f;

    /** The rudder angle that input is driving towards. */
    private float targetRudderAngle = 0.0f;

    /** Smoothly interpolated rudder angle used for rendering. */
    private float currentRudderAngle = 0.0f;

    /** True while a player is actively holding the mouse button on the helm. */
    private boolean activelyHeld = false;

    /**
     * A held helm is released by the server if no steer packet arrived for this many ticks. The client sends a
     * heartbeat every {@code HOLD_HEARTBEAT_TICKS}; without this a disconnect, a crash or a hostile client could
     * pin the rudder hard over for good (activelyHeld is also saved to disk).
     */
    public static final int HOLD_TIMEOUT_TICKS = 40;
    /** Client heartbeat interval while the wheel is held. */
    public static final int HOLD_HEARTBEAT_TICKS = 10;
    /** Server game time of the last accepted steer packet. */
    private long lastInputGameTime = Long.MIN_VALUE;

    public HelmBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HELM.get(), pos, state);
    }

    // ---- Accessors ----------------------------------------------------------

    /** Returns the smoothed rudder angle for rendering purposes. */
    public float getCurrentRudderAngle() {
        return this.currentRudderAngle;
    }

    /** Returns the visual wheel rotation angle (4:1 ratio). */
    public float getVisualWheelAngle() {
        return this.currentRudderAngle * STEERING_RATIO;
    }

    /** Returns the target rudder angle (what input is requesting). */
    public float getTargetRudderAngle() {
        return this.targetRudderAngle;
    }

    /**
     * Legacy accessor kept for backwards compatibility with existing code
     * that reads the steering angle (e.g. physics handler, rudder sync).
     */
    public float getSteeringAngle() {
        return this.currentRudderAngle;
    }

    /** Returns whether a player is actively steering. */
    public boolean isActivelyHeld() {
        return this.activelyHeld;
    }

    // ---- Mutators -----------------------------------------------------------

    /**
     * Sets the target rudder angle and active-hold state. Called from the
     * serverbound steer payload handler.
     */
    public void setSteeringInput(float rudderAngle, boolean held) {
        if (Float.isNaN(rudderAngle) || Float.isInfinite(rudderAngle)) {
            rudderAngle = 0.0f;
        }
        this.targetRudderAngle = Mth.clamp(rudderAngle, -MAX_RUDDER_ANGLE, MAX_RUDDER_ANGLE);
        this.activelyHeld = held;
        if (level != null) {
            this.lastInputGameTime = level.getGameTime();
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Client-side prediction setter on the targeted block entity for 60+ FPS responsiveness.
     */
    public void setTargetRudderAngleClient(float angle) {
        if (Float.isNaN(angle) || Float.isInfinite(angle)) {
            angle = 0.0f;
        }
        this.targetRudderAngle = Mth.clamp(angle, -MAX_RUDDER_ANGLE, MAX_RUDDER_ANGLE);
        this.activelyHeld = true;
    }

    /**
     * Legacy setter: sets the steering angle directly (for backward
     * compatibility with existing rudder propagation code).
     */
    public void setSteeringAngle(float angle) {
        if (Float.isNaN(angle) || Float.isInfinite(angle)) {
            angle = 0.0f;
        }
        this.targetRudderAngle = Mth.clamp(angle, -MAX_RUDDER_ANGLE, MAX_RUDDER_ANGLE);
        this.currentRudderAngle = this.targetRudderAngle;
        this.activelyHeld = false;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            propagateSteeringAngle(this.currentRudderAngle);
        }
    }

    // ---- Tick ---------------------------------------------------------------

    /**
     * Called every game tick by the block's ticker.
     * <ul>
     *   <li>If not actively held, spring the target back to center.</li>
     *   <li>Smoothly interpolate the rendering angle toward the target.</li>
     *   <li>Propagate to rudders on the server side.</li>
     * </ul>
     */
    public void tick() {
        // Server: release a hold whose player stopped talking to us (see HOLD_TIMEOUT_TICKS).
        if (this.activelyHeld && level != null && !level.isClientSide
                && level.getGameTime() - this.lastInputGameTime > HOLD_TIMEOUT_TICKS) {
            this.activelyHeld = false;
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }

        // Spring back to center when no player is actively steering
        if (!this.activelyHeld) {
            // Reduced from 0.15f -> 0.09f for a slower, heavier helm feel
            this.targetRudderAngle = Mth.lerp(0.09f, this.targetRudderAngle, 0.0f);
            // Snap to zero when close enough to avoid endless tiny lerps
            if (Math.abs(this.targetRudderAngle) < 0.1f) {
                this.targetRudderAngle = 0.0f;
            }
        }

        // Smooth visual follow-through
        float prevAngle = this.currentRudderAngle;
        this.currentRudderAngle = Mth.lerp(0.25f, this.currentRudderAngle, this.targetRudderAngle);
        if (Math.abs(this.currentRudderAngle) < 0.05f && Math.abs(this.targetRudderAngle) < 0.05f) {
            this.currentRudderAngle = 0.0f;
        }

        // Server-side: propagate angle to rudders & physics when the angle changes
        if (level != null && !level.isClientSide) {
            if (Math.abs(this.currentRudderAngle - prevAngle) > 0.01f) {
                propagateSteeringAngle(this.currentRudderAngle);
                setChanged();
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    // ---- Rudder Propagation -------------------------------------------------

    /**
     * Propagates the helm steering angle to all active RudderBlockEntity instances
     * associated with this sub-level / ship so rudder blades deflect in unison.
     */
    private void propagateSteeringAngle(float angle) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }

        int foundRudders = 0;
        SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        if (container instanceof ServerSubLevelContainer serverContainer) {
            LevelPlot plot = serverContainer.getPlot(new ChunkPos(this.worldPosition));
            if (plot != null) {
                if (plot.getSubLevel() instanceof ServerSubLevel subLevel) {
                    ServerPhysicsHandler.setHelmAngle(subLevel.getUniqueId(), angle);
                }
                foundRudders += syncRuddersInPlot(plot, angle, serverLevel);
                SereneSailing.LOGGER.debug("[HELM-SYNC] Propagating angle: {} to {} found rudders", angle, foundRudders);
                return;
            }
            // Check all loaded sub-levels in case the plot contains this chunk
            for (ServerSubLevel subLevel : serverContainer.getAllSubLevels()) {
                LevelPlot subPlot = subLevel.getPlot();
                if (subPlot != null && subPlot.contains(new ChunkPos(this.worldPosition))) {
                    ServerPhysicsHandler.setHelmAngle(subLevel.getUniqueId(), angle);
                    foundRudders += syncRuddersInPlot(subPlot, angle, serverLevel);
                    SereneSailing.LOGGER.debug("[HELM-SYNC] Propagating angle: {} to {} found rudders", angle, foundRudders);
                    return;
                }
            }
        }

        // Fallback for unassembled / non-sublevel ships: search nearby loaded chunks
        int centerChunkX = this.worldPosition.getX() >> 4;
        int centerChunkZ = this.worldPosition.getZ() >> 4;
        int radius = 2;
        for (int cx = centerChunkX - radius; cx <= centerChunkX + radius; cx++) {
            for (int cz = centerChunkZ - radius; cz <= centerChunkZ + radius; cz++) {
                LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) {
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof RudderBlockEntity rudder) {
                            foundRudders++;
                            rudder.setSteeringAngle(angle);
                            rudder.setChanged();
                            Level rLvl = rudder.getLevel() != null ? rudder.getLevel() : serverLevel;
                            rLvl.sendBlockUpdated(rudder.getBlockPos(), rudder.getBlockState(), rudder.getBlockState(), 3);
                        }
                    }
                }
            }
        }
        SereneSailing.LOGGER.debug("[HELM-SYNC] Propagating angle: {} to {} found rudders", angle, foundRudders);
    }

    private int syncRuddersInPlot(LevelPlot plot, float angle, ServerLevel serverLevel) {
        int count = 0;
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk != null) {
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof RudderBlockEntity rudder) {
                        count++;
                        rudder.setSteeringAngle(angle);
                        rudder.setChanged();
                        Level rLvl = rudder.getLevel() != null ? rudder.getLevel() : serverLevel;
                        rLvl.sendBlockUpdated(rudder.getBlockPos(), rudder.getBlockState(), rudder.getBlockState(), 3);
                    }
                }
            }
        }
        return count;
    }

    public void cycleSteering(float delta) {
        setSteeringAngle(this.currentRudderAngle + delta);
    }

    // ---- NBT ----------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("targetRudderAngle", this.targetRudderAngle);
        tag.putFloat("currentRudderAngle", this.currentRudderAngle);
        tag.putBoolean("activelyHeld", this.activelyHeld);
        // Legacy compat: also write under old key so older clients don't crash
        tag.putFloat("steeringAngle", this.currentRudderAngle);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("targetRudderAngle")) {
            this.targetRudderAngle = tag.getFloat("targetRudderAngle");
            this.currentRudderAngle = tag.getFloat("currentRudderAngle");
            this.activelyHeld = tag.getBoolean("activelyHeld");
        } else if (tag.contains("steeringAngle")) {
            // Migration from old format
            float oldAngle = tag.getFloat("steeringAngle");
            this.targetRudderAngle = Mth.clamp(oldAngle, -MAX_RUDDER_ANGLE, MAX_RUDDER_ANGLE);
            this.currentRudderAngle = this.targetRudderAngle;
            this.activelyHeld = false;
        }
        if (Float.isNaN(this.targetRudderAngle) || Float.isInfinite(this.targetRudderAngle)) {
            this.targetRudderAngle = 0.0f;
        }
        if (Float.isNaN(this.currentRudderAngle) || Float.isInfinite(this.currentRudderAngle)) {
            this.currentRudderAngle = 0.0f;
        }
        // Edited / corrupt NBT must not exceed the physical stop.
        this.targetRudderAngle = Mth.clamp(this.targetRudderAngle, -MAX_RUDDER_ANGLE, MAX_RUDDER_ANGLE);
        this.currentRudderAngle = Mth.clamp(this.currentRudderAngle, -MAX_RUDDER_ANGLE, MAX_RUDDER_ANGLE);
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
