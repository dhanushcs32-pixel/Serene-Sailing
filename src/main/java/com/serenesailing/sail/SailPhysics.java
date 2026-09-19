package com.serenesailing.sail;

/**
 * Planned home for sail thrust math. Left unimplemented until the Sable
 * and Aeronautics APIs are actually resolved in-IDE (this scaffold was
 * built without network access to their Maven artifacts - see README.md).
 * <p>
 * Rough model to implement first (simplest version that could work):
 * <pre>
 *   thrust = sailArea * windSpeed^2 * dragCoefficient * cos(windAngleRelativeToSail) * SAIL_FORCE_MULTIPLIER
 * </pre>
 * where {@code windAngleRelativeToSail} is 0 when the wind hits the sail
 * face-on and 90 degrees when the wind is edge-on (no thrust). Clamp
 * negative cos() to zero rather than allowing "backwards" thrust, unless
 * you deliberately want to model luffing/backing sails later.
 * <p>
 * Real sailing also has upwind ("tacking") behaviour where the boat can
 * make forward progress at an angle even when the wind is somewhat
 * against it, thanks to keel/hull lift. Decide whether that level of
 * realism is worth it for this pack, or whether a simplified
 * downwind-only / broad-reach model is good enough - it's a fun/complexity
 * tradeoff, not a technical blocker.
 */
public final class SailPhysics {
    private SailPhysics() {
    }

    // TODO: implement once Sable's force-application API is visible in IDE.
    // Likely shape: something like
    //   void applySailForce(SubLevel subLevel, BlockPos sailPos, Vec3 windVector)
    // Check Sable's API for how Aeronautics' AeronauticsPropellerBlock (or
    // equivalent) applies force to a sub-level, and mirror that call.
}
