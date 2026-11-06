package com.serenesailing.hold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HoldMaskGeometryTest {

    private static final double EPS = 1.0e-9;

    @Test
    @DisplayName("Aligned run: ends are pulled in by the inset, shared row edges are left alone")
    public void alignedRunInsetsOnlyTheOutline() {
        // a 9-cell row (x 10..19) of one row (z 5..6), neighbours on both sides are valid rows
        final double[] r = HoldMaskGeometry.insetRun(10, 5, 9, 0, 0, 1, 0.5, true, true);
        assertTrue(r != null);
        assertEquals(10.5, r[0], EPS);   // origin x moved in by half a cell
        assertEquals(5.0, r[1], EPS);    // z untouched: both neighbour rows are valid
        assertEquals(8.0, r[2], EPS);    // length shrinks by one cell in total
        assertEquals(1.0, r[5], EPS);    // row thickness untouched
    }

    @Test
    @DisplayName("First / last row of the hold are also inset along the row-to-row direction")
    public void endRowsAreInsetAcrossToo() {
        final double[] first = HoldMaskGeometry.insetRun(0, 0, 4, 0, 0, 1, 0.5, false, true);
        assertEquals(0.5, first[1], EPS);
        assertEquals(0.5, first[5], EPS);
        final double[] only = HoldMaskGeometry.insetRun(0, 0, 4, 0, 0, 1, 0.5, false, false);
        assertTrue(only == null, "a 1-cell-thick row with both sides inset has nothing left");
    }

    @Test
    @DisplayName("A hull interior narrower than the inset leaves nothing to mask")
    public void tooNarrowReturnsNull() {
        assertTrue(HoldMaskGeometry.insetRun(0, 0, 1.0, 0, 0, 1, 0.5, true, true) == null);
    }

    @Test
    @DisplayName("Rotated hull: the inset follows the hull's own axes, not the world's")
    public void rotatedRunInsetsAlongItsOwnAxes() {
        final double c = Math.cos(Math.PI / 4), s = Math.sin(Math.PI / 4);
        // run of length 6 along 45 degrees, 1 thick
        final double[] r = HoldMaskGeometry.insetRun(0, 0, 6 * c, 6 * s, -s, c, 0.5, true, true);
        assertEquals(0.5 * c, r[0], EPS);
        assertEquals(0.5 * s, r[1], EPS);
        assertEquals(5.0, Math.hypot(r[2], r[3]), EPS);
        assertEquals(Math.atan2(s, c), Math.atan2(r[3], r[2]), EPS, "direction is preserved");
    }
}
