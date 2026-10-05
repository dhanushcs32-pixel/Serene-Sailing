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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Ship's Wheel steering station. Holds the authoritative helm angle for the
 * assembled contraption; propagates this angle to every attached
 * {@link RudderBlockEntity} so the blades follow the wheel.
 */
public class HelmBlockEntity extends BlockEntity {

    private float steeringAngle = 0.0f; // [-45.0, +45.0] degrees
    public HelmBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HELM.get(), pos, state);
    }

    public float getSteeringAngle() {
        return this.steeringAngle;
    }

    public void setSteeringAngle(float angle) {
        // Clamp to the physical stop [-45, +45]
        this.steeringAngle = Math.max(-45.0f, Math.min(45.0f, angle));
        // Sync contract: mark dirty AND push the update packet so tracking
        // clients see the new wheel position immediately (see RudderBlockEntity).
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            propagateSteeringAngle(this.steeringAngle);
        }
    }

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
                SereneSailing.LOGGER.info("[HELM-SYNC] Propagating angle: {} to {} found rudders", angle, foundRudders);
                return;
            }
            // Check all loaded sub-levels in case the plot contains this chunk
            for (ServerSubLevel subLevel : serverContainer.getAllSubLevels()) {
                LevelPlot subPlot = subLevel.getPlot();
                if (subPlot != null && subPlot.contains(new ChunkPos(this.worldPosition))) {
                    ServerPhysicsHandler.setHelmAngle(subLevel.getUniqueId(), angle);
                    foundRudders += syncRuddersInPlot(subPlot, angle, serverLevel);
                    SereneSailing.LOGGER.info("[HELM-SYNC] Propagating angle: {} to {} found rudders", angle, foundRudders);
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
        SereneSailing.LOGGER.info("[HELM-SYNC] Propagating angle: {} to {} found rudders", angle, foundRudders);
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
        setSteeringAngle(this.steeringAngle + delta);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("steeringAngle", this.steeringAngle);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.steeringAngle = tag.getFloat("steeringAngle");
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
