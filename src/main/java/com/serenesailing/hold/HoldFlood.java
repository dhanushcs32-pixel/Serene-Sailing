package com.serenesailing.hold;

import java.util.Arrays;

/**
 * Watertightness analysis of a ship: which parts of it can the sea reach? Pure Java (no Minecraft types).
 *
 * <p>The sea is a flood fill. Every cell of the ship's block grid that lies below the waterline is either
 * <ul>
 *   <li><b>FLOODED</b>: connected, through open faces and only below the waterline, to the open sea outside the ship
 *       (a breach at or below the waterline, a rim that is awash, a missing plank, a gunport below the water), or</li>
 *   <li><b>DRY</b> (a trapped air pocket): open space that the sea cannot reach (a sealed hold, a bilge compartment
 *       behind a bulkhead, a sealed cavity inside a hull).</li>
 * </ul>
 * Water must be hidden, and entities must not swim, exactly in the DRY cells. Because this is a per-compartment
 * property of the geometry itself, no ship needs to be classified as "raft" or "hold" and nothing needs to be tagged:
 * a flat raft simply has no dry cells, a sealed bowl has one big pocket, a bowl with a hole under the waterline has
 * none, and a bowl with a bulkhead and a hole in one half is flooded in that half only.
 *
 * <p>Movement between two neighbouring cells needs BOTH cells to leave the shared face open, which is how vanilla
 * fluids decide whether water passes (a face only blocks if the block covers it completely). The caller supplies that
 * per-face information through {@link Grid#blocksFace}.
 */
public final class HoldFlood {

    private HoldFlood() {
    }

    /** Face directions, matching {@link #blocksFace}: 0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z. */
    private static final int[] DX = {-1, 1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, -1, 1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, -1, 1};

    public interface Grid {
        /** True if the block in this cell completely covers the face in direction {@code dir} (0..5), so no water passes it. */
        boolean blocksFace(int x, int y, int z, int dir);

        /**
         * Do the blocks of cell (x, y, z) and of its neighbour in direction {@code dir} TOGETHER seal the face between
         * them? Vanilla fluids pass unless the union of both faces covers the boundary completely (see
         * {@link FaceCoverage}). The default only knows whole faces: sealed if either block covers it alone.
         */
        default boolean sealsBetween(int x, int y, int z, int dir) {
            return blocksFace(x, y, z, dir) || blocksFace(x + DX[dir], y + DY[dir], z + DZ[dir], dir ^ 1);
        }
    }

    /** Result: per cell of the (unpadded) box, whether it is DRY, plus a few totals. */
    public static final class Result {
        private final int minX, minY, minZ, width, height, depth;
        private final boolean[] dry;
        /** Padded by one cell on every side (see analyse): which cells were below the waterline in this scan. */
        private final boolean[] belowPadded;
        /** Padded: reached by the sea; and for every reached cell, the cell the sea came from. */
        private final boolean[] seenPadded;
        private final int[] parentPadded;
        private final int[] distPadded;
        private final int dryCells, floodedCells, openCellsBelowWater;

        private Result(int minX, int minY, int minZ, int width, int height, int depth, boolean[] dry,
                       boolean[] belowPadded, boolean[] seenPadded, int[] parentPadded, int[] distPadded,
                       int dryCells, int floodedCells, int openCellsBelowWater) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.dry = dry;
            this.belowPadded = belowPadded;
            this.seenPadded = seenPadded;
            this.parentPadded = parentPadded;
            this.distPadded = distPadded;
            this.dryCells = dryCells;
            this.floodedCells = floodedCells;
            this.openCellsBelowWater = openCellsBelowWater;
        }

        public boolean isDry(int x, int y, int z) {
            if (x < minX || x >= minX + width || y < minY || y >= minY + height || z < minZ || z >= minZ + depth) {
                return false;
            }
            return dry[((y - minY) * depth + (z - minZ)) * width + (x - minX)];
        }

        /** True if the cell (unpadded coords) was below the waterline during this scan; false outside the box. */
        public boolean wasBelow(int x, int y, int z) {
            final int px = x - minX + 1, py = y - minY + 1, pz = z - minZ + 1;
            return px >= 0 && px < width + 2 && py >= 0 && py < height + 2 && pz >= 0 && pz < depth + 2
                    && belowPadded[(py * (depth + 2) + pz) * (width + 2) + px];
        }

        private int padIndex(int x, int y, int z) {
            return ((y - minY + 1) * (depth + 2) + (z - minZ + 1)) * (width + 2) + (x - minX + 1);
        }

        public boolean isFlooded(int x, int y, int z) {
            if (x < minX || x >= minX + width || y < minY || y >= minY + height || z < minZ || z >= minZ + depth) {
                return false;
            }
            return seenPadded[padIndex(x, y, z)];
        }

        /** BFS steps from the open-sea padding ring, or -1 if not flooded / out of bounds. */
        public int floodDistance(int x, int y, int z) {
            if (x < minX || x >= minX + width || y < minY || y >= minY + height || z < minZ || z >= minZ + depth) {
                return -1;
            }
            return distPadded[padIndex(x, y, z)];
        }

        public int minX() { return minX; }
        public int minY() { return minY; }
        public int minZ() { return minZ; }
        public int width() { return width; }
        public int height() { return height; }
        public int depth() { return depth; }

        /**
         * How the sea gets to a flooded cell: the cells from this one back to the open sea, as x, y, z triples (first
         * triple = the cell itself, last = the sea cell just outside the box). Empty if the cell is not flooded.
         */
        public int[] pathToSea(int x, int y, int z) {
            if (x < minX || x >= minX + width || y < minY || y >= minY + height || z < minZ || z >= minZ + depth) {
                return new int[0];
            }
            int i = padIndex(x, y, z);
            if (!seenPadded[i]) {
                return new int[0];
            }
            final java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
            final int pw = width + 2, pd = depth + 2;
            int guard = 0;
            while (i >= 0 && guard++ < 1_000_000) {
                out.add(minX + (i % pw) - 1);
                out.add(minY + (i / (pw * pd)) - 1);
                out.add(minZ + ((i / pw) % pd) - 1);
                i = parentPadded[i];
            }
            final int[] arr = new int[out.size()];
            for (int k = 0; k < arr.length; k++) {
                arr[k] = out.get(k);
            }
            return arr;
        }

        /**
         * The flooded cell furthest from the open sea by BFS travel steps (ties broken by horizontal margin from the
         * plot sides). This avoids picking open-ocean cells underneath the hull at y = minY (which are only 1 step
         * from the bottom padding face) and instead targets the interior of a breached hold.
         */
        public int[] deepestFloodedCell() {
            int bestDist = -1, bestMargin = -1, bx = 0, by = 0, bz = 0;
            for (int y = minY; y < minY + height; y++) {
                for (int z = minZ; z < minZ + depth; z++) {
                    for (int x = minX; x < minX + width; x++) {
                        final int idx = padIndex(x, y, z);
                        if (!seenPadded[idx]) {
                            continue;
                        }
                        final int steps = distPadded[idx];
                        final int margin = Math.min(Math.min(x - minX, minX + width - 1 - x),
                                Math.min(z - minZ, minZ + depth - 1 - z));
                        if (steps > bestDist || (steps == bestDist && margin > bestMargin)) {
                            bestDist = steps;
                            bestMargin = margin;
                            bx = x;
                            by = y;
                            bz = z;
                        }
                    }
                }
            }
            return bestDist < 0 ? null : new int[]{bx, by, bz};
        }

        public int dryCells() { return dryCells; }
        public int floodedCells() { return floodedCells; }
        public int openCellsBelowWater() { return openCellsBelowWater; }
        public boolean hasDryPocket() { return dryCells > 0; }
    }

    /**
     * @param frame   local-to-world height map (see {@link HoldScanner.Frame})
     * @param waterY  world height of the still sea surface (62.8889 for vanilla sea level)
     */
    public static Result analyse(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                 Grid grid, HoldScanner.Frame frame, double waterY) {
        return analyse(minX, minY, minZ, maxX, maxY, maxZ, grid, frame, waterY, 0.0, null);
    }

    /**
     * As above, with hysteresis so the swell cannot flip surface cells between wet and dry every scan.
     *
     * @param hysteresis half-width in blocks of the dead band around the waterline: a cell that was below the
     *                   waterline in {@code previous} stays below until its centre is {@code hysteresis} above the
     *                   surface, and a cell that was above stays above until its centre is {@code hysteresis} below it
     * @param previous   the result of the previous scan of the same ship, or null
     */
    public static Result analyse(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                 Grid grid, HoldScanner.Frame frame, double waterY,
                                 double hysteresis, Result previous) {
        final int w = maxX - minX + 1, h = maxY - minY + 1, d = maxZ - minZ + 1;
        // Padded by one cell on every side: the padding ring is open sea (where it lies below the waterline).
        final int pw = w + 2, ph = h + 2, pd = d + 2;
        final boolean[] below = new boolean[pw * ph * pd];
        final boolean[] seen = new boolean[pw * ph * pd];
        final int[] parent = new int[pw * ph * pd];            // BFS tree: how the sea reached each cell (-1 = a seed)
        Arrays.fill(parent, -1);
        final int[] dist = new int[pw * ph * pd];
        Arrays.fill(dist, -1);
        final int[] queue = new int[pw * ph * pd];
        int head = 0, tail = 0, openBelow = 0;

        for (int py = 0; py < ph; py++) {
            for (int pz = 0; pz < pd; pz++) {
                for (int px = 0; px < pw; px++) {
                    final int x = minX + px - 1, y = minY + py - 1, z = minZ + pz - 1;
                    final int i = (py * pd + pz) * pw + px;
                    // A cell [y, y+1] intersects the ocean whenever its bottom lies below the water surface.
                    // One-sided upward hysteresis ensures any cell actually intersecting waterY is caught immediately
                    // (never locked out on shallow-draft hulls), while preventing upward heave from flickering it off.
                    final double threshold = (previous != null && hysteresis > 0.0 && previous.wasBelow(x, y, z))
                            ? waterY + hysteresis
                            : waterY;
                    below[i] = frame.worldY(x + 0.5, y, z + 0.5) < threshold;
                    final boolean ring = px == 0 || py == 0 || pz == 0 || px == pw - 1 || py == ph - 1 || pz == pd - 1;
                    if (ring && below[i]) {
                        seen[i] = true;
                        dist[i] = 0;
                        queue[tail++] = i;
                    }
                }
            }
        }

        while (head < tail) {
            final int i = queue[head++];
            final int px = i % pw, pz = (i / pw) % pd, py = i / (pw * pd);
            final int x = minX + px - 1, y = minY + py - 1, z = minZ + pz - 1;
            final boolean inside = px > 0 && py > 0 && pz > 0 && px < pw - 1 && py < ph - 1 && pz < pd - 1;
            for (int dir = 0; dir < 6; dir++) {
                final int npx = px + DX[dir], npy = py + DY[dir], npz = pz + DZ[dir];
                if (npx < 0 || npy < 0 || npz < 0 || npx >= pw || npy >= ph || npz >= pd) {
                    continue;
                }
                final int ni = (npy * pd + npz) * pw + npx;
                if (seen[ni] || !below[ni]) {
                    continue; // water does not climb above its own surface
                }
                final boolean nInside = npx > 0 && npy > 0 && npz > 0 && npx < pw - 1 && npy < ph - 1 && npz < pd - 1;
                final boolean sealed;
                if (inside && nInside) {
                    sealed = grid.sealsBetween(x, y, z, dir);
                } else if (inside) {
                    sealed = grid.blocksFace(x, y, z, dir);          // the other side is open sea
                } else if (nInside) {
                    sealed = grid.blocksFace(x + DX[dir], y + DY[dir], z + DZ[dir], dir ^ 1);
                } else {
                    sealed = false;
                }
                if (sealed) {
                    continue;
                }
                seen[ni] = true;
                parent[ni] = i;
                dist[ni] = dist[i] + 1;
                queue[tail++] = ni;
            }
        }

        final boolean[] dry = new boolean[w * h * d];
        int dryCells = 0, flooded = 0;
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    final int pi = ((y + 1) * pd + (z + 1)) * pw + (x + 1);
                    if (!below[pi]) {
                        continue;
                    }
                    final int wx = minX + x, wy = minY + y, wz = minZ + z;
                    boolean allBlocked = true;
                    for (int dir = 0; dir < 6 && allBlocked; dir++) {
                        allBlocked = grid.blocksFace(wx, wy, wz, dir);
                    }
                    if (allBlocked) {
                        continue; // a solid block, neither wet nor dry
                    }
                    openBelow++;
                    if (seen[pi]) {
                        flooded++;
                    } else {
                        dry[(y * d + z) * w + x] = true;
                        dryCells++;
                    }
                }
            }
        }
        return new Result(minX, minY, minZ, w, h, d, dry, below, seen, parent, dist, dryCells, flooded, openBelow);
    }
}
