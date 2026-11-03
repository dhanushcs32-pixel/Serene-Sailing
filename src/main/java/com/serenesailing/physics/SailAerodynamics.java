package com.serenesailing.physics;

import org.joml.Vector3d;

public final class SailAerodynamics {

    private static final double AIR_DENSITY = 1.225;      // kg/m^3
    private static final double DEFAULT_SAIL_AREA = 12.0; // m^2 per sail block
    public static double SAIL_THRUST_MULTIPLIER = 1.0;   // SI Newton force output
    /** Fraction of dynamic pressure applied as backward push when the bow is
     *  dead into the apparent wind (within 30 degrees). This bleeds speed,
     *  initiates sternway, and lets the rudder kick the bow out of irons. */
    private static final double IN_IRONS_PUSHBACK = 0.15;
    /** Alignment at which the headwind dead zone ends and the sails start to draw. */
    private static final double IRONS_EDGE = -0.1;
    /** Alignment at which thrust has fully faded in. Between IRONS_EDGE and this
     *  value the polar thrust is blended in with a smoothstep, so the force is
     *  continuous (0 at the dead-zone edge) instead of popping to ~0.6x thrust. */
    private static final double IRONS_EXIT_FULL_THRUST = 0.0;

    private SailAerodynamics() {}

    /**
     * Computes aerodynamic sail force with realistic in-irons dead zone and apparent wind dynamics.
     */
    public static Vector3d calculateSailForce(
            Vector3d trueWindWorld,
            Vector3d shipLinearVel,
            Vector3d shipForwardDir,
            @SuppressWarnings("unused") Vector3d sailNormal,
            double sailEfficiency
    ) {
        // Apparent wind = True Wind - Ship Velocity
        Vector3d apparentWind = new Vector3d(trueWindWorld).sub(shipLinearVel);
        double apparentSpeed = apparentWind.length();

        if (apparentSpeed < 0.1) {
            return new Vector3d(0, 0, 0);
        }

        Vector3d apparentWindDir = new Vector3d(apparentWind).normalize();

        // Alignment with ship heading:
        // +1.0 = Direct downwind (running)
        //  0.0 = Crosswind (beam reach)
        // -1.0 = Direct headwind (in irons)
        double alignment = apparentWindDir.dot(shipForwardDir);

        // IN IRONS: Pointing into apparent wind produces zero forward propulsion.
        if (alignment < IRONS_EDGE) {
            double dynamicPressure = 0.5 * AIR_DENSITY * apparentSpeed * apparentSpeed;
            if (alignment < -0.866) {
                // Dead ahead (apparent wind within 30 degrees of the bow): apply
                // backward aerodynamic drag along the apparent wind vector.
                // This pushes the hull into sternway so the rudder's steering
                // authority (which works in reverse too) can kick the bow out -
                // breaking the classic "in irons" U-turn deadlock.
                double pushMag = dynamicPressure * DEFAULT_SAIL_AREA * sailEfficiency
                        * IN_IRONS_PUSHBACK * SAIL_THRUST_MULTIPLIER;
                return new Vector3d(apparentWindDir).mul(pushMag);
            }
            // Close-hauled headwind band: no forward drive, with continuous aerodynamic
            // drag ramping smoothly toward sternway pushback so the vessel bleeds speed.
            double headwindFactor = (-alignment + IRONS_EDGE) / (0.866 + IRONS_EDGE);
            double dragMag = dynamicPressure * DEFAULT_SAIL_AREA * sailEfficiency
                    * (headwindFactor * IN_IRONS_PUSHBACK) * SAIL_THRUST_MULTIPLIER;
            return new Vector3d(apparentWindDir).mul(dragMag);
        }

        // Propulsion polar curve:
        // Downwind (1.0) -> coeff = 1.0
        // Beam reach (0.0) -> coeff = 0.65 (aerodynamic foil lift)
        double propulsionCoeff = 0.65 + 0.35 * alignment;

        // Exit from the dead zone: the drag branch above goes to exactly 0 at
        // IRONS_EDGE, so fade thrust in from 0 rather than jumping straight to
        // the polar value (~0.615). Smoothstep keeps zero slope at both ends.
        // For alignment >= IRONS_EXIT_FULL_THRUST the blend is exactly 1.0, so
        // beam-reach and downwind behaviour is unchanged.
        double t = (alignment - IRONS_EDGE) / (IRONS_EXIT_FULL_THRUST - IRONS_EDGE);
        t = Math.max(0.0, Math.min(1.0, t));
        propulsionCoeff *= t * t * (3.0 - 2.0 * t);

        // Dynamic pressure: q = 0.5 * rho * v_app^2
        double dynamicPressure = 0.5 * AIR_DENSITY * apparentSpeed * apparentSpeed;
        double thrustMagnitude = dynamicPressure * DEFAULT_SAIL_AREA * sailEfficiency * propulsionCoeff * SAIL_THRUST_MULTIPLIER;

        // Calculate pure forward thrust vector along the ship centerline
        Vector3d forwardThrust = new Vector3d(shipForwardDir).mul(thrustMagnitude);

        // Return PURE forward propulsion — do NOT add lateralDir to forwardThrust!
        return forwardThrust;
    }
}
