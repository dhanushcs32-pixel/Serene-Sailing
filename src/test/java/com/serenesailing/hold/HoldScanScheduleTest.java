package com.serenesailing.hold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HoldScanScheduleTest {

    private static final int SPACING = 10, BASE = 20, MAX = 200;

    private static HoldScanSchedule started(long now, int seed) {
        final HoldScanSchedule s = new HoldScanSchedule();
        s.initialise(now, BASE, seed);
        return s;
    }

    /** One timer-driven scan that finds nothing new. */
    private static void quietScan(HoldScanSchedule s, long now) {
        final boolean external = s.beginScan(now);
        s.afterScan(now, false, false, external, false, BASE, MAX);
    }

    @Test
    @DisplayName("Ships are staggered: different seeds start on different ticks inside one base interval")
    public void firstScansAreStaggered() {
        final java.util.Set<Long> starts = new java.util.HashSet<>();
        for (int seed = 0; seed < 5; seed++) {
            final HoldScanSchedule s = started(1000, seed * 7);
            starts.add(s.nextScanTick());
            assertTrue(s.nextScanTick() >= 1000 && s.nextScanTick() < 1000 + BASE);
            assertFalse(s.due(999, SPACING));
        }
        assertEquals(5, starts.size(), "five ships, five different first scan ticks");
        assertFalse(new HoldScanSchedule().due(5000, SPACING), "never scanned before it is initialised");
    }

    @Test
    @DisplayName("A ship that keeps coming back unchanged is scanned less and less often, up to the maximum gap")
    public void quietShipsBackOff() {
        final HoldScanSchedule s = started(0, 0);
        long now = s.nextScanTick();
        final java.util.List<Long> gaps = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            quietScan(s, now);
            gaps.add(s.nextScanTick() - now);
            now = s.nextScanTick();
        }
        assertEquals((long) BASE, (long) gaps.get(0));
        assertEquals((long) BASE * 3, (long) gaps.get(HoldScanSchedule.STABLE_FOR_FIRST_BACKOFF - 1));
        assertEquals((long) MAX, (long) gaps.get(gaps.size() - 1));
        long previous = 0;
        for (final long g : gaps) {
            assertTrue(g >= previous, "the gap never shrinks while the ship stays quiet");
            previous = g;
        }
    }

    @Test
    @DisplayName("A block change snaps a backed-off ship straight back to the base rate, subject to the minimum spacing")
    public void changeWakesAQuietShip() {
        final HoldScanSchedule s = started(0, 0);
        long now = s.nextScanTick();
        for (int i = 0; i < 12; i++) {
            quietScan(s, now);
            now = s.nextScanTick();
        }
        assertFalse(s.due(now - 5, SPACING), "quiet and not yet due");
        s.markChanged();
        assertTrue(s.due(now - 5, SPACING), "a change is scanned straight away, not at the next backed-off tick");
        assertEquals(0, s.stableScans());
        final boolean external = s.beginScan(now - 5);
        assertTrue(external);
        s.afterScan(now - 5, true, false, external, true, BASE, MAX);
        assertEquals(now - 5 + BASE, s.nextScanTick(), "back to the base rate after a change");
    }

    @Test
    @DisplayName("A block change is scanned immediately, but never more often than the minimum spacing")
    public void changeIsRateLimited() {
        final HoldScanSchedule s = started(0, 0);
        s.markChanged();
        assertTrue(s.due(100, SPACING));
        final boolean external = s.beginScan(100);
        s.afterScan(100, true, false, external, true, BASE, MAX);
        s.markChanged();
        assertFalse(s.due(103, SPACING), "too soon");
        assertTrue(s.isDirty(), "but it stays dirty");
        assertTrue(s.due(110, SPACING));
    }

    @Test
    @DisplayName("An unapplied change arms exactly MAX_FOLLOW_UPS confirmation scans, which do not re-arm themselves")
    public void followUpsAreBounded() {
        final HoldScanSchedule s = started(0, 0);
        s.markChanged();
        long now = 0;
        int scans = 0;
        boolean external = s.beginScan(now);
        assertTrue(external);
        s.afterScan(now, false, true, external, true, BASE, MAX);
        scans++;
        while (s.due(now + SPACING, SPACING) && scans < 50) {
            now += SPACING;
            external = s.beginScan(now);
            assertFalse(external, "a follow-up scan is not a block change");
            s.afterScan(now, false, true, external, true, BASE, MAX);
            scans++;
        }
        assertEquals(1 + HoldScanSchedule.MAX_FOLLOW_UPS, scans, "one scan for the change + the bounded follow-ups");
        assertFalse(s.isDirty());
    }

    @Test
    @DisplayName("Follow-ups stop as soon as the change is applied")
    public void followUpsStopOnApply() {
        final HoldScanSchedule s = started(0, 0);
        s.markChanged();
        boolean external = s.beginScan(0);
        s.afterScan(0, false, true, external, true, BASE, MAX);
        assertTrue(s.due(10, SPACING));
        external = s.beginScan(10);
        s.afterScan(10, true, false, external, true, BASE, MAX);
        assertFalse(s.isDirty());
    }

    @Test
    @DisplayName("A new block change during follow-ups re-arms them")
    public void realChangeRearms() {
        final HoldScanSchedule s = started(0, 0);
        s.markChanged();
        boolean external = s.beginScan(0);
        s.afterScan(0, false, true, external, true, BASE, MAX);
        external = s.beginScan(10);
        s.afterScan(10, false, true, external, true, BASE, MAX);   // follow-up #1 used
        s.markChanged();                                           // the player breaks another block
        external = s.beginScan(20);
        assertTrue(external, "a real change is recognised even though a follow-up was pending");
        s.afterScan(20, false, true, external, true, BASE, MAX);
        int more = 0;
        long now = 20;
        while (s.due(now + SPACING, SPACING) && more < 50) {
            now += SPACING;
            s.afterScan(now, false, true, s.beginScan(now), true, BASE, MAX);
            more++;
        }
        assertEquals(HoldScanSchedule.MAX_FOLLOW_UPS, more, "full follow-up budget again");
    }

    @Test
    @DisplayName("A ship that leaves the scan range forgets its pending change")
    public void clearDirtyForgets() {
        final HoldScanSchedule s = started(0, 0);
        s.markChanged();
        s.clearDirty();
        assertFalse(s.due(100, SPACING) && s.isDirty());
    }

    @Test
    @DisplayName("Cost model: a ship that never changes is scanned about 20x less than at the base rate")
    public void steadyStateCostIsSmall() {
        final HoldScanSchedule s = started(0, 0);
        long now = s.nextScanTick();
        int scans = 0;
        while (now < 20 * 60 * 10) {           // ten minutes of ticks
            quietScan(s, now);
            scans++;
            now = s.nextScanTick();
        }
        final int atBaseRate = 20 * 60 * 10 / BASE;
        assertTrue(scans * 8 < atBaseRate, "scans=" + scans + " vs " + atBaseRate + " at the base rate");
    }
}
