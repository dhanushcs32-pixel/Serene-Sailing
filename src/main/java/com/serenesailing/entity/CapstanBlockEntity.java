package com.serenesailing.entity;

import com.serenesailing.SereneSailing;
import com.serenesailing.block.CapstanBlock;
import com.serenesailing.event.ServerPhysicsHandler;
import com.serenesailing.registry.ModBlockEntities;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import org.joml.Vector3d;
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

    /** Anchor free-drop payout speed [blocks/tick] (~5 m/s in water). */
    private static final float DROP_SPEED_PER_TICK = 0.25f;
    /** Chain heave-in winch speed [blocks/tick] (~1.6 m/s at 20 tps). */
    private static final float HEAVE_SPEED_PER_TICK = 0.08f;
    /** Maximum straight-down seabed search depth [blocks]. */
    private static final int MAX_SEABED_SEARCH = 48;

    private boolean anchored = false;
    /** Depth to the first solid, non-liquid block below the capstan. */
    private float seabedDepth = 0.0f;
    /** Chain length last tick, for partial-tick interpolation. */
    private float prevChainLength = 0.0f;
    /** Currently deployed chain length [blocks]. */
    private float chainLength = 0.0f;
    /** Horizontal trailing offset (ship local X) of the anchor behind the capstan. */
    private float trailX = 0.0f;
    private float prevTrailX = 0.0f;
    /** Horizontal trailing offset (ship local Z) of the anchor behind the capstan. */
    private float trailZ = 0.0f;
    private float prevTrailZ = 0.0f;
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

    /** True once the dropped anchor has reached the seabed and bites into the bottom. */
    public boolean isHolding() {
        return this.anchored && (this.chainLength >= Math.max(0.5f, this.seabedDepth - 0.4f) || this.seabedDepth <= 0.5f);
    }

    public float getTrailX() {
        return this.trailX;
    }

    public float getTrailZ() {
        return this.trailZ;
    }

    public float getInterpolatedTrailX(float partialTick) {
        return Mth.lerp(partialTick, this.prevTrailX, this.trailX);
    }

    public float getInterpolatedTrailZ(float partialTick) {
        return Mth.lerp(partialTick, this.prevTrailZ, this.trailZ);
    }

    /**
     * Per-tick chain simulation (both sides, via the block's ticker):
     * advances {@link #chainLength} toward its target at realistic drop/heave speed.
     * On the server this raycasts the seabed, calculates hydrodynamic trailing
     * offset if the vessel is underway so the anchor takes a curved path and
     * lands a bit behind, and throttled-syncs state to tracking clients.
     */
    public void tick() {
        this.prevChainLength = this.chainLength;
        this.prevTrailX = this.trailX;
        this.prevTrailZ = this.trailZ;
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

                    // If the ship is moving, calculate hydrodynamic lag trailing offset
                    // so the anchor follows a curved path and lands behind the vessel
                    RigidBodyHandle body = RigidBodyHandle.of(subLevel);
                    Vector3d worldVel = (body != null && body.isValid())
                            ? new Vector3d(body.getLinearVelocity())
                            : ServerPhysicsHandler.getVesselVelocity(subLevel.getUniqueId());
                    Vector3d localVel = pose.transformNormalInverse(worldVel, new Vector3d());

                    // While dropping or before biting, the anchor trails behind opposite to motion
                    if (!isHolding()) {
                        float lagFactor = 1.2f; // meters of trailing lag per m/s of ship speed
                        float targetTrailX = (float) (-localVel.x * lagFactor);
                        float targetTrailZ = (float) (-localVel.z * lagFactor);
                        float maxTrail = Math.min(Math.max(1.0f, this.chainLength) * 0.75f, 15.0f);
                        float dist = (float) Math.sqrt(targetTrailX * targetTrailX + targetTrailZ * targetTrailZ);
                        if (dist > maxTrail && dist > 1.0e-4f) {
                            targetTrailX = (targetTrailX / dist) * maxTrail;
                            targetTrailZ = (targetTrailZ / dist) * maxTrail;
                        }
                        this.trailX += (targetTrailX - this.trailX) * 0.2f;
                        this.trailZ += (targetTrailZ - this.trailZ) * 0.2f;
                    }
                    // Once isHolding() is true, the anchor has bitten the seabed — trailX and trailZ stay fixed!
                }
            }

            if (this.syncCounter % 20 == 0) {
                SereneSailing.LOGGER.debug("[ANCHOR-DIAG] Dimension: {} | LocalPos: {} | OverworldY: {} | SeabedDepth: {} | ChainLength: {} | Trail: ({}, {})",
                        level.dimension().location(), this.worldPosition, overworldY, this.seabedDepth, this.chainLength, this.trailX, this.trailZ);
            }
        } else if (!level.isClientSide && !this.anchored) {
            // When weighing anchor, smoothly retract trailing displacement as chain pays in
            if (this.seabedDepth > 0.1f) {
                float retractRatio = Math.min(1.0f, this.chainLength / this.seabedDepth);
                this.trailX *= retractRatio;
                this.trailZ *= retractRatio;
            } else {
                this.trailX = 0.0f;
                this.trailZ = 0.0f;
            }
        }

        float target = this.anchored ? this.seabedDepth : 0.0f;
        float diff = target - this.chainLength;
        float step = diff > 0 ? DROP_SPEED_PER_TICK : HEAVE_SPEED_PER_TICK;
        if (Math.abs(diff) <= step) {
            this.chainLength = target;
        } else {
            this.chainLength += Math.signum(diff) * step;
        }

        // Throttled sync (~5 Hz) while the winch is actually moving or trail is shifting.
        if (!level.isClientSide && (Math.abs(diff) > 0.001f || Math.abs(this.trailX - this.prevTrailX) > 0.01f || Math.abs(this.trailZ - this.prevTrailZ) > 0.01f)
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
                // The plot's blocks and the water column under the ship live in this very level. (This used to swap in
                // resolveWeatherQueryLevel(), a Weather2 helper that can return the OVERWORLD for a ship in another
                // dimension: seabed depth was then read from, and could force-load, the wrong dimension.)
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
        tag.putFloat("TrailX", this.trailX);
        tag.putFloat("TrailZ", this.trailZ);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.anchored = tag.getBoolean("Anchored");
        this.seabedDepth = tag.getFloat("SeabedDepth");
        this.chainLength = tag.getFloat("ChainLength");
        this.trailX = tag.getFloat("TrailX");
        this.trailZ = tag.getFloat("TrailZ");

        if (Float.isNaN(this.seabedDepth) || Float.isInfinite(this.seabedDepth)) this.seabedDepth = 0.0f;
        if (Float.isNaN(this.chainLength) || Float.isInfinite(this.chainLength)) this.chainLength = 0.0f;
        if (Float.isNaN(this.trailX) || Float.isInfinite(this.trailX)) this.trailX = 0.0f;
        if (Float.isNaN(this.trailZ) || Float.isInfinite(this.trailZ)) this.trailZ = 0.0f;

        this.seabedDepth = Mth.clamp(this.seabedDepth, 0.0f, (float) MAX_SEABED_SEARCH);
        this.chainLength = Mth.clamp(this.chainLength, 0.0f, (float) MAX_SEABED_SEARCH);
        this.trailX = Mth.clamp(this.trailX, -15.0f, 15.0f);
        this.trailZ = Mth.clamp(this.trailZ, -15.0f, 15.0f);

        // Snap interpolation state so the chain does not visibly rewind.
        this.prevChainLength = this.chainLength;
        this.prevTrailX = this.trailX;
        this.prevTrailZ = this.trailZ;
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

