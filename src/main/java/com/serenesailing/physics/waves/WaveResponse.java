package com.serenesailing.physics.waves;

/**
 * How strongly a hull responds to the waves under it, as a function of its weight and size.
 *
 * <p>Every factor returned here is in {@code (0, 1]} where <b>1 is the current, un-attenuated behaviour of a
 * small boat or raft and is the maximum</b>. A hull never responds more than it did before this class existed;
 * heavier and longer hulls respond progressively less, so a galleon cuts through the swell that tosses a raft.
 *
 * <p>Pure arithmetic (no Minecraft types) so the curve is unit-tested and trivially tunable.
 *
 * <h3>Model</h3>
 * <pre>
 *   legacy    = 1 / (1 + blockCount / 400)                      the pre-existing block-count filter
 *   massTerm  = sqrt( REF_MASS_KG / max(mass, REF_MASS_KG) )    1 for a raft, falls with displacement
 *   lengthTerm= 1 / sqrt( max(1, length / REF_LENGTH) )         a long hull spans several crests and averages them
 *   heave     = max(HEAVE_FLOOR, legacy * sqrt(massTerm * lengthTerm))
 * </pre>
 * The geometric mean of mass and length keeps the two (correlated) inputs from double-counting.
 */
public final class WaveResponse {

    /** Mass at or below which a hull is "a raft / default Minecraft boat" and gets the full wave response (kg). */
    public static final double REF_MASS_KG = 1500.0;
    /** Hull length at or below which a hull is "small" (blocks). */
    public static final double REF_LENGTH_BLOCKS = 6.0;
    /** Even the biggest hull still moves a little with the sea. */
    public static final double HEAVE_FLOOR = 0.04;
    /** Pitch/roll never fall below this (they were floored at 0.35 before; big hulls now roll much less). */
    public static final double ROTATION_FLOOR = 0.12;

    private WaveResponse() {}

    /** The previous block-count-only filter; kept as the upper bound so behaviour never increases. */
    public static double legacyAttenuation(int blockCount) {
        return 1.0 / (1.0 + (Math.max(0, blockCount) / 400.0));
    }

    /** 1 for hulls at or below the reference mass, decreasing smoothly with displacement. */
    public static double massTerm(double massKg) {
        if (!(massKg > REF_MASS_KG)) { // also catches NaN
            return 1.0;
        }
        return Math.sqrt(REF_MASS_KG / massKg);
    }

    /** 1 for hulls at or below the reference length, decreasing as the hull spans more wave. */
    public static double lengthTerm(double hullLengthBlocks) {
        if (!(hullLengthBlocks > REF_LENGTH_BLOCKS)) {
            return 1.0;
        }
        return 1.0 / Math.sqrt(hullLengthBlocks / REF_LENGTH_BLOCKS);
    }

    /**
     * Fraction of the wave's vertical displacement (and slope-driven surge/sway) the hull follows.
     * Bounded above by {@link #legacyAttenuation}, so it can only ever be <= the old value.
     */
    public static double heaveResponse(double massKg, double hullLengthBlocks, int blockCount) {
        double legacy = legacyAttenuation(blockCount);
        double sizeTerm = Math.sqrt(massTerm(massKg) * lengthTerm(hullLengthBlocks));
        return Math.min(legacy, Math.max(HEAVE_FLOOR, legacy * sizeTerm));
    }

    /**
     * Fraction of the wave's pitch/roll the hull follows. Rotation falls off more gently than heave (a heavy
     * ship still leans into a big swell), and is capped by the old rotation filter so it never increases.
     */
    public static double rotationResponse(double massKg, double hullLengthBlocks, int blockCount) {
        double legacyRot = Math.max(0.35, legacyAttenuation(blockCount));
        double heave = heaveResponse(massKg, hullLengthBlocks, blockCount);
        return Math.min(legacyRot, Math.max(ROTATION_FLOOR, Math.sqrt(heave)));
    }
}

