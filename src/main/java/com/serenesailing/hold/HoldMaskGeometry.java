package com.serenesailing.hold;

/**
 * Geometry for hiding the storm-wave mesh inside a hold. Pure Java (no Minecraft types) so it can be unit tested.
 *
 * <p>The wave mesh is a grid of whole-block cells and a cell is hidden when its CENTRE lies inside the hold's
 * footprint, so a hidden cell can stick out of the hull by up to half a cell (open sea missing beside the hull) and an
 * un-hidden cell can poke into the hull by up to half a cell (a ribbon of water along the inside of the wall). Hiding
 * a cell only when its centre is half a cell INSIDE the outline makes both errors vanish for an aligned hull: a hidden
 * cell then never leaves the footprint, and an un-hidden cell never reaches past the wall column (one block thick).
 * Only the OUTLINE is inset: the edge shared between two neighbouring valid rows stays put, otherwise stripes of
 * un-hidden cells would appear between rows.
 */
public final class HoldMaskGeometry {

    private HoldMaskGeometry() {
    }

    /**
     * Insets one row run (origin + two edge vectors, world X/Z) by {@code inset} at both ends of the row, and at the
     * sides that border an invalid row.
     *
     * @return {ox, oz, ux, uz, vx, vz} of the inset parallelogram, or null if nothing is left of it
     */
    public static double[] insetRun(double ox, double oz, double ux, double uz, double vx, double vz,
                                    double inset, boolean prevRowValid, boolean nextRowValid) {
        final double uLen = Math.hypot(ux, uz);
        final double vLen = Math.hypot(vx, vz);
        if (uLen <= 2.0 * inset || vLen <= 1.0e-9) {
            return null;
        }
        final double uxn = ux / uLen, uzn = uz / uLen;
        final double vxn = vx / vLen, vzn = vz / vLen;

        final double vStart = prevRowValid ? 0.0 : inset;
        final double vCut = vStart + (nextRowValid ? 0.0 : inset);
        if (vLen <= vCut) {
            return null;
        }
        final double newULen = uLen - 2.0 * inset;
        final double newVLen = vLen - vCut;
        return new double[]{
                ox + uxn * inset + vxn * vStart,
                oz + uzn * inset + vzn * vStart,
                uxn * newULen, uzn * newULen,
                vxn * newVLen, vzn * newVLen
        };
    }
}
