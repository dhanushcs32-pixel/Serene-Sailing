package com.serenesailing.entity;

import com.serenesailing.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ship's Wheel steering station. Holds the authoritative helm angle for the
 * assembled contraption; {@code ServerPhysicsHandler} propagates this angle to
 * every attached {@link RudderBlockEntity} so the blades follow the wheel.
 */
public class HelmBlockEntity extends BlockEntity {

    private float steeringAngle = 0.0f; // [-30.0, +30.0] degrees, 15-degree steps

    public HelmBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HELM.get(), pos, state);
    }

    public float getSteeringAngle() {
        return this.steeringAngle;
    }

    public void setSteeringAngle(float angle) {
        // Clamp to the physical stop - five discrete states:
        // -30 (Hard Port), -15 (Port), 0 (Centered), +15 (Starboard), +30 (Hard Starboard).
        this.steeringAngle = Math.max(-30.0f, Math.min(30.0f, angle));
        // Sync contract: mark dirty AND push the update packet so tracking
        // clients see the new wheel position immediately (see RudderBlockEntity).
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
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
