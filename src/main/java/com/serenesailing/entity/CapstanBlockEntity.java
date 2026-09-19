package com.serenesailing.entity;

import com.serenesailing.block.CapstanBlock;
import com.serenesailing.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Winch station for the anchor. Toggled by interacting with the block; while
 * anchored the vessel's planar drift and yaw rate are clamped to zero
 * (see {@code ServerPhysicsHandler}).
 *
 * <p>Also animates the mooring chain: on the server it raycasts straight down
 * to find the seabed and winches the chain out/in at a realistic speed; the
 * chain length is synced to clients (throttled) where {@code CapstanRenderer}
 * draws the deployed chain and anchor fluke.</p>
 */
public class CapstanBlockEntity extends BlockEntity {

    /** Chain winch speed [blocks/tick] (= 1.5 m/s at 20 tps). */
    private static final float WINCH_SPEED_PER_TICK = 0.075f;
    /** Maximum straight-down seabed search depth [blocks]. */
    private static final int MAX_SEABED_SEARCH = 48;

    private boolean anchored = false;
    /** Depth to the first solid, non-liquid block below the capstan. */
    private float seabedDepth = 0.0f;
    /** Chain length last tick, for partial-tick interpolation. */
    private float prevChainLength = 0.0f;
    /** Currently deployed chain length [blocks]. */
    private float chainLength = 0.0f;
    /** Throttle counter for server -> client chain sync while winching. */
    private int syncCounter = 0;

    public CapstanBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CAPSTAN.get(), pos, state);
    }

    public boolean isAnchored() {
        return this.anchored;
    }

    /** Alias used by the renderer: true once the anchor is dropped. */
    public boolean isMoored() {
        return this.anchored;
    }

    /** Explicitly sets the anchor state (kept in sync with the blockstate). */
    public void setAnchored(boolean anchored) {
        this.anchored = anchored;
        setChanged();
    }

    /** Toggles the anchor, updates the blockstate visual and syncs to clients. */
    public boolean toggleAnchor() {
        this.anchored = !this.anchored;
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            if (state.hasProperty(CapstanBlock.ANCHORED)) {
                level.setBlock(worldPosition,
                        state.setValue(CapstanBlock.ANCHORED, this.anchored), 3);
            } else {
                level.sendBlockUpdated(worldPosition, state, state, 3);
            }
        }
        return this.anchored;
    }

    /**
     * Per-tick chain simulation (both sides, via the block's ticker):
     * advances {@link #chainLength} toward its target at the winch speed.
     * On the server this raycasts the seabed while moored and throttled-syncs
     * the length to tracking clients; on the client it animates toward the
     * last synced target between packets.
     */
    public void tick() {
        this.prevChainLength = this.chainLength;
        if (level == null) {
            return;
        }

        if (!level.isClientSide && this.anchored) {
            this.seabedDepth = findSeabedDepth();
        }
        float target = this.anchored ? this.seabedDepth : 0.0f;
        float diff = target - this.chainLength;
        if (Math.abs(diff) <= WINCH_SPEED_PER_TICK) {
            this.chainLength = target;
        } else {
            this.chainLength += Math.signum(diff) * WINCH_SPEED_PER_TICK;
        }

        // Throttled sync (~5 Hz) while the winch is actually moving.
        if (!level.isClientSide && Math.abs(diff) > 0.001f
                && ++this.syncCounter % 4 == 0) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Straight-down scan: depth to the first solid ground block below the
     * capstan. Air, fluids (water) and non-solid blocks are ignored; a block
     * qualifies when it blocks motion or its UP face is sturdy. The returned
     * depth targets the block's TOP surface (with a small inset) so the anchor
     * fluke rests neatly on the seabed instead of phasing into it.
     */
    private float findSeabedDepth() {
        BlockPos.MutableBlockPos checkPos = this.worldPosition.mutable();
        float foundDepth = (float) MAX_SEABED_SEARCH;

        for (int d = 1; d <= MAX_SEABED_SEARCH; d++) {
            checkPos.setY(this.worldPosition.getY() - d);
            BlockState state = this.level.getBlockState(checkPos);

            // Ignore air, fluids/water, and non-solid blocks
            if (!state.isAir() && state.getFluidState().isEmpty()
                    && (state.blocksMotion()
                        || state.isFaceSturdy(this.level, checkPos, Direction.UP))) {
                // The top surface of pos.below(d) is (d - 1.0f) blocks below
                // the capstan base; small offset so the anchor sits neatly
                // on top of the block.
                foundDepth = (float) d - 0.15f;
                break;
            }
        }
        return foundDepth;
    }

    /** @return chain length interpolated between ticks for smooth rendering. */
    public float getInterpolatedChainLength(float partialTick) {
        return Mth.lerp(partialTick, this.prevChainLength, this.chainLength);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Anchored", this.anchored);
        tag.putFloat("SeabedDepth", this.seabedDepth);
        tag.putFloat("ChainLength", this.chainLength);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.anchored = tag.getBoolean("Anchored");
        this.seabedDepth = tag.getFloat("SeabedDepth");
        this.chainLength = tag.getFloat("ChainLength");
        // Snap interpolation state so the chain does not visibly rewind.
        this.prevChainLength = this.chainLength;
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

