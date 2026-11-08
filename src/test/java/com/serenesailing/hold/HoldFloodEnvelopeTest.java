package com.serenesailing.hold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Flood-fill result -> {@link HoldScanner.Envelope}. Same bowl fixture as {@link HoldFloodTest}: 12 x 10 x 20, water in cells y = 0..2. */
public class HoldFloodEnvelopeTest {

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

        static Ship bowl() {
            final Ship s = new Ship();
            for (int x = 0; x < W; x++) {
                for (int z = 0; z < D; z++) {
                    s.set(x, 0, z, true);
                    if (x == 0 || x == W - 1 || z == 0 || z == D - 1) {
                        for (int y = 0; y <= 8; y++) {
                            s.set(x, y, z, true);
                        }
                    }
                }
            }
            return s;
        }

        HoldScanner.Envelope envelope(double oy) {
            final HoldScanner.Frame frame = HoldScanner.Frame.level(0, 0, 0, oy);
            final HoldFlood.Grid grid = (x, y, z, dir) -> at(x, y, z);
            final HoldFlood.Result r = HoldFlood.analyse(0, 0, 0, W - 1, H - 1, D - 1, grid, frame, WATER_Y, 0.25, null);
            return HoldFloodEnvelope.build(0, 0, 0, W - 1, H - 1, D - 1, grid, r, frame);
        }

        HoldScanner.Envelope envelope() {
            return envelope(60.0);
        }
    }

    @Test
    @DisplayName("Sealed bowl: the interior is the sealed volume, up through the open air to the cap; walls are not in it")
    public void sealedBowlBecomesAVolume() {
        final HoldScanner.Envelope env = Ship.bowl().envelope();
        assertTrue(env.isSunken());
        assertTrue(env.hasVolume());
        assertNotEquals(0, env.volumeHash());
        assertTrue(env.contains(5, 1, 5), "dry cell");
        assertTrue(env.contains(5, 2, 5));
        assertTrue(env.contains(5, 6, 5), "open air above the pocket (surface and wave crests) is covered");
        assertFalse(env.contains(5, 0, 5), "the floor block is below the pocket");
        assertFalse(env.contains(0, 1, 5), "the wall column is not part of the physics volume");
        assertFalse(env.contains(11, 1, 5));
        assertFalse(env.contains(5, 1, 0), "nor is the end wall");
        assertEquals(1, env.columnLo(5, 5) , "pocket starts on the floor");
    }

    @Test
    @DisplayName("The wall columns ARE in the render footprint (wave mask / planes), exactly one column around the interior")
    public void wallsJoinTheFootprint() {
        final HoldScanner.Envelope env = Ship.bowl().envelope();
        final int row = 5;
        assertEquals(0, env.portX(row), "footprint starts at the port wall");
        assertEquals(W - 1, env.starboardX(row));
        assertTrue((env.flags(row, 0) & HoldScanner.FLAG_SEALED) != 0);
        assertTrue((env.flags(row, 5) & HoldScanner.FLAG_SEALED) != 0);
        // the end walls form rows of their own
        assertEquals(0, env.portX(0));
        assertEquals(D, env.validRows(), "every row z=0..19 has footprint, the end walls included");
    }

    @Test
    @DisplayName("A breach below the waterline floods the hold: no volume, hash 0, nothing to hide")
    public void breachedBowlHasNoVolume() {
        final Ship s = Ship.bowl();
        s.set(0, 1, 5, false);
        final HoldScanner.Envelope env = s.envelope();
        assertFalse(env.isSunken());
        assertFalse(env.hasVolume());
        assertEquals(0, env.volumeHash());
        assertFalse(env.contains(5, 1, 5), "flooded: ocean physics and ocean water apply");
    }

    @Test
    @DisplayName("Bulkhead + breach: only the breached compartment is open to the sea; the other keeps its volume")
    public void onlyTheBreachedCompartmentFloods() {
        final Ship s = Ship.bowl();
        for (int x = 1; x <= 10; x++) {
            for (int y = 1; y <= 8; y++) {
                s.set(x, y, 10, true);
            }
        }
        s.set(0, 1, 5, false);
        final HoldScanner.Envelope env = s.envelope();
        assertTrue(env.isSunken());
        assertFalse(env.contains(5, 1, 5), "forward compartment flooded");
        assertTrue(env.contains(5, 1, 15), "aft compartment dry");
        assertTrue((env.flags(10, 5) & HoldScanner.FLAG_SEALED) != 0, "the bulkhead joins the aft footprint");
        assertEquals(0, env.flags(5, 5), "the flooded compartment is not in the footprint");
    }

    @Test
    @DisplayName("Ceiling: an enclosed hold's volume stops at its deck, it does not run up through the hull")
    public void volumeStopsAtTheDeck() {
        final Ship s = Ship.bowl();
        for (int x = 1; x <= 10; x++) {
            for (int z = 1; z <= 18; z++) {
                s.set(x, 5, z, true);       // a deck over the hold
            }
        }
        final HoldScanner.Envelope env = s.envelope();
        assertTrue(env.contains(5, 4, 5));
        assertFalse(env.contains(5, 5, 5), "deck block");
        assertFalse(env.contains(5, 6, 5), "above the deck is not part of the hold volume");
    }

    @Test
    @DisplayName("Heave: the volume hash does not change while the waterline moves through a metre of swell")
    public void hashIsStableUnderHeave() {
        final int reference = Ship.bowl().envelope(60.0).volumeHash();
        assertNotEquals(0, reference);
        for (int i = -10; i <= 10; i++) {
            final double oy = 60.0 + i * 0.05;
            assertEquals(reference, Ship.bowl().envelope(oy).volumeHash(), "heave offset " + i * 0.05);
        }
    }

    @Test
    @DisplayName("Flat raft: no volume, no footprint")
    public void raftIsEmpty() {
        final Ship s = new Ship();
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < D; z++) {
                s.set(x, 2, z, true);
            }
        }
        final HoldScanner.Envelope env = s.envelope();
        assertFalse(env.isSunken());
        assertEquals(0, env.volumeHash());
        assertEquals(0, env.validRows());
    }
}
