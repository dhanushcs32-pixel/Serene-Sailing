package com.serenesailing.hold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HoldScanScheduleTest {

    private static final int SPACING = 10;

    @Test
    @DisplayName("A clean ship is only scanned by the background pass")
    public void cleanShipOnlyOnFullPass() {
        final HoldScanSchedule s = new HoldScanSchedule();
        assertFalse(s.due(100, false, SPACING));
        assertTrue(s.due(100, true, SPACING));
    }

    @Test
    @DisplayName("A block change is scanned immediately, but never more often than the minimum spacing")
    public void changeIsRateLimited() {
        final HoldScanSchedule s = new HoldScanSchedule();
        s.markChanged();
        assertTrue(s.due(100, false, SPACING));
        assertTrue(s.beginScan(100));
        s.afterScan(true, false, true);
        s.markChanged();                              // another plank breaks 3 ticks later
        assertFalse(s.due(103, false, SPACING), "too soon");
        assertTrue(s.isDirty(), "but it stays dirty");
        assertTrue(s.due(110, false, SPACING));
    }

    @Test
    @DisplayName("An unapplied change arms exactly MAX_FOLLOW_UPS confirmation scans, which do not re-arm themselves")
    public void followUpsAreBounded() {
        final HoldScanSchedule s = new HoldScanSchedule();
        s.markChanged();
        long now = 0;
        int scans = 0;
        boolean external = s.beginScan(now);
        assertTrue(external);
        s.afterScan(false, true, external);            // hash differs, debounce not satisfied
        scans++;
        // the hash NEVER settles: count how many scans the scheduler asks for before it gives up
        while (s.due(now + SPACING, false, SPACING) && scans < 50) {
            now += SPACING;
            external = s.beginScan(now);
            assertFalse(external, "a follow-up scan is not a block change");
            s.afterScan(false, true, external);
            scans++;
        }
        assertEquals(1 + HoldScanSchedule.MAX_FOLLOW_UPS, scans, "one scan for the change + the bounded follow-ups");
        assertFalse(s.isDirty());
    }

    @Test
    @DisplayName("Follow-ups stop as soon as the change is applied")
    public void followUpsStopOnApply() {
        final HoldScanSchedule s = new HoldScanSchedule();
        s.markChanged();
        boolean external = s.beginScan(0);
        s.afterScan(false, true, external);            // first sighting
        assertTrue(s.due(10, false, SPACING));
        external = s.beginScan(10);
        s.afterScan(true, false, external);            // second identical scan: applied
        assertFalse(s.isDirty());
        assertFalse(s.due(20, false, SPACING));
    }

    @Test
    @DisplayName("A new block change during follow-ups re-arms them")
    public void realChangeRearms() {
        final HoldScanSchedule s = new HoldScanSchedule();
        s.markChanged();
        boolean external = s.beginScan(0);
        s.afterScan(false, true, external);
        external = s.beginScan(10);
        s.afterScan(false, true, external);            // follow-up #1 used
        s.markChanged();                               // the player breaks another block
        external = s.beginScan(20);
        assertTrue(external, "a real change is recognised even though a follow-up was pending");
        s.afterScan(false, true, external);
        int more = 0;
        long now = 20;
        while (s.due(now + SPACING, false, SPACING) && more < 50) {
            now += SPACING;
            s.afterScan(false, true, s.beginScan(now));
            more++;
        }
        assertEquals(HoldScanSchedule.MAX_FOLLOW_UPS, more, "full follow-up budget again");
    }

    @Test
    @DisplayName("A ship that leaves the scan range forgets its pending change")
    public void clearDirtyForgets() {
        final HoldScanSchedule s = new HoldScanSchedule();
        s.markChanged();
        s.clearDirty();
        assertFalse(s.due(100, false, SPACING));
    }
}
