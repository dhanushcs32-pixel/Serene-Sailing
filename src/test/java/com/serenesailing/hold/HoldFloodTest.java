package com.serenesailing.hold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bowl-shaped hulls (open top) on a 12 x 10 x 20 grid; local y = 0 sits at world y 60, so cells y = 0..2 are below 62.89. */
public class HoldFloodTest {

    private static final int W = 12, H = 10, D = 20;
    private static final double WATER_Y = 62.8889;

    private static final class Ship {
        final boolean[] solid = new boolean[W * H * D];

        boolean at(int x, int y, int z) {
            return x >= 0 && x < W && y >= 0 && y < H && z >= 0 && z < D && solid[(y * D + z) * W + x];
        }

        void set(int x, int y, int z, boolean v) {
            solid[(y * D + z) * W + x] = v;
        }

        /** A bowl: floor layer y=0, perimeter walls up to y=wallTop, open top. */
        static Ship bowl(int wallTop) {
            final Ship s = new Ship();
            for (int x = 0; x < W; x++) {
                for (int z = 0; z < D; z++) {
                    s.set(x, 0, z, true);
                    if (x == 0 || x == W - 1 || z == 0 || z == D - 1) {
                        for (int y = 0; y <= wallTop; y++) {
                            s.set(x, y, z, true);
                        }
                    }
                }
            }
            return s;
        }

        HoldFlood.Result analyse(HoldScanner.Frame frame) {
            return HoldFlood.analyse(0, 0, 0, W - 1, H - 1, D - 1, (x, y, z, dir) -> at(x, y, z), frame, WATER_Y);
        }

        HoldFlood.Result analyse() {
            return analyse(HoldScanner.Frame.level(0, 0, 0, 60.0));
        }
    }

    @Test
    @DisplayName("Sealed bowl: the whole interior below the waterline is one dry pocket, nothing is flooded")
    public void sealedBowlIsDry() {
        final HoldFlood.Result r = Ship.bowl(8).analyse();
        assertEquals(10 * 18 * 2, r.dryCells(), "interior x1..10, z1..18, layers y=1,2");
        assertEquals(0, r.floodedCells());
        assertTrue(r.isDry(5, 1, 5));
        assertFalse(r.isDry(5, 5, 5), "above the waterline is never wet or dry");
    }

    @Test
    @DisplayName("A hole at or below the waterline floods the whole connected interior")
    public void holeBelowWaterlineFloods() {
        final Ship s = Ship.bowl(8);
        s.set(0, 1, 5, false);
        final HoldFlood.Result r = s.analyse();
        assertEquals(0, r.dryCells(), "water reaches every connected cell, so nothing stays dry");
        assertTrue(r.floodedCells() >= 10 * 18 * 2);
    }

    @Test
    @DisplayName("A hole ABOVE the waterline does not matter: the hull is still watertight")
    public void holeAboveWaterlineStaysDry() {
        final Ship s = Ship.bowl(8);
        s.set(0, 5, 5, false);
        assertEquals(10 * 18 * 2, s.analyse().dryCells());
    }

    @Test
    @DisplayName("Bulkhead: a breach in one compartment floods that compartment only")
    public void breachFloodsOnlyItsCompartment() {
        final Ship s = Ship.bowl(8);
        for (int x = 1; x <= 10; x++) {
            for (int y = 1; y <= 8; y++) {
                s.set(x, y, 10, true);          // watertight bulkhead across the hull
            }
        }
        s.set(0, 1, 5, false);                  // breach in the forward half (z < 10)
        final HoldFlood.Result r = s.analyse();
        assertEquals(10 * 8 * 2, r.dryCells(), "the aft compartment z=11..18 stays dry");
        assertTrue(r.isDry(5, 1, 15));
        assertFalse(r.isDry(5, 1, 5), "the breached compartment is flooded");
    }

    @Test
    @DisplayName("Rim awash: walls lower than the sea let it pour in over the top")
    public void rimBelowTheSeaFloods() {
        final HoldFlood.Result r = Ship.bowl(1).analyse();   // wall tops at y=1, surface is in cell y=2
        assertEquals(0, r.dryCells());
    }

    @Test
    @DisplayName("Flat raft (a slab at the waterline): no dry pocket, so nothing to hide and nothing to classify")
    public void flatRaftHasNoPocket() {
        final Ship s = new Ship();
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < D; z++) {
                s.set(x, 2, z, true);
            }
        }
        final HoldFlood.Result r = s.analyse();
        assertEquals(0, r.dryCells());
        assertFalse(r.hasDryPocket());
    }

    @Test
    @DisplayName("A sealed air cavity inside a solid hull is dry")
    public void sealedCavityIsDry() {
        final Ship s = new Ship();
        for (int x = 3; x <= 6; x++) {
            for (int y = 0; y <= 3; y++) {
                for (int z = 3; z <= 6; z++) {
                    s.set(x, y, z, true);
                }
            }
        }
        for (int x = 4; x <= 5; x++) {
            for (int y = 1; y <= 2; y++) {
                for (int z = 4; z <= 5; z++) {
                    s.set(x, y, z, false);
                }
            }
        }
        assertEquals(2 * 2 * 2, s.analyse().dryCells());
    }

    @Test
    @DisplayName("Heeled ship (tilted waterline) with a sealed hull stays dry; a breach still floods it")
    public void heeledShip() {
        final HoldScanner.Frame heeled = new HoldScanner.Frame(0, 0, 0, 60.4, 0.05, 1.0, 0.0);
        assertTrue(Ship.bowl(8).analyse(heeled).dryCells() > 0);
        assertEquals(0, ((Ship) breached()).analyse(heeled).dryCells());
    }

    private static Ship breached() {
        final Ship s = Ship.bowl(8);
        s.set(0, 1, 5, false);
        return s;
    }

    @Test
    @DisplayName("A cell whose faces are not blocked is an opening, whatever block it holds (porthole, open trapdoor)")
    public void porousBlockCountsAsAnOpening() {
        final Ship s = Ship.bowl(8);
        final HoldFlood.Result sealed = HoldFlood.analyse(0, 0, 0, W - 1, H - 1, D - 1,
                (x, y, z, dir) -> s.at(x, y, z) && !(x == 0 && y == 1 && z == 5), HoldScanner.Frame.level(0, 0, 0, 60.0), WATER_Y);
        assertEquals(0, sealed.dryCells(), "a block that does not cover its faces is an opening");
    }
}
