package com.serenesailing.hold;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft glue for {@link HoldFlood}: snapshots the plot's blocks into a per-cell "which faces are watertight" byte,
 * runs the flood fill and converts it to an {@link HoldScanner.Envelope}.
 *
 * <p>Cost control: a BlockState is an immutable singleton, so the six face-coverage bits are computed once per state
 * and cached ({@link #FACE_MASKS}); a scan is then one chunk-section read per cell plus a map lookup, never a voxel shape
 * calculation per query. Chunks are only read if already loaded, never loaded.
 */
public final class HoldFloodBlocks {

    /** Vanilla sea level surface (the top of the water block at Y 62). */
    public static final double WATER_Y = 62.8889;
    /** Dead band in blocks around the waterline so the swell cannot flip surface cells between wet and dry. */
    public static final double HYSTERESIS = 0.25;
    /** Plots with a bounding box larger than this many cells are not analysed. */
    public static final long MAX_CELLS = 2_000_000L;

    /** Direction order of {@link HoldFlood.Grid#blocksFace}: -X, +X, -Y, +Y, -Z, +Z. */
    private static final Direction[] DIRS = {
            Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};

    private static final Map<BlockState, Byte> FACE_MASKS = new ConcurrentHashMap<>();
    private static boolean warnedMask;

    private HoldFloodBlocks() {
    }

    /** The analysis of one ship: the envelope the rest of the mod reads, and the raw result kept for hysteresis. */
    public record Outcome(HoldScanner.Envelope envelope, HoldFlood.Result result) {
    }

    @Nullable
    public static Outcome scan(Level level, SubLevel sub, Pose3dc pose, @Nullable HoldFlood.Result previous,
                               Vector3d scratch) {
        final HoldBlocks.Prepared p = HoldBlocks.prepare(sub, pose, scratch);
        if (p == null) {
            return null;
        }
        final int w = p.maxX() - p.minX() + 1, h = p.maxY() - p.minY() + 1, d = p.maxZ() - p.minZ() + 1;
        if ((long) w * h * d > MAX_CELLS) {
            return null;
        }
        final byte[] masks = snapshot(level, p, w, h, d);
        final HoldFlood.Grid grid = (x, y, z, dir) ->
                (masks[((y - p.minY()) * d + (z - p.minZ())) * w + (x - p.minX())] & (1 << dir)) != 0;
        final HoldFlood.Result result = HoldFlood.analyse(p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                grid, p.frame(), WATER_Y, HYSTERESIS, previous);
        final HoldScanner.Envelope env = HoldFloodEnvelope.build(p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                grid, result, p.frame());
        return new Outcome(env, result);
    }

    /** One byte per cell of the plot box: bit {@code dir} set = the block covers that face completely. Unloaded = open. */
    private static byte[] snapshot(Level level, HoldBlocks.Prepared p, int w, int h, int d) {
        final byte[] masks = new byte[w * h * d];
        for (int cz = p.minZ() >> 4; cz <= p.maxZ() >> 4; cz++) {
            for (int cx = p.minX() >> 4; cx <= p.maxX() >> 4; cx++) {
                final ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false); // never loads a chunk
                if (chunk == null) {
                    continue;
                }
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
                                masks[((y - p.minY()) * d + (z - p.minZ())) * w + (x - p.minX())] =
                                        faceMask(section.getBlockState(lx, ly, lz));
                            }
                        }
                    }
                }
            }
        }
        return masks;
    }

    /** Bit i set if this block completely covers face DIRS[i], i.e. water cannot pass through it. Cached per state. */
    static byte faceMask(BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        return FACE_MASKS.computeIfAbsent(state, HoldFloodBlocks::computeMask);
    }

    private static byte computeMask(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) {
            return 0; // water / lava inside the plot is a fluid, not a barrier
        }
        try {
            int mask = 0;
            for (int i = 0; i < 6; i++) {
                if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, DIRS[i])) {
                    mask |= 1 << i;
                }
            }
            return (byte) mask;
        } catch (Throwable t) {
            // A modded block that needs a real level to answer: fall back to "full block or nothing".
            if (!warnedMask) {
                warnedMask = true;
                com.serenesailing.SereneSailing.LOGGER.warn("[SereneSailing] Could not compute the face coverage of {}; "
                        + "treating it as {} for the flood fill.", state, state.canOcclude() ? "solid" : "open", t);
            }
            return (byte) (state.canOcclude() ? 0x3F : 0);
        }
    }
}
