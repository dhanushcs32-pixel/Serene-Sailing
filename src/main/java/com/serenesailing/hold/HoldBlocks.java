package com.serenesailing.hold;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * Minecraft / Sable glue for {@link HoldScanner}: the single hull predicate, a {@link HoldScanner.Grid} over a
 * level, and the plot-bounds + pose to {@link HoldScanner.Frame} conversion. Same code path on both logical sides.
 */
public final class HoldBlocks {

    /** Ships whose plot is wider than this are not scanned. */
    public static final int MAX_PLOT_SPAN = 192;

    private HoldBlocks() {
    }

    /**
     * The one definition of "hull": any non-air block that renders. Waterlogged stairs, slabs and trapdoors are
     * hull; plain water (render shape INVISIBLE) and bubble columns are not.
     */
    public static boolean isHull(BlockState state) {
        return !state.isAir() && state.getRenderShape() != RenderShape.INVISIBLE;
    }

    /** {@link HoldScanner.Grid} over a level that never forces a chunk load. */
    public static final class LevelGrid implements HoldScanner.Grid {
        private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        private final Level level;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        /** The scanner walks columns, so consecutive queries almost always hit the same chunk. */
        private int cachedCx = Integer.MIN_VALUE, cachedCz = Integer.MIN_VALUE;
        private net.minecraft.world.level.chunk.LevelChunk cachedChunk;

        public LevelGrid(Level level) {
            this.level = level;
        }

        private BlockState state(int x, int y, int z) {
            final int cx = x >> 4, cz = z >> 4;
            if (cx != cachedCx || cz != cachedCz) {
                cachedCx = cx;
                cachedCz = cz;
                // getChunkNow never loads or generates a chunk; null = not loaded (treated as air)
                cachedChunk = level.getChunkSource().getChunkNow(cx, cz);
            }
            return cachedChunk == null ? AIR : cachedChunk.getBlockState(pos.set(x, y, z));
        }

        @Override
        public boolean isHull(int x, int y, int z) {
            return HoldBlocks.isHull(state(x, y, z));
        }

        @Override
        public double floorHeight(int x, int y, int z) {
            final BlockState state = state(x, y, z);
            if (state.isAir()) {
                return Double.NaN;
            }
            final VoxelShape shape = state.getCollisionShape(level, pos.set(x, y, z));
            return shape.isEmpty() ? Double.NaN : shape.max(Direction.Axis.Y);
        }
    }

    /**
     * Scans one sub-level in its plot space.
     *
     * @param pose     the pose to evaluate the waterline against (logical pose on both sides)
     * @param previous class of the previous scan, for hysteresis
     * @return null when the sub-level cannot be scanned right now (empty / unsynced plot, too big, rolled over);
     * callers keep their previous result in that case
     */
    @Nullable
    public static HoldScanner.Envelope scan(Level level, SubLevel sub, Pose3dc pose,
                                            HoldScanner.HullClass previous, Vector3d scratch) {
        final Prepared p = prepare(sub, pose, scratch);
        if (p == null) {
            return null;
        }
        return HoldScanner.scan(p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                new LevelGrid(level), p.frame(), previous);
    }

    /** A scannable plot: its block bounds and the local-to-world height map for the current pose. */
    public record Prepared(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, HoldScanner.Frame frame) {
    }

    /** Shared by both scan engines. Null when the plot cannot be scanned right now (empty, unsynced, too big, rolled over). */
    @Nullable
    public static Prepared prepare(SubLevel sub, Pose3dc pose, Vector3d scratch) {
        final LevelPlot plot = sub.getPlot();
        if (plot == null || pose == null) {
            return null;
        }
        final BoundingBox3ic bb = plot.getBoundingBox();
        final int minX = bb.minX(), minY = bb.minY(), minZ = bb.minZ();
        final int maxX = bb.maxX(), maxY = bb.maxY(), maxZ = bb.maxZ();
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return null; // empty / not synced yet
        }
        if (!plot.contains(minX, minZ) || !plot.contains(maxX, maxZ)) {
            return null; // EMPTY box sits at the origin, outside the plot
        }
        if (maxX - minX >= MAX_PLOT_SPAN || maxZ - minZ >= MAX_PLOT_SPAN) {
            return null;
        }
        final HoldScanner.Frame frame = frameOf(pose, minX, minY, minZ, scratch);
        if (frame == null) {
            return null;
        }
        return new Prepared(minX, minY, minZ, maxX, maxY, maxZ, frame);
    }

    /** The Y row of the local-to-world map from four pose transforms; null if the ship is tilted too far. */
    @Nullable
    public static HoldScanner.Frame frameOf(Pose3dc pose, int bx, int by, int bz, Vector3d tmp) {
        Vector3d p = pose.transformPosition(tmp.set(bx, by, bz));
        final double ox = p.x, oy = p.y, oz = p.z;
        p = pose.transformPosition(tmp.set(bx + 1, by, bz));
        final double exY = p.y - oy;
        p = pose.transformPosition(tmp.set(bx, by + 1, bz));
        final double eyX = p.x - ox, eyY = p.y - oy, eyZ = p.z - oz;
        p = pose.transformPosition(tmp.set(bx, by, bz + 1));
        final double ezY = p.y - oy;
        final double upLen = Math.sqrt(eyX * eyX + eyY * eyY + eyZ * eyZ);
        if (upLen <= 1.0e-6 || eyY / upLen < HoldScanner.MIN_UPRIGHT_COS) {
            return null;
        }
        return new HoldScanner.Frame(bx, by, bz, oy, exY, eyY, ezY);
    }
}

