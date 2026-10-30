package com.serenesailing.entity;

import com.serenesailing.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class RudderBlockEntity extends BlockEntity {

    private float steeringAngle = 0.0f; // [-45.0, +45.0] degrees

    public RudderBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RUDDER.get(), pos, state);
    }

    public float getSteeringAngle() {
        return this.steeringAngle;
    }

    public void setSteeringAngle(float angle) {
        if (Float.isNaN(angle) || Float.isInfinite(angle)) {
            angle = 0.0f;
        }
        // Clamp to the physical stop [-45, +45]
        this.steeringAngle = Math.max(-45.0f, Math.min(45.0f, angle));
        // Sync contract (prevents client/server angle desync & visual stutter):
        // 1. setChanged() marks the BE dirty so it saves and re-ticks.
        // 2. sendBlockUpdated(..., 3) triggers getUpdatePacket()/getUpdateTag()
        //    so the new angle is pushed to all tracking clients immediately.
        // Both are REQUIRED every time the angle changes.
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
        tag.putFloat("SteeringAngle", this.steeringAngle);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.steeringAngle = tag.getFloat("SteeringAngle");
        if (Float.isNaN(this.steeringAngle) || Float.isInfinite(this.steeringAngle)) {
            this.steeringAngle = 0.0f;
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