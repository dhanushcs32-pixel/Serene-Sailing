package com.serenesailing.hold;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * Hull / sunken-hold scanner. Pure Java: no Minecraft, Sable or NeoForge imports, so it can be unit tested
 * on a plain block grid ({@link #scan(int, int, int, int, int, int, IntPredicate, Frame, HullClass)}).
 *
 * <p>One scan produces one {@link Envelope} in sub-level local (plot) space. Everything that cares about the
 * hold uses that single envelope: the Sable water-occlusion region, the entity physics fallback, the bilge
 * purge, the wave-grid mask and the fallback depth planes. The renderer, the helper and the purge therefore
 * can no longer disagree about what "solid" or "inside the hull" means.
 *
 * <p>The scan is a row (constant local Z) scanline:
 * <ol>
 *   <li>Find the outer wall columns of the row ({@code portX} / {@code starboardX}). A column is a wall when it
 *       holds a hull block itself and a 3-column window around it holds enough hull blocks in the waterline
 *       band; the wall top is the highest block top over the same window. A tumblehome step (low outer rib,
 *       taller second column) or a gunport column holding only a trapdoor can therefore never reject or
 *       shrink a row.</li>
 *   <li>Every column in {@code [portX .. starboardX]} is sealed, whatever stands in it (ledges, stairs, masts,
 *       overhangs). Interior fixtures can no longer punch holes in the envelope.</li>
 *   <li>Rows are classified: a {@link HullClass#SUNKEN_HOLD} needs a basin whose floor lies well below the
 *       vanilla surface, with hysteresis so a bobbing ship never flickers.</li>
 * </ol>
 */
public final class HoldScanner {

    private HoldScanner() {
    }

    // =========================================================================================
    //  TUNABLES (world Y unless stated otherwise)
    // =========================================================================================

    /** Interior floor top at/above this is raft-like. Also the threshold to ENTER "sunken hold". */
    public static final double RAFT_FLOOR_MIN_Y = 62.30;
    /** A hull floor exists below the surface. Also the threshold to STAY a "sunken hold" (hysteresis). */
    public static final double HOLD_FLOOR_MAX_Y = 62.65;
    /** The lower (61.895) depth plane is only emitted where the floor top is below this. */
    public static final double LOWER_PLANE_FLOOR_MAX_Y = 61.85;
    /**
     * A row is SEALED when enough of its interior has a hull floor below this height. Deliberately well above the
     * 62.89 surface: a floor that sits just under (or a hair over) the waterline, typically where the hull bottom
     * curves up at the stern, still carries a thin film of ocean water that must be hidden. Whether the vessel is a
     * hold at all is decided separately, by HOLD_FLOOR_MAX_Y / RAFT_FLOOR_MIN_Y, so a flat raft stays a raft.
     */
    public static final double SEAL_FLOOR_MAX_Y = 64.0;
    /**
     * A wall column must be a real stack: the same column holds at least this many hull blocks (over its whole local
     * height, so heave cannot change it) in this row or within WALL_SUPPORT_ROWS rows either side. A railing, ladder
     * or fender post hanging off the hull is a lone block and is rejected; a gunport column that is only a trapdoor
     * in one row is accepted because its neighbouring rows are full stacks.
     */
    public static final int WALL_MIN_COLUMN_BLOCKS = 3;
    public static final int WALL_SUPPORT_ROWS = 3;
    /** Perimeter walls must reach at least this high to count as rising above the surface. */
    public static final double WALL_RISE_MIN_Y = 63.2;
    /**
     * A column is a hull wall if enough hull blocks fall inside this band, which is a two-block slice AROUND THE
     * WATERLINE (surface 62.89), not a tall band above it. The sealed span must follow the hull where it meets the
     * sea: a hull that flares outwards as it rises (stepped bulwarks, overhanging gun decks) has wider columns higher
     * up, and a tall band picked those as "the wall", sealing a strip of open ocean beside the hull.
     */
    public static final double WALL_BAND_MIN_Y = 61.5;
    public static final double WALL_BAND_MAX_Y = 63.5;
    /** ...and its wall top is the highest block top found in this (taller) vertical slice. */
    public static final double WALL_TOP_BAND_MIN_Y = 61.5;
    public static final double WALL_TOP_BAND_MAX_Y = 65.5;
    /** Hull blocks needed in the band, summed over the wall window. */
    public static final int WALL_MIN_BAND_BLOCKS = 2;
    /** Wall window radius in columns: 1 = a 3-column window. */
    public static final int WALL_WINDOW_RADIUS = 1;
    /** The sealed volume rises at most this many local cells above the lowest hull floor of its row. */
    public static final int MAX_VOLUME_HEIGHT = 24;
    /** Rows needed before a vessel can be a hold at all. */
    public static final int MIN_HOLD_ROWS = 3;
    /** Basin cells (interior floor well below the surface) needed: at least this many... */
    public static final int MIN_BASIN_CELLS = 4;
    /** ...and at least this fraction of the floored interior cells. */
    public static final double BASIN_FRACTION = 0.20;
    /** A row needs this fraction of its interior cells to have a floor below the surface. */
    public static final double ROW_MIN_FLOORED_FRACTION = 0.25;
    /** cos(max tilt of local +Y from world +Y). Ships beyond ~31 degrees are skipped by the adapters. */
    public static final double MIN_UPRIGHT_COS = 0.85;
    private static final double FLOOR_SCAN_CEILING_Y = WALL_TOP_BAND_MAX_Y + 1.0;

    /** Cell flag: the column is sealed (water must not be drawn / simulated here). Set on the whole row span. */
    public static final int FLAG_SEALED = 1;
    /** Cell flag: a solid hull floor lies below the lower water layer plane. */
    public static final int FLAG_LOWER = 2;

    public enum HullClass { FLAT_RAFT, SUNKEN_HOLD }

    /** Numbers behind a row-scan classification, for diagnostics: why is this ship a raft or a hold? */
    public record ClassifyStats(int validRows, int flooredCells, int basinCells, int basinNeeded, boolean wasHold) {
        public String describe() {
            return "validRows=" + validRows + " (need " + MIN_HOLD_ROWS + ") flooredCells=" + flooredCells
                    + " basinCells=" + basinCells + " (need " + basinNeeded + ", " + (wasHold ? "staying" : "entering") + ")";
        }
    }

    // =========================================================================================
    //  INPUT TYPES
    // =========================================================================================

    /** Block access in sub-level local coordinates. */
    public interface Grid {
        /** Unified hull predicate: any non-air block that renders (so waterlogged stairs / slabs / trapdoors count). */
        boolean isHull(int x, int y, int z);

        /** Collision height of the block above its own floor in [0, 1], or NaN when it is not a walkable floor. */
        default double floorHeight(int x, int y, int z) {
            return isHull(x, y, z) ? 1.0 : Double.NaN;
        }
    }

    /**
     * The Y row of the affine local-to-world map of one sub-level: world Y of local point (lx, ly, lz) is
     * {@code oy + (lx-bx)*exY + (ly-by)*eyY + (lz-bz)*ezY}, i.e. {@code oy} is the world Y of the local corner
     * (bx, by, bz) and e*Y is how much one local unit along each axis raises the world Y.
     */
    public record Frame(double bx, double by, double bz, double oy, double exY, double eyY, double ezY) {

        /** An un-rotated ship whose local corner (bx, by, bz) sits at world height {@code oy}. */
        public static Frame level(double bx, double by, double bz, double oy) {
            return new Frame(bx, by, bz, oy, 0.0, 1.0, 0.0);
        }

        public double worldY(double lx, double ly, double lz) {
            return oy + (lx - bx) * exY + (ly - by) * eyY + (lz - bz) * ezY;
        }

        /** Local Y at which the column (lx, lz) crosses the given world height. */
        public double localY(double worldY, double lx, double lz) {
            return by + (worldY - oy - (lx - bx) * exY - (lz - bz) * ezY) / eyY;
        }
    }

    /** Index into a flat hull array, see {@link #scan(int, int, int, int, int, int, IntPredicate, Frame, HullClass)}. */
    public static int index(int minX, int minY, int minZ, int maxX, int maxZ, int x, int y, int z) {
        final int width = maxX - minX + 1;
        final int depth = maxZ - minZ + 1;
        return ((y - minY) * depth + (z - minZ)) * width + (x - minX);
    }

    // =========================================================================================
    //  RESULT
    // =========================================================================================

    /** Immutable-after-scan result, local (plot) space. Survives bobbing; rebuilt about once a second. */
    public static final class Envelope {
        private final int minX, minY, minZ, maxX, maxY, maxZ;
        private final int width, depth;
        private final HullClass hullClass;
        private final int[] portX;
        private final int[] starboardX;
        private final byte[] cellFlags;
        /** Per column (row-major, x fastest): sealed local Y interval, lo > hi when the column is not sealed. */
        private final int[] colLo;
        private final int[] colHi;
        private final int validRows;
        private final int volMinX, volMinY, volMinZ, volMaxX, volMaxY, volMaxZ;
        private final boolean hasVolume;
        private final int volumeHash;
        /** Row scanner only: the classification numbers (null for other engines). */
        private ClassifyStats stats;

        private Envelope(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, HullClass hullClass,
                         int[] portX, int[] starboardX, byte[] cellFlags, int[] colLo, int[] colHi, int validRows) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.width = maxX - minX + 1;
            this.depth = maxZ - minZ + 1;
            this.hullClass = hullClass;
            this.portX = portX;
            this.starboardX = starboardX;
            this.cellFlags = cellFlags;
            this.colLo = colLo;
            this.colHi = colHi;
            this.validRows = validRows;

            int vx0 = Integer.MAX_VALUE, vy0 = Integer.MAX_VALUE, vz0 = Integer.MAX_VALUE;
            int vx1 = Integer.MIN_VALUE, vy1 = Integer.MIN_VALUE, vz1 = Integer.MIN_VALUE;
            long h = 0xcbf29ce484222325L;
            boolean any = false;
            if (hullClass == HullClass.SUNKEN_HOLD) {
                for (int row = 0; row < depth; row++) {
                    if (portX[row] < 0) {
                        continue;
                    }
                    h = mix(h, row);
                    h = mix(h, portX[row]);
                    h = mix(h, starboardX[row]);
                    final int lz = minZ + row;
                    for (int lx = portX[row]; lx <= starboardX[row]; lx++) {
                        final int i = row * width + (lx - minX);
                        if (colLo[i] > colHi[i]) {
                            continue;
                        }
                        any = true;
                        h = mix(h, colLo[i]);
                        h = mix(h, colHi[i]);
                        vx0 = Math.min(vx0, lx);
                        vx1 = Math.max(vx1, lx);
                        vz0 = Math.min(vz0, lz);
                        vz1 = Math.max(vz1, lz);
                        vy0 = Math.min(vy0, colLo[i]);
                        vy1 = Math.max(vy1, colHi[i]);
                    }
                }
            }
            this.hasVolume = any;
            this.volMinX = vx0;
            this.volMinY = vy0;
            this.volMinZ = vz0;
            this.volMaxX = vx1;
            this.volMaxY = vy1;
            this.volMaxZ = vz1;
            int folded = (int) (h ^ (h >>> 32));
            this.volumeHash = any ? (folded == 0 ? 1 : folded) : 0;
        }

        private static long mix(long h, int v) {
            h ^= v;
            return h * 0x100000001b3L;
        }

        public HullClass hullClass() {
            return hullClass;
        }

        public boolean isSunken() {
            return hullClass == HullClass.SUNKEN_HOLD && hasVolume;
        }

        /** 0 when this is not a sunken hold; otherwise a non-zero hash of the sealed volume. */
        public int volumeHash() {
            return volumeHash;
        }

        public int validRows() {
            return validRows;
        }

        /** Row scanner: the numbers behind the raft / hold decision; null for envelopes from another engine. */
        public ClassifyStats stats() {
            return stats;
        }

        public boolean hasVolume() {
            return hasVolume;
        }

        // ---- scan bounds (the plot bounding box that was scanned) ----
        public int minX() { return minX; }
        public int minY() { return minY; }
        public int minZ() { return minZ; }
        public int maxX() { return maxX; }
        public int maxY() { return maxY; }
        public int maxZ() { return maxZ; }
        public int width() { return width; }
        public int depth() { return depth; }
        public double centerX() { return (minX + maxX + 1) * 0.5; }
        public double centerZ() { return (minZ + maxZ + 1) * 0.5; }

        // ---- tight bounds of the sealed volume (only meaningful when hasVolume()) ----
        public int volumeMinX() { return volMinX; }
        public int volumeMinY() { return volMinY; }
        public int volumeMinZ() { return volMinZ; }
        public int volumeMaxX() { return volMaxX; }
        public int volumeMaxY() { return volMaxY; }
        public int volumeMaxZ() { return volMaxZ; }

        // ---- per-row data ----
        /** First hull-wall column of the row (row = lz - minZ), or -1 if the row is not part of a hold. */
        public int portX(int row) { return portX[row]; }
        /** Last hull-wall column of the row, or -1 if the row is not part of a hold. */
        public int starboardX(int row) { return starboardX[row]; }

        /** FLAG_SEALED / FLAG_LOWER bits of a cell (0 outside the row span). */
        public int flags(int row, int lx) {
            return cellFlags[row * width + (lx - minX)];
        }

        /** Lowest sealed local Y of the column, or Integer.MAX_VALUE if the column is not sealed. */
        public int columnLo(int lx, int lz) {
            return inScanBounds(lx, lz) ? colLo[(lz - minZ) * width + (lx - minX)] : Integer.MAX_VALUE;
        }

        /** Highest sealed local Y of the column, or Integer.MIN_VALUE if the column is not sealed. */
        public int columnHi(int lx, int lz) {
            return inScanBounds(lx, lz) ? colHi[(lz - minZ) * width + (lx - minX)] : Integer.MIN_VALUE;
        }

        private boolean inScanBounds(int lx, int lz) {
            return lx >= minX && lx <= maxX && lz >= minZ && lz <= maxZ;
        }

        /** Is the local block cell inside the sealed hold volume (hull cells inside the span included)? */
        public boolean contains(int lx, int ly, int lz) {
            if (!hasVolume || !inScanBounds(lx, lz)) {
                return false;
            }
            final int i = (lz - minZ) * width + (lx - minX);
            return ly >= colLo[i] && ly <= colHi[i];
        }

        /**
         * Inside the sealed volume, or within {@code pad} columns of it horizontally. Used by the bilge purge: a
         * waterlogged block in the hull skin is a water SOURCE that keeps spilling flowing water into the hold.
         */
        public boolean containsPadded(int lx, int ly, int lz, int pad) {
            for (int dx = -pad; dx <= pad; dx++) {
                for (int dz = -pad; dz <= pad; dz++) {
                    if (contains(lx + dx, ly, lz + dz)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Like {@link #contains} but excludes the two outer wall columns of the row (the hull skin itself). */
        public boolean containsInterior(int lx, int ly, int lz) {
            if (!contains(lx, ly, lz)) {
                return false;
            }
            final int row = lz - minZ;
            return lx > portX[row] && lx < starboardX[row];
        }
    }

    /**
     * Builds an envelope from per-column data produced by another analysis (see {@link HoldFloodEnvelope}); the volume
     * hash and bounds are derived exactly as for a row scan, so every consumer reads it the same way.
     *
     * @param cellFlags FLAG_SEALED / FLAG_LOWER per column (footprint, which may be wider than the dry columns)
     * @param colLo     per column sealed interval, lo > hi where the column holds no sealed volume
     */
    public static Envelope fromColumns(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, HullClass hullClass,
                                       int[] portX, int[] starboardX, byte[] cellFlags, int[] colLo, int[] colHi,
                                       int validRows) {
        return new Envelope(minX, minY, minZ, maxX, maxY, maxZ, hullClass, portX, starboardX, cellFlags, colLo, colHi,
                validRows);
    }

    // =========================================================================================
    //  SCAN
    // =========================================================================================

    /**
     * Test-friendly overload: the hull is a flat predicate over the scan box. Use {@link #index} to build the
     * flat index; every hull cell is treated as a full block for floor purposes.
     */
    public static Envelope scan(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                IntPredicate hullAtIndex, Frame frame, HullClass previous) {
        final Grid grid = (x, y, z) -> x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ
                && hullAtIndex.test(index(minX, minY, minZ, maxX, maxZ, x, y, z));
        return scan(minX, minY, minZ, maxX, maxY, maxZ, grid, frame, previous);
    }

    /**
     * Row/column outer-hull scanline scan of one sub-level.
     *
     * @param previous the class of the previous scan of this sub-level (hysteresis), FLAT_RAFT if none
     */
    public static Envelope scan(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                Grid grid, Frame frame, HullClass previous) {
        final int width = maxX - minX + 1;
        final int depth = maxZ - minZ + 1;
        final int[] portX = new int[depth];
        final int[] starboardX = new int[depth];
        final byte[] flags = new byte[width * depth];
        final int[] colLo = new int[width * depth];
        final int[] colHi = new int[width * depth];
        Arrays.fill(portX, -1);
        Arrays.fill(starboardX, -1);
        Arrays.fill(colLo, Integer.MAX_VALUE);
        Arrays.fill(colHi, Integer.MIN_VALUE);

        // Wall columns are probed lazily from the two outer edges inwards, and a column's full height is only counted
        // for the one or two candidate wall columns per row: probing every column of every row costs hundreds of
        // thousands of block queries on a large ship.
        final RowProbe probe = new RowProbe(grid, frame, minX, minY, minZ, maxY, width, depth);
        final double[] floorLowestTop = new double[width];
        final double[] floorRunTop = new double[width];
        final int[] floorLowestY = new int[width];

        int validRows = 0, flooredCells = 0, basinEnter = 0, basinStay = 0;

        for (int lz = minZ; lz <= maxZ; lz++) {
            final int row = lz - minZ;

            probe.startRow(row);

            // Port side: first outer-hull wall column scanning inwards.
            int port = -1;
            for (int lx = minX; lx <= maxX; lx++) {
                if (probe.isWall(lx - minX)) {
                    port = lx;
                    break;
                }
            }
            if (port < 0) {
                continue;
            }
            // Starboard side: last outer-hull wall column.
            int star = -1;
            for (int lx = maxX; lx > port; lx--) {
                if (probe.isWall(lx - minX)) {
                    star = lx;
                    break;
                }
            }
            if (star < 0 || star - port < 2) {
                continue; // no interior between the walls
            }
            final double portTop = probe.windowTop(port - minX);
            final double starTop = probe.windowTop(star - minX);
            if (portTop < WALL_RISE_MIN_Y || starTop < WALL_RISE_MIN_Y) {
                continue; // perimeter does not rise above the surface
            }

            // Floor data for every column of the span (walls included).
            int floored = 0, sealable = 0, rowBasinEnter = 0, rowBasinStay = 0;
            for (int lx = port; lx <= star; lx++) {
                final int c = lx - minX;
                readFloorColumn(grid, frame, lx, lz, minY, maxY, floorLowestTop, floorRunTop, floorLowestY, c);
                if (lx > port && lx < star) {
                    if (floorLowestTop[c] < SEAL_FLOOR_MAX_Y) {
                        sealable++;
                    }
                    if (floorLowestTop[c] < HOLD_FLOOR_MAX_Y) {
                        floored++;
                    }
                    if (floorRunTop[c] < RAFT_FLOOR_MIN_Y) {
                        rowBasinEnter++;
                    }
                    if (floorRunTop[c] < HOLD_FLOOR_MAX_Y) {
                        rowBasinStay++;
                    }
                }
            }
            final int interior = star - port - 1;
            if (sealable < Math.max(1, (int) Math.ceil(interior * ROW_MIN_FLOORED_FRACTION))) {
                continue; // no hull floor near or below the surface between the walls
            }

            // The sealed volume is defined by LOCAL geometry only (hull cells), never by a world height, so heave
            // cannot change it: the water surface moving through a cell boundary must not re-hash the hold.
            //   bottom: the lowest solid hull cell of the row (the keel), the same for every column of the row, so
            //           ledges, overhangs, open bottoms and trapdoor-only gunport columns are all sealed down to it.
            //           (Whether a column counts as "floored" depends on the waterline and may flip with heave, so it
            //           must not influence the volume.)
            //   top:    the highest hull cell around the lower of the two outer walls, capped above the keel.
            int rowLo = Integer.MAX_VALUE;
            for (int lx = port; lx <= star; lx++) {
                rowLo = Math.min(rowLo, floorLowestY[lx - minX]);
            }
            final int hi = Math.min(Math.min(highestHullAround(grid, port, lz, minX, maxX, minY, maxY),
                    highestHullAround(grid, star, lz, minX, maxX, minY, maxY)), (long) rowLo + MAX_VOLUME_HEIGHT > maxY
                    ? maxY : rowLo + MAX_VOLUME_HEIGHT);
            if (hi < rowLo) {
                continue;
            }

            portX[row] = port;
            starboardX[row] = star;
            validRows++;
            flooredCells += floored;
            basinEnter += rowBasinEnter;
            basinStay += rowBasinStay;

            for (int lx = port; lx <= star; lx++) {
                final int c = lx - minX;
                final int i = row * width + c;
                int f = FLAG_SEALED;
                if (floorLowestTop[c] < LOWER_PLANE_FLOOR_MAX_Y) {
                    f |= FLAG_LOWER;
                }
                flags[i] = (byte) f;
                colLo[i] = rowLo;
                colHi[i] = hi;
            }
        }

        final HullClass cls = classify(validRows, flooredCells, basinEnter, basinStay, previous);
        final boolean wasHold = previous == HullClass.SUNKEN_HOLD;
        final ClassifyStats stats = new ClassifyStats(validRows, flooredCells, wasHold ? basinStay : basinEnter,
                Math.max(MIN_BASIN_CELLS, (int) Math.ceil(flooredCells * BASIN_FRACTION)), wasHold);
        if (cls != HullClass.SUNKEN_HOLD) {
            // A raft / flat deck owns no volume: drop everything so nothing downstream acts on it.
            Arrays.fill(portX, -1);
            Arrays.fill(starboardX, -1);
            Arrays.fill(flags, (byte) 0);
            Arrays.fill(colLo, Integer.MAX_VALUE);
            Arrays.fill(colHi, Integer.MIN_VALUE);
            final Envelope raft = new Envelope(minX, minY, minZ, maxX, maxY, maxZ, cls, portX, starboardX, flags, colLo, colHi, 0);
            raft.stats = stats;
            return raft;
        }
        final Envelope hold = new Envelope(minX, minY, minZ, maxX, maxY, maxZ, cls, portX, starboardX, flags, colLo, colHi, validRows);
        hold.stats = stats;
        return hold;
    }

    /**
     * Sunken hold vs flat raft. A hold needs perimeter walls rising above the surface (already required by
     * every valid row) and an interior basin: floor well below the surface. Entering needs the floor below
     * RAFT_FLOOR_MIN_Y, staying only needs it below HOLD_FLOOR_MAX_Y, so a bobbing ship never flickers.
     */
    static HullClass classify(int validRows, int flooredCells, int basinEnter, int basinStay, HullClass previous) {
        if (validRows < MIN_HOLD_ROWS) {
            return HullClass.FLAT_RAFT;
        }
        final int basin = previous == HullClass.SUNKEN_HOLD ? basinStay : basinEnter;
        final int needed = Math.max(MIN_BASIN_CELLS, (int) Math.ceil(flooredCells * BASIN_FRACTION));
        return basin >= needed ? HullClass.SUNKEN_HOLD : HullClass.FLAT_RAFT;
    }

    // ---- wall probing ----

    /** Hull-block count in the waterline band and highest block top in the wall slice of one column. */
    private static void probeWallColumn(Grid g, Frame f, int lx, int lz, int minY, int maxY,
                                        int[] bandCount, double[] wallTop, int c) {
        final double cx = lx + 0.5, cz = lz + 0.5;
        final int lo = Math.max(minY, (int) Math.floor(f.localY(WALL_BAND_MIN_Y - 0.5, cx, cz)) - 1);
        final int hi = Math.min(maxY, (int) Math.ceil(f.localY(WALL_TOP_BAND_MAX_Y + 0.5, cx, cz)) + 1);
        int blocks = 0;
        double top = Double.NEGATIVE_INFINITY;
        for (int ly = lo; ly <= hi; ly++) {
            if (!g.isHull(lx, ly, lz)) {
                continue;
            }
            final double centerY = f.worldY(cx, ly + 0.5, cz);
            if (centerY >= WALL_BAND_MIN_Y && centerY <= WALL_BAND_MAX_Y) {
                blocks++;
            }
            if (centerY >= WALL_TOP_BAND_MIN_Y && centerY <= WALL_TOP_BAND_MAX_Y) {
                top = Math.max(top, f.worldY(cx, ly + 1.0, cz));
            }
        }
        bandCount[c] = blocks;
        wallTop[c] = top;
    }

    /**
     * Lazy, memoised wall probing for one row at a time.
     *
     * <p>A wall column holds a hull block in the waterline band itself, and the 3-column window around it holds at least
     * WALL_MIN_BAND_BLOCKS of them. The own-block requirement keeps the envelope from growing outwards into open water
     * next to a wall; the window keeps a trapdoor-only gunport column from being skipped. On top of that the column must
     * be a real stack (WALL_MIN_COLUMN_BLOCKS hull blocks over its whole height) in this row or a few rows either side:
     * a gunport row is a stack in its neighbouring rows, a railing / ladder / fender post is a lone block everywhere and
     * must not widen the envelope into open water.
     */
    private static final class RowProbe {
        private final Grid grid;
        private final Frame frame;
        private final int minX, minY, minZ, maxY, width, depth;
        private final int[] band;
        private final double[] top;
        private final boolean[] probed;
        /** Whole-height hull count per column, shared across rows; -1 = not counted yet. */
        private final int[] totalMemo;
        private int row;

        RowProbe(Grid grid, Frame frame, int minX, int minY, int minZ, int maxY, int width, int depth) {
            this.grid = grid;
            this.frame = frame;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxY = maxY;
            this.width = width;
            this.depth = depth;
            this.band = new int[width];
            this.top = new double[width];
            this.probed = new boolean[width];
            this.totalMemo = new int[width * depth];
            Arrays.fill(totalMemo, -1);
        }

        void startRow(int row) {
            this.row = row;
            Arrays.fill(probed, false);
        }

        private void ensure(int c) {
            if (!probed[c]) {
                probeWallColumn(grid, frame, minX + c, minZ + row, minY, maxY, band, top, c);
                probed[c] = true;
            }
        }

        private int columnTotal(int r, int c) {
            final int i = r * width + c;
            if (totalMemo[i] < 0) {
                int total = 0;
                for (int ly = minY; ly <= maxY; ly++) {
                    if (grid.isHull(minX + c, ly, minZ + r)) {
                        total++;
                    }
                }
                totalMemo[i] = total;
            }
            return totalMemo[i];
        }

        private boolean supported(int c) {
            for (int dr = 0; dr <= WALL_SUPPORT_ROWS; dr++) {
                if (stackAtRowOffset(c, dr)) {
                    return true;
                }
            }
            return false;
        }

        /** Is column c a real stack in the row dr rows away (either side)? */
        private boolean stackAtRowOffset(int c, int dr) {
            final int r0 = row;
            if (dr == 0) {
                return columnTotal(r0, c) >= WALL_MIN_COLUMN_BLOCKS;
            }
            final int up = r0 + dr, down = r0 - dr;
            return (up < depth && columnTotal(up, c) >= WALL_MIN_COLUMN_BLOCKS)
                    || (down >= 0 && columnTotal(down, c) >= WALL_MIN_COLUMN_BLOCKS);
        }

        boolean isWall(int c) {
            ensure(c);
            if (band[c] < 1) {
                return false;
            }
            int sum = 0;
            for (int d = -WALL_WINDOW_RADIUS; d <= WALL_WINDOW_RADIUS; d++) {
                final int k = c + d;
                if (k >= 0 && k < width) {
                    ensure(k);
                    sum += band[k];
                }
            }
            return sum >= WALL_MIN_BAND_BLOCKS && supported(c);
        }

        double windowTop(int c) {
            double best = Double.NEGATIVE_INFINITY;
            for (int d = -WALL_WINDOW_RADIUS; d <= WALL_WINDOW_RADIUS; d++) {
                final int k = c + d;
                if (k >= 0 && k < width) {
                    ensure(k);
                    best = Math.max(best, top[k]);
                }
            }
            return best;
        }
    }

    /** Highest hull cell (local Y) in the 3-column window around {@code c}; Integer.MIN_VALUE if there is none. */
    private static int highestHullAround(Grid g, int c, int lz, int minX, int maxX, int minY, int maxY) {
        int best = Integer.MIN_VALUE;
        for (int d = -WALL_WINDOW_RADIUS; d <= WALL_WINDOW_RADIUS; d++) {
            final int lx = c + d;
            if (lx < minX || lx > maxX) {
                continue;
            }
            for (int ly = maxY; ly > best && ly >= minY; ly--) {
                if (g.isHull(lx, ly, lz)) {
                    best = ly;
                    break;
                }
            }
        }
        return best;
    }

    /**
     * lowestTop: world Y of the top of the lowest solid block in the column (the hull bottom).
     * runTop:    world Y of the top of the contiguous solid run starting there (the walkable hull floor).
     * lowestY:   local Y of the lowest solid block (Integer.MAX_VALUE if none).
     * lowestTop / runTop are +Infinity if the column has no solid block below the scan ceiling.
     */
    private static void readFloorColumn(Grid g, Frame f, int lx, int lz, int minY, int maxY,
                                        double[] lowestTop, double[] runTop, int[] lowestY, int c) {
        lowestTop[c] = Double.POSITIVE_INFINITY;
        runTop[c] = Double.POSITIVE_INFINITY;
        lowestY[c] = Integer.MAX_VALUE;
        final double cx = lx + 0.5, cz = lz + 0.5;
        final int scanMax = Math.min(maxY, (int) Math.ceil(f.localY(FLOOR_SCAN_CEILING_Y, cx, cz)));
        boolean inRun = false;
        for (int ly = minY; ly <= scanMax; ly++) {
            final double height = g.floorHeight(lx, ly, lz);
            if (Double.isNaN(height)) {
                if (inRun) {
                    break;
                }
                continue;
            }
            final double top = f.worldY(cx, ly + height, cz);
            if (!inRun) {
                lowestTop[c] = top;
                lowestY[c] = ly;
                inRun = true;
            }
            runTop[c] = top;
        }
    }
}

