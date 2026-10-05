package com.serenesailing.entity;

import com.serenesailing.SereneSailing;
import com.serenesailing.block.CapstanBlock;
import com.serenesailing.event.ServerPhysicsHandler;
import com.serenesailing.registry.ModBlockEntities;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
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
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

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

    public float getSeabedDepth() {
        return this.seabedDepth;
    }

    public float getCurrentChainLength() {
        return this.chainLength;
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

        double overworldY = this.worldPosition.getY();
        if (!level.isClientSide && this.anchored) {
            this.seabedDepth = findSeabedDepth();
            if (level instanceof ServerLevel serverLevel) {
                ServerSubLevel subLevel = resolveSubLevel(serverLevel);
                if (subLevel != null) {
                    Pose3dc pose = subLevel.logicalPose();
                    Vec3 localCenter = Vec3.atCenterOf(this.worldPosition);
                    Vec3 worldCenter = pose.transformPosition(localCenter);
                    overworldY = worldCenter.y;
                }
            }

            if (this.syncCounter % 20 == 0) {
                SereneSailing.LOGGER.info("[ANCHOR-DIAG] Dimension: {} | LocalPos: {} | OverworldY: {} | SeabedDepth: {} | ChainLength: {}",
                        level.dimension().location(), this.worldPosition, overworldY, this.seabedDepth, this.chainLength);
            }
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
     * Resolves the {@link ServerSubLevel} containing this capstan if assembled on a vessel.
     */
    private ServerSubLevel resolveSubLevel(ServerLevel serverLevel) {
        SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        if (container instanceof ServerSubLevelContainer serverContainer) {
            LevelPlot plot = serverContainer.getPlot(new ChunkPos(this.worldPosition));
            if (plot != null && plot.getSubLevel() instanceof ServerSubLevel ssl) {
                return ssl;
            } else {
                for (ServerSubLevel ssl : serverContainer.getAllSubLevels()) {
                    LevelPlot subPlot = ssl.getPlot();
                    if (subPlot != null && subPlot.contains(new ChunkPos(this.worldPosition))) {
                        return ssl;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Straight-down scan: continuous depth from the capstan's Overworld Y
     * to the top surface of the first solid seabed block below it.
     *
     * <p>Uses the vessel's exact floating-point world Y (including draft
     * fraction) so the returned payout length places the anchor flush on
     * the seabed regardless of how high the hull bobs above the block grid.</p>
     */
    private float findSeabedDepth() {
        if (this.level == null) return 0.0f;

        // --- Resolve Overworld level & continuous capstan world position ---
        Level overworldLevel = this.level;
        double capstanWorldY;
        int overworldX, overworldZ;

        if (this.level instanceof ServerLevel serverLevel) {
            ServerSubLevel subLevel = resolveSubLevel(serverLevel);
            if (subLevel != null) {
                ServerLevel parentLevel = ServerPhysicsHandler.resolveWeatherQueryLevel(serverLevel);
                if (parentLevel != null) {
                    overworldLevel = parentLevel;
                }
                Pose3dc pose = subLevel.logicalPose();
                Vec3 localCenter = Vec3.atCenterOf(this.worldPosition);
                Vec3 worldCenter = pose.transformPosition(localCenter);
                capstanWorldY = worldCenter.y;
                overworldX = (int) Math.floor(worldCenter.x);
                overworldZ = (int) Math.floor(worldCenter.z);
            } else {
                capstanWorldY = this.worldPosition.getY() + 0.5;
                overworldX = this.worldPosition.getX();
                overworldZ = this.worldPosition.getZ();
            }
        } else {
            return 0.0f;
        }

        // Start scanning 1 block below the capstan to clear ship hull/deck
        int scanStartY = (int) Math.floor(capstanWorldY) - 1;

        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

        for (int d = 0; d <= MAX_SEABED_SEARCH; d++) {
            scanPos.set(overworldX, scanStartY - d, overworldZ);
            BlockState state = overworldLevel.getBlockState(scanPos);
            FluidState fluid = overworldLevel.getFluidState(scanPos);

            if (!state.isAir() && fluid.isEmpty() && state.blocksMotion()) {
                // Top surface of the solid seabed block is at scanPos.getY() + 1.0.
                // Measure true continuous distance from the capstan drum down:
                double seabedTopY = (double) scanPos.getY() + 1.0;
                double trueDistance = capstanWorldY - seabedTopY;

                // Small buffer (0.02) so the crossbar bottom rests flush
                return (float) Math.max(0.0, trueDistance - 0.02);
            }
        }

        return (float) MAX_SEABED_SEARCH; // Open deep sea / Abyss
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

