package com.serenesailing.physics;

import org.joml.Vector3d;

public final class SailAerodynamics {

    private static final double AIR_DENSITY = 1.225;     // kg/m^3
    private static final double DEFAULT_SAIL_AREA = 8.0; // m^2 per sail block
    public static double SAIL_THRUST_MULTIPLIER = 1.0;   // SI Newton force output
    /** Fraction of dynamic pressure applied as backward push when the bow is
     *  dead into the apparent wind (within 30 degrees). This bleeds speed,
     *  initiates sternway, and lets the rudder kick the bow out of irons. */
    private static final double IN_IRONS_PUSHBACK = 0.15;

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
        if (alignment < -0.1) {
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
            double headwindFactor = (-alignment - 0.1) / (0.866 - 0.1);
            double dragMag = dynamicPressure * DEFAULT_SAIL_AREA * sailEfficiency
                    * (headwindFactor * IN_IRONS_PUSHBACK) * SAIL_THRUST_MULTIPLIER;
            return new Vector3d(apparentWindDir).mul(dragMag);
        }

        // Propulsion polar curve:
        // Downwind (1.0) -> coeff = 1.0
        // Beam reach (0.0) -> coeff = 0.65 (aerodynamic foil lift)
        double propulsionCoeff = 0.65 + 0.35 * alignment;

        // Dynamic pressure: q = 0.5 * rho * v_app^2
        double dynamicPressure = 0.5 * AIR_DENSITY * apparentSpeed * apparentSpeed;
        double thrustMagnitude = dynamicPressure * DEFAULT_SAIL_AREA * sailEfficiency * propulsionCoeff * SAIL_THRUST_MULTIPLIER;

        Vector3d forwardThrust = new Vector3d(shipForwardDir).mul(thrustMagnitude);

        // Lateral heeling component for crosswinds
        Vector3d lateralDir = new Vector3d(shipForwardDir).cross(0, 1, 0).normalize();
        double lateralDot = apparentWindDir.dot(lateralDir);
        Vector3d heelingForce = new Vector3d(lateralDir).mul(thrustMagnitude * 0.2 * lateralDot);

        return forwardThrust.add(heelingForce);
    }
}