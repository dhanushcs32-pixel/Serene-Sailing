package com.serenesailing.hold;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft glue for {@link HoldFlood}: snapshots the plot's blocks into a per-cell "which faces are watertight" byte,
 * runs the flood fill and converts it to an {@link HoldScanner.Envelope}.
 *
 * <p>Cost control: a BlockState is an immutable singleton, so the six face-coverage bits are computed once per state
 * and cached ({@link #FACE_INFO}); a scan is then one chunk-section read per cell plus a map lookup, never a voxel shape
 * calculation per query. Chunks are only read if already loaded, never loaded.
 */
public final class HoldFloodBlocks {

    public static final double WATER_Y = 62.8889;
    /** Half-width in blocks of the waterline dead band (see {@link HoldFlood#analyse}). */
    public static final double HYSTERESIS = 0.35;
    /** Plots with a bounding box larger than this many cells are not analysed. */
    public static final long MAX_CELLS = 2_000_000L;

    /** Per BlockState: how its collision shape covers the six faces of its cell (see {@link FaceCoverage}). */
    private static final Map<BlockState, StateInfo> FACE_INFO = new ConcurrentHashMap<>();
    private static final StateInfo OPEN = new StateInfo(0, null);
    private static boolean warnedMask;

    private HoldFloodBlocks() {
    }

    public record Outcome(HoldScanner.Envelope envelope, HoldFlood.Result result) {
    }

    /**
     * @param previous previous scan of the same ship (or null), for waterline hysteresis
     * @return null if the plot cannot be scanned right now (unloaded, empty, too big, rolled over)
     */
    @Nullable
    public static Outcome scan(Level level, SubLevel sub, Pose3dc pose,
                               @Nullable HoldFlood.Result previous, Vector3d scratch) {
        final HoldBlocks.Prepared p = HoldBlocks.prepare(sub, pose, scratch);
        if (p == null) {
            return null;
        }
        final int w = p.maxX() - p.minX() + 1, h = p.maxY() - p.minY() + 1, d = p.maxZ() - p.minZ() + 1;
        if ((long) w * h * d > MAX_CELLS) {
            return null;
        }
        final StateInfo[] snap = snapshot(level, sub.getPlot(), p, w, h, d);
        if (snap == null) {
            return null; // plot chunks not loaded/synced yet: defer rather than treating missing chunks as holes
        }
        final HoldFlood.Grid grid = new PlotGrid(snap, p.minX(), p.minY(), p.minZ(), w, d);
        final HoldFlood.Result result = HoldFlood.analyse(p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                grid, p.frame(), WATER_Y, HYSTERESIS, previous);
        final HoldScanner.Envelope env = HoldFloodEnvelope.build(p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                grid, result, p.frame());
        return new Outcome(env, result);
    }

    /** One entry per cell of the plot box: the face coverage of its block; null if no plot chunks are loaded yet. */
    @Nullable
    private static StateInfo[] snapshot(Level level, @Nullable LevelPlot plot, HoldBlocks.Prepared p, int w, int h, int d) {
        final Map<Long, LevelChunk> plotChunks = new HashMap<>();
        if (plot != null) {
            for (final PlotChunkHolder holder : plot.getLoadedChunks()) {
                final LevelChunk c = holder.getChunk();
                if (c != null) {
                    plotChunks.put(c.getPos().toLong(), c);
                }
            }
        }
        if (plot != null && plotChunks.isEmpty()) {
            return null; // plot has not loaded/synced any chunks yet
        }
        final StateInfo[] cells = new StateInfo[w * h * d];
        int chunksRead = 0;
        for (int cz = p.minZ() >> 4; cz <= p.maxZ() >> 4; cz++) {
            for (int cx = p.minX() >> 4; cx <= p.maxX() >> 4; cx++) {
                LevelChunk chunk = plotChunks.get(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
                if (chunk == null) {
                    chunk = level.getChunkSource().getChunkNow(cx, cz); // never loads a chunk
                }
                if (chunk == null) {
                    // Empty corner chunks on L-shaped/diagonal plots may never be allocated; treat as open.
                    continue;
                }
                chunksRead++;
                final LevelChunkSection[] sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    final LevelChunkSection section = sections[si];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    final int baseY = chunk.getSectionYFromSectionIndex(si) << 4;
                    if (baseY + 15 < p.minY() || baseY > p.maxY()) {
                        continue;
                    }
                    for (int ly = 0; ly < 16; ly++) {
                        final int y = baseY + ly;
                        if (y < p.minY() || y > p.maxY()) {
                            continue;
                        }
                        for (int lz = 0; lz < 16; lz++) {
                            final int z = (cz << 4) + lz;
                            if (z < p.minZ() || z > p.maxZ()) {
                                continue;
                            }
                            for (int lx = 0; lx < 16; lx++) {
                                final int x = (cx << 4) + lx;
                                if (x < p.minX() || x > p.maxX()) {
                                    continue;
                                }
                                final BlockState state = section.getBlockState(lx, ly, lz);
                                if (!state.isAir()) {
                                    final StateInfo info = info(state);
                                    if (info != OPEN) {
                                        cells[((y - p.minY()) * d + (z - p.minZ())) * w + (x - p.minX())] = info;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return chunksRead == 0 ? null : cells;
    }

    /** Face coverage of one block state: which faces it covers completely alone, and the full 16 x 16 coverage of each face. */
    static final class StateInfo {
        /** Bit f set if the block covers face f (0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z) completely on its own. */
        final int fullMask;
        /** Per-face 16 x 16 coverage, or null if the block covers nothing. */
        final long[][] faces;

        StateInfo(int fullMask, long[][] faces) {
            this.fullMask = fullMask;
            this.faces = faces;
        }
    }

    /** The flood fill's view of a plot: pairs of neighbouring blocks seal a boundary if their faces TOGETHER cover it. */
    private static final class PlotGrid implements HoldFlood.Grid {
        private static final int[] DX = {-1, 1, 0, 0, 0, 0}, DY = {0, 0, -1, 1, 0, 0}, DZ = {0, 0, 0, 0, -1, 1};
        private final StateInfo[] cells;
        private final int minX, minY, minZ, w, d;

        PlotGrid(StateInfo[] cells, int minX, int minY, int minZ, int w, int d) {
            this.cells = cells;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.w = w;
            this.d = d;
        }

        private StateInfo at(int x, int y, int z) {
            final StateInfo info = cells[((y - minY) * d + (z - minZ)) * w + (x - minX)];
            return info == null ? OPEN : info;
        }

        @Override
        public boolean blocksFace(int x, int y, int z, int dir) {
            return ((at(x, y, z).fullMask >> dir) & 1) != 0;
        }

        @Override
        public boolean sealsBetween(int x, int y, int z, int dir) {
            final StateInfo a = at(x, y, z);
            final StateInfo b = at(x + DX[dir], y + DY[dir], z + DZ[dir]);
            if (((a.fullMask >> dir) & 1) != 0 || ((b.fullMask >> (dir ^ 1)) & 1) != 0) {
                return true;
            }
            return a.faces != null && b.faces != null && FaceCoverage.unionIsFull(a.faces[dir], b.faces[dir ^ 1]);
        }
    }

    static StateInfo info(BlockState state) {
        if (state.isAir()) {
            return OPEN;
        }
        return FACE_INFO.computeIfAbsent(state, HoldFloodBlocks::computeInfo);
    }

    /**
     * Collision shape, as vanilla water uses it ({@code FlowingFluid#canPassThroughWall} merges the two blocks'
     * collision-shape faces), evaluated without a level; block states determine the shape of nearly every block.
     */
    private static StateInfo computeInfo(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) {
            return OPEN; // water / lava inside the plot is a fluid, not a barrier
        }
        try {
            final VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) {
                return OPEN;
            }
            final List<AABB> aabbs = shape.toAabbs();
            final double[][] boxes = new double[aabbs.size()][];
            for (int i = 0; i < boxes.length; i++) {
                final AABB b = aabbs.get(i);
                boxes[i] = new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
            }
            final long[][] faces = FaceCoverage.fromBoxes(boxes);
            boolean any = false;
            for (final long[] f : faces) {
                for (final long word : f) {
                    any |= word != 0L;
                }
            }
            return any ? new StateInfo(FaceCoverage.fullMask(faces), faces) : OPEN;
        } catch (Throwable t) {
            // A modded block that needs a real level to answer: fall back to "full block or nothing".
            if (!warnedMask) {
                warnedMask = true;
                com.serenesailing.SereneSailing.LOGGER.warn("[SereneSailing] Could not compute the collision faces of {}; "
                        + "treating it as {} for the flood fill.", state, state.canOcclude() ? "solid" : "open", t);
            }
            return state.canOcclude() ? FULL : OPEN;
        }
    }

    private static final StateInfo FULL = new StateInfo(0b111111, FaceCoverage.fromBoxes(new double[][]{{0, 0, 0, 1, 1, 1}}));
}
