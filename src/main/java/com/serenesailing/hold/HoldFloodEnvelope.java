package com.serenesailing.hold;

import java.util.Arrays;

/**
 * Turns a {@link HoldFlood.Result} into the {@link HoldScanner.Envelope} that the Sable region, the depth planes, the
 * wave mask, the entity physics and the bilge purge already read, so none of them needs to know which engine ran.
 * Pure Java.
 *
 * <p>Per column (x, z) that holds at least one DRY cell:
 * <ul>
 *   <li>the sealed interval runs from the lowest dry cell up through the open air above the pocket, to the first solid
 *       cell (a deck or ceiling) or MAX_VOLUME_HEIGHT cells above the floor, whichever is lower. The top is therefore
 *       defined by the hull's own blocks and not by the waterline, so heave cannot change it. It covers the water
 *       surface and the wave crests above it;</li>
 *   <li>the column is part of the footprint (the flags the planes and the wave mask draw from).</li>
 * </ul>
 * Columns that are solid at the pocket's waterline level and touch a dry column (the hull wall, bulkheads) join the
 * footprint but get no interval: the wave mask needs the wall in its footprint (see HoldMaskGeometry), while the
 * physics volume must stay the air only. A flooded compartment contributes nothing at all.
 */
public final class HoldFloodEnvelope {

    private HoldFloodEnvelope() {
    }

    public static HoldScanner.Envelope build(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                             HoldFlood.Grid grid, HoldFlood.Result result, HoldScanner.Frame frame) {
        final int width = maxX - minX + 1;
        final int depth = maxZ - minZ + 1;
        final int[] portX = new int[depth];
        final int[] starboardX = new int[depth];
        final byte[] flags = new byte[width * depth];
        final int[] colLo = new int[width * depth];
        final int[] colHi = new int[width * depth];
        final int[] topDry = new int[width * depth];
        Arrays.fill(portX, -1);
        Arrays.fill(starboardX, -1);
        Arrays.fill(colLo, Integer.MAX_VALUE);
        Arrays.fill(colHi, Integer.MIN_VALUE);
        Arrays.fill(topDry, Integer.MIN_VALUE);

        boolean anyDry = false;
        for (int lz = minZ; lz <= maxZ; lz++) {
            for (int lx = minX; lx <= maxX; lx++) {
                int lo = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
                for (int ly = minY; ly <= maxY; ly++) {
                    if (result.isDry(lx, ly, lz)) {
                        lo = Math.min(lo, ly);
                        top = ly;
                    }
                }
                if (lo == Integer.MAX_VALUE) {
                    continue;
                }
                anyDry = true;
                int hi = top;
                final int cap = (int) Math.min(maxY, (long) lo + HoldScanner.MAX_VOLUME_HEIGHT);
                while (hi < cap && !isSolid(grid, lx, hi + 1, lz)) {
                    hi++;
                }
                final int i = (lz - minZ) * width + (lx - minX);
                colLo[i] = lo;
                colHi[i] = hi;
                topDry[i] = top;
                int f = HoldScanner.FLAG_SEALED;
                if (frame.worldY(lx + 0.5, lo + 1.0, lz + 0.5) < HoldScanner.LOWER_PLANE_FLOOR_MAX_Y) {
                    f |= HoldScanner.FLAG_LOWER;
                }
                flags[i] = (byte) f;
            }
        }

        if (!anyDry) {
            return HoldScanner.fromColumns(minX, minY, minZ, maxX, maxY, maxZ, HoldScanner.HullClass.FLAT_RAFT,
                    portX, starboardX, flags, colLo, colHi, 0);
        }

        // Footprint dilation: solid neighbours (walls, bulkheads, corners) of a dry column, judged at the level of the
        // pocket's top. An open neighbour (flooded water, air) is never added.
        final byte[] dilated = flags.clone();
        for (int lz = minZ; lz <= maxZ; lz++) {
            for (int lx = minX; lx <= maxX; lx++) {
                final int i = (lz - minZ) * width + (lx - minX);
                if (topDry[i] == Integer.MIN_VALUE) {
                    continue;
                }
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        final int nx = lx + dx, nz = lz + dz;
                        if ((dx == 0 && dz == 0) || nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                            continue;
                        }
                        final int ni = (nz - minZ) * width + (nx - minX);
                        if (flags[ni] == 0 && dilated[ni] == 0 && isSolid(grid, nx, topDry[i], nz)) {
                            dilated[ni] = (byte) HoldScanner.FLAG_SEALED;
                        }
                    }
                }
            }
        }

        int validRows = 0;
        for (int row = 0; row < depth; row++) {
            int first = -1, last = -1;
            for (int c = 0; c < width; c++) {
                if (dilated[row * width + c] != 0) {
                    if (first < 0) {
                        first = c;
                    }
                    last = c;
                }
            }
            if (first >= 0) {
                portX[row] = minX + first;
                starboardX[row] = minX + last;
                validRows++;
            }
        }
        return HoldScanner.fromColumns(minX, minY, minZ, maxX, maxY, maxZ, HoldScanner.HullClass.SUNKEN_HOLD,
                portX, starboardX, dilated, colLo, colHi, validRows);
    }

    /** A cell whose six faces are all blocked: a solid block as far as water is concerned. */
    private static boolean isSolid(HoldFlood.Grid grid, int x, int y, int z) {
        for (int dir = 0; dir < 6; dir++) {
            if (!grid.blocksFace(x, y, z, dir)) {
                return false;
            }
        }
        return true;
    }
}

