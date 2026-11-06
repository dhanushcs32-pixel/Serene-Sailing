package com.serenesailing.hold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests for {@link HoldScanner}: no Minecraft, Sable or NeoForge is booted. Hulls are drawn on a block
 * grid and fed through the {@code IntPredicate} overload.
 *
 * <p>Fixture frame: local y = {@code yBase + k} sits at world {@code oy + k}. With {@code oy = 59.75} every block
 * centre has fraction .25, a safe distance from all band edges (x.0 / x.5) even when the hull bobs by +-0.1.
 */
public class HoldScannerTest {

    private static final int MIN_X = -2, MAX_X = 10;   // 13 columns: hull lives in x = 0..8, 2 exterior columns each side
    private static final int MIN_Z = 0, MAX_Z = 11;    // 12 rows: hull body in z = 1..10, z = 0 / 11 open (stem / stern)
    private static final int HEIGHT = 11;
    private static final double OY = 59.75;
    private static final double SURFACE_Y = 62.8889;   // vanilla sea level water surface

    /** Mutable block grid with the hull drawn in k = 0..HEIGHT-1 above yBase. */
    private static final class Hull {
        final int yBase;
        final boolean[] cells = new boolean[(MAX_X - MIN_X + 1) * HEIGHT * (MAX_Z - MIN_Z + 1)];

        Hull(int yBase) {
            this.yBase = yBase;
        }

        void set(int x, int k, int z) {
            cells[HoldScanner.index(MIN_X, yBase, MIN_Z, MAX_X, MAX_Z, x, yBase + k, z)] = true;
        }

        void clear(int x, int k, int z) {
            cells[HoldScanner.index(MIN_X, yBase, MIN_Z, MAX_X, MAX_Z, x, yBase + k, z)] = false;
        }

        void column(int x, int fromK, int toK, int z) {
            for (int k = fromK; k <= toK; k++) {
                set(x, k, z);
            }
        }

        HoldScanner.Envelope scan(double oy, HoldScanner.HullClass previous) {
            final HoldScanner.Frame frame = HoldScanner.Frame.level(MIN_X, yBase, MIN_Z, oy);
            return HoldScanner.scan(MIN_X, yBase, MIN_Z, MAX_X, yBase + HEIGHT - 1, MAX_Z,
                    i -> cells[i], frame, previous);
        }

        HoldScanner.Envelope scan() {
            return scan(OY, HoldScanner.HullClass.FLAT_RAFT);
        }
    }

    /**
     * Sloped tumblehome brig, hull in x = 0..8, body rows z = 1..10:
     * <ul>
     *   <li>keel / floor layer k=0 across x = 1..7;</li>
     *   <li>outer ribs x = 0 and x = 8: k = 0..2 only (top at world 62.75, BELOW the 63.2 wall-rise limit);</li>
     *   <li>second wall columns x = 1 and x = 7: k = 0..5 (top at world 65.75), the real bulwark;</li>
     *   <li>gunports at z = 3 and z = 7: the outer rib column holds a single trapdoor at k = 2;</li>
     *   <li>a mast at (4, 6): k = 1..9, standing on the keel;</li>
     *   <li>a raised ledge at (3, 8): no floor under it, one block at k = 4 (lowest solid above 62.65).</li>
     * </ul>
     */
    private static Hull brig(int yBase) {
        final Hull h = new Hull(yBase);
        for (int z = 1; z <= 10; z++) {
            for (int x = 1; x <= 7; x++) {
                h.set(x, 0, z);
            }
            h.column(1, 0, 5, z);
            h.column(7, 0, 5, z);
            if (z == 3 || z == 7) {
                h.set(0, 2, z);
                h.set(8, 2, z);
            } else {
                h.column(0, 0, 2, z);
                h.column(8, 0, 2, z);
            }
        }
        h.column(4, 1, 9, 6);
        h.clear(3, 0, 8);
        h.set(3, 4, 8);
        return h;
    }

    private static int localCellOfSurface(int yBase) {
        return yBase + (int) Math.floor(SURFACE_Y - OY);
    }

    @Test
    @DisplayName("Brig: tumblehome ribs and trapdoor gunports never reject or shrink a row")
    public void brigRowsAreComplete() {
        final HoldScanner.Envelope env = brig(0).scan();

        assertTrue(env.isSunken(), "a hull with a basin below the surface and walls above it is a sunken hold");
        assertEquals(10, env.validRows(), "all body rows z = 1..10 must be valid, gunport rows included");
        for (int z = 1; z <= 10; z++) {
            final int row = z - MIN_Z;
            assertEquals(0, env.portX(row), "port wall column at z=" + z + " (outer rib, even as a lone trapdoor)");
            assertEquals(8, env.starboardX(row), "starboard wall column at z=" + z);
        }
        assertEquals(-1, env.portX(0 - MIN_Z), "open stem row must not be part of the hold");
        assertEquals(-1, env.portX(11 - MIN_Z), "open stern row must not be part of the hold");
    }

    @Test
    @DisplayName("Brig: the whole [portX..starboardX] span is sealed at the water surface; nothing outside is")
    public void brigSpanIsSealedAndExteriorIsNot() {
        final int surface = localCellOfSurface(0);
        final HoldScanner.Envelope env = brig(0).scan();

        for (int z = 1; z <= 10; z++) {
            for (int x = 0; x <= 8; x++) {
                assertTrue(env.contains(x, surface, z), "cell (" + x + "," + surface + "," + z + ") must be sealed");
                assertTrue((env.flags(z - MIN_Z, x) & HoldScanner.FLAG_SEALED) != 0, "sealed flag at x=" + x + " z=" + z);
            }
            for (int x : new int[]{-2, -1, 9, 10}) {
                assertFalse(env.contains(x, surface, z), "open water outside the hull at x=" + x + " must stay water");
            }
        }
        for (int x = 0; x <= 8; x++) {
            assertFalse(env.contains(x, surface, 0), "stem row is open");
            assertFalse(env.contains(x, surface, 11), "stern row is open");
        }
    }

    @Test
    @DisplayName("Brig: masts and raised ledges stay sealed, they do not punch holes in the envelope")
    public void interiorFixturesDoNotPunchHoles() {
        final int surface = localCellOfSurface(0);
        final HoldScanner.Envelope env = brig(0).scan();

        // Mast column.
        assertTrue(env.contains(4, surface, 6));
        assertTrue(env.contains(4, surface - 1, 6));
        // Raised ledge: nothing under it, lowest solid block far above the surface.
        assertTrue(env.contains(3, surface, 8), "ledge column must still cover the water surface");
        assertTrue(env.contains(3, surface - 1, 8), "...and the cells below the ledge");
        assertEquals(0, env.flags(8 - MIN_Z, 3) & HoldScanner.FLAG_LOWER,
                "no hull floor under the ledge, so no lower-layer plane there");
        assertTrue((env.flags(8 - MIN_Z, 3) & HoldScanner.FLAG_SEALED) != 0);
        // Ordinary floor columns do get the lower-layer plane.
        assertTrue((env.flags(1 - MIN_Z, 2) & HoldScanner.FLAG_LOWER) != 0);
    }

    @Test
    @DisplayName("Brig: containsInterior excludes the two wall columns, contains includes them")
    public void interiorExcludesWallColumns() {
        final int surface = localCellOfSurface(0);
        final HoldScanner.Envelope env = brig(0).scan();

        assertTrue(env.contains(0, surface, 5));
        assertFalse(env.containsInterior(0, surface, 5));
        assertFalse(env.containsInterior(8, surface, 5));
        assertTrue(env.containsInterior(4, surface, 5));
    }

    @Test
    @DisplayName("Brig: the volume hash is stable while the hull bobs and changes when a block really changes")
    public void volumeHashIsBobStable() {
        final Hull hull = brig(0);
        final int h0 = hull.scan(OY, HoldScanner.HullClass.FLAT_RAFT).volumeHash();
        assertNotEquals(0, h0);
        assertEquals(h0, hull.scan(OY + 0.10, HoldScanner.HullClass.SUNKEN_HOLD).volumeHash(), "bobbing up");
        assertEquals(h0, hull.scan(OY - 0.10, HoldScanner.HullClass.SUNKEN_HOLD).volumeHash(), "bobbing down");

        final Hull broken = brig(0);
        for (int k = 0; k <= 5; k++) {
            broken.clear(7, k, 5); // the whole bulwark column gone at one row: its wall top drops below the surface rise limit
        }
        assertNotEquals(h0, broken.scan().volumeHash(), "a real change in the hull must change the hash");
    }

    @Test
    @DisplayName("Heave: a tall-walled brig keeps one volume hash over a full metre of heave (no region churn in waves)")
    public void volumeHashIsStableOverFullHeave() {
        final Hull tall = brig(0);
        for (int z = 1; z <= 10; z++) {
            tall.column(1, 0, 9, z);   // bulwarks / superstructure far above the 65.5 top-band limit
            tall.column(7, 0, 9, z);
        }
        final int reference = tall.scan(OY, HoldScanner.HullClass.FLAT_RAFT).volumeHash();
        assertNotEquals(0, reference);
        for (int i = -10; i <= 10; i++) {
            final double oy = OY + i * 0.05; // +-0.5 block
            assertEquals(reference, tall.scan(oy, HoldScanner.HullClass.SUNKEN_HOLD).volumeHash(),
                    "volume hash changed at heave offset " + (i * 0.05));
        }
    }

    @Test
    @DisplayName("Stern floor a hair under the waterline: the rows are sealed, no thin film of ocean water survives")
    public void floorJustUnderTheSurfaceIsSealed() {
        final Hull hull = brig(0);
        for (int z = 8; z <= 10; z++) {          // the keel line rises: floor top 62.75, above the old 62.65 limit
            for (int x = 1; x <= 7; x++) {
                hull.clear(x, 0, z);
                hull.clear(x, 1, z);
            }
            for (int x = 2; x <= 6; x++) {
                hull.set(x, 2, z);
            }
        }
        final HoldScanner.Envelope env = hull.scan();
        assertTrue(env.isSunken());
        assertEquals(10, env.validRows(), "the raised stern rows must stay part of the hold");
        for (int z = 8; z <= 10; z++) {
            for (int x = 1; x <= 7; x++) {
                assertTrue(env.contains(x, localCellOfSurface(0), z), "water film over the stern floor at x=" + x + " z=" + z);
            }
        }
    }

    @Test
    @DisplayName("Exterior railing / ladder posts one block wide never widen the envelope into open water")
    public void exteriorRailingDoesNotLeakTheEnvelope() {
        final Hull hull = brig(0);
        for (int z = 1; z <= 10; z++) {
            hull.set(-1, 2, z);   // a rail post hanging off each side at the waterline band
            hull.set(9, 2, z);
        }
        final int surface = localCellOfSurface(0);
        final HoldScanner.Envelope env = hull.scan();
        assertTrue(env.isSunken());
        for (int z = 1; z <= 10; z++) {
            assertEquals(0, env.portX(z - MIN_Z), "port wall must stay on the hull at z=" + z);
            assertEquals(8, env.starboardX(z - MIN_Z), "starboard wall must stay on the hull at z=" + z);
            assertFalse(env.contains(-1, surface, z), "open water outside the railing must stay water");
            assertFalse(env.contains(9, surface, z));
        }
    }

    @Test
    @DisplayName("Flared hull: bulwarks that overhang above the waterline do not widen the sealed span at the sea")
    public void overhangAboveTheWaterlineDoesNotWidenTheSeal() {
        final Hull hull = brig(0);
        for (int z = 1; z <= 10; z++) {
            hull.column(-1, 4, 7, z);   // flared upper hull, starts 1.1 blocks above the surface
            hull.column(9, 4, 7, z);
        }
        final int surface = localCellOfSurface(0);
        final HoldScanner.Envelope env = hull.scan();
        assertTrue(env.isSunken());
        for (int z = 1; z <= 10; z++) {
            assertEquals(0, env.portX(z - MIN_Z), "span must stop at the hull as it meets the water, z=" + z);
            assertEquals(8, env.starboardX(z - MIN_Z), "z=" + z);
            assertFalse(env.contains(-1, surface, z), "open sea under the overhang must stay visible");
            assertFalse(env.contains(9, surface, z));
        }
    }

    @Test
    @DisplayName("Plot-local Y is arbitrary: the same brig at local Y 200 and -32 is found the same way")
    public void plotLocalYIsNotAssumedToBeWorldY() {
        final int baseline = brig(0).scan().validRows();
        for (int yBase : new int[]{200, -32, 0}) {
            final HoldScanner.Envelope env = brig(yBase).scan();
            final int surface = localCellOfSurface(yBase);
            assertTrue(env.isSunken(), "yBase=" + yBase);
            assertEquals(baseline, env.validRows(), "yBase=" + yBase);
            assertTrue(env.contains(4, surface, 5), "yBase=" + yBase);
            assertTrue(env.volumeMinY() >= yBase && env.volumeMaxY() < yBase + HEIGHT, "volume lives at the local Y of the hull, yBase=" + yBase);
            assertFalse(env.contains(4, 55, 5), "a purge that assumed local Y 40..70 would be wrong for yBase=" + yBase);
        }
    }

    @Test
    @DisplayName("Flat raft: a single deck layer at the surface is not a hold and owns no volume")
    public void flatRaftIsNotAHold() {
        final Hull raft = new Hull(0);
        for (int z = 0; z <= 11; z++) {
            for (int x = 0; x <= 8; x++) {
                raft.set(x, 2, z);
            }
        }
        final HoldScanner.Envelope env = raft.scan();
        assertEquals(HoldScanner.HullClass.FLAT_RAFT, env.hullClass());
        assertFalse(env.isSunken());
        assertFalse(env.hasVolume());
        assertEquals(0, env.volumeHash());
        assertFalse(env.contains(4, localCellOfSurface(0), 5));
    }

    @Test
    @DisplayName("Flat raft with rails: raised rails alone do not make a hold, the deck has to sit below the surface")
    public void railedRaftIsNotAHold() {
        final Hull raft = new Hull(0);
        for (int z = 0; z <= 11; z++) {
            for (int x = 0; x <= 8; x++) {
                raft.set(x, 2, z);
            }
            raft.column(0, 2, 4, z);
            raft.column(8, 2, 4, z);
        }
        final HoldScanner.Envelope env = raft.scan();
        assertEquals(HoldScanner.HullClass.FLAT_RAFT, env.hullClass(), "deck top 62.75 is above the 62.65 hold-floor limit");
        assertEquals(0, env.volumeHash());
    }

    @Test
    @DisplayName("Hysteresis: a floor between 62.30 and 62.65 keeps an existing hold but cannot create one")
    public void hysteresisBetweenEnterAndStay() {
        final double oy = 59.5; // floor layer k=2 tops out at world 62.5
        final Hull basin = new Hull(0);
        for (int z = 1; z <= 10; z++) {
            for (int x = 1; x <= 7; x++) {
                basin.set(x, 2, z);
            }
            basin.column(0, 0, 5, z);
            basin.column(8, 0, 5, z);
        }
        assertEquals(HoldScanner.HullClass.FLAT_RAFT, basin.scan(oy, HoldScanner.HullClass.FLAT_RAFT).hullClass(),
                "entering needs the floor below 62.30");
        assertEquals(HoldScanner.HullClass.SUNKEN_HOLD, basin.scan(oy, HoldScanner.HullClass.SUNKEN_HOLD).hullClass(),
                "staying only needs it below 62.65");
    }

    @Test
    @DisplayName("Frame: worldY and localY are inverse, also on a pitched and rolled hull")
    public void frameRoundTrip() {
        final HoldScanner.Frame frame = new HoldScanner.Frame(5, 10, 7, 62.0, 0.05, 0.98, -0.07);
        for (double lx : new double[]{3.5, 5.0, 12.25}) {
            for (double lz : new double[]{0.5, 7.0, 20.0}) {
                final double wy = frame.worldY(lx, 13.3, lz);
                assertEquals(13.3, frame.localY(wy, lx, lz), 1.0e-9);
            }
        }
    }

    @Test
    @DisplayName("Debounce: a changed hash is applied on the second identical scan only; a one-scan flicker is ignored")
    public void debounceNeedsTwoIdenticalScans() {
        final HoldDebounce d = new HoldDebounce(0);
        assertFalse(d.offer(0));
        assertFalse(d.offer(42), "first sighting");
        assertTrue(d.offer(42), "second identical scan applies");
        assertEquals(42, d.appliedHash());
        assertFalse(d.offer(42), "unchanged");
        assertFalse(d.offer(77), "one-scan change");
        assertFalse(d.offer(42), "back to the applied hash: flicker ignored");
        assertFalse(d.offer(77));
        assertFalse(d.offer(99), "a different new hash restarts the count");
        assertTrue(d.offer(99));
        assertEquals(99, d.appliedHash());
        assertFalse(d.offer(0), "hold disappears: also debounced");
        assertTrue(d.offer(0));
        assertEquals(0, d.appliedHash());
    }
}

