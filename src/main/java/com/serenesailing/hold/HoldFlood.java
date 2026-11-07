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
    }

    /** Result: per cell of the (unpadded) box, whether it is DRY, plus a few totals. */
    public static final class Result {
        private final int minX, minY, minZ, width, height, depth;
        private final boolean[] dry;
        private final int dryCells, floodedCells, openCellsBelowWater;

        private Result(int minX, int minY, int minZ, int width, int height, int depth, boolean[] dry,
                       int dryCells, int floodedCells, int openCellsBelowWater) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.dry = dry;
            this.dryCells = dryCells;
            this.floodedCells = floodedCells;
            this.openCellsBelowWater = openCellsBelowWater;
        }

        /** Is this cell a trapped, dry air pocket below the waterline? */
        public boolean isDry(int x, int y, int z) {
            final int lx = x - minX, ly = y - minY, lz = z - minZ;
            return lx >= 0 && lx < width && ly >= 0 && ly < height && lz >= 0 && lz < depth
                    && dry[(ly * depth + lz) * width + lx];
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
        final int w = maxX - minX + 1, h = maxY - minY + 1, d = maxZ - minZ + 1;
        // Padded by one cell on every side: the padding ring is open sea (where it lies below the waterline).
        final int pw = w + 2, ph = h + 2, pd = d + 2;
        final boolean[] below = new boolean[pw * ph * pd];
        final boolean[] seen = new boolean[pw * ph * pd];
        final int[] queue = new int[pw * ph * pd];
        int head = 0, tail = 0, openBelow = 0;

        for (int py = 0; py < ph; py++) {
            for (int pz = 0; pz < pd; pz++) {
                for (int px = 0; px < pw; px++) {
                    final int x = minX + px - 1, y = minY + py - 1, z = minZ + pz - 1;
                    final int i = (py * pd + pz) * pw + px;
                    below[i] = frame.worldY(x + 0.5, y + 0.5, z + 0.5) < waterY;
                    final boolean ring = px == 0 || py == 0 || pz == 0 || px == pw - 1 || py == ph - 1 || pz == pd - 1;
                    if (ring && below[i]) {
                        seen[i] = true;
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
                if (inside && grid.blocksFace(x, y, z, dir)) {
                    continue;
                }
                if (nInside && grid.blocksFace(x + DX[dir], y + DY[dir], z + DZ[dir], dir ^ 1)) {
                    continue;
                }
                seen[ni] = true;
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
        return new Result(minX, minY, minZ, w, h, d, dry, dryCells, flooded, openBelow);
    }
}
