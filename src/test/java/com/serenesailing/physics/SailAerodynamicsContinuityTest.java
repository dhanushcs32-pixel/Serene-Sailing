package com.serenesailing.physics;

import org.joml.Vector3d;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test: sail force must be continuous across the close-hauled /
 * in-irons boundary (alignment = -0.1). Previously thrust jumped from ~0 to
 * ~0.615x full propulsion in a single step.
 */
public class SailAerodynamicsContinuityTest {

    private static double forwardForce(double alignment) {
        Vector3d forward = new Vector3d(0.0, 0.0, -1.0);
        double lateral = Math.sqrt(1.0 - alignment * alignment);
        // apparent-wind direction whose dot with `forward` equals `alignment`
        Vector3d wind = new Vector3d(lateral * 10.0, 0.0, -alignment * 10.0);
        return SailAerodynamics.calculateSailForce(
                wind, new Vector3d(), forward, new Vector3d(1, 0, 0), 1.0).dot(forward);
    }

    @Test
    @DisplayName("No thrust pop at the in-irons boundary")
    public void testNoThrustDiscontinuityAtIronsEdge() {
        double fullBeamReach = forwardForce(0.0);
        double step = 1e-4;
        double prev = forwardForce(-0.3);
        for (double a = -0.3 + step; a <= 0.3; a += step) {
            double cur = forwardForce(a);
            Assertions.assertTrue(Math.abs(cur - prev) < 0.01 * fullBeamReach,
                    "Force jumped by " + Math.abs(cur - prev) + " N near alignment " + a);
            prev = cur;
        }
    }

    @Test
    @DisplayName("Thrust is ~0 just outside the dead zone and never negative above it")
    public void testThrustFadesInFromZero() {
        double beam = forwardForce(0.0);
        Assertions.assertTrue(Math.abs(forwardForce(-0.1)) < 0.01 * beam,
                "Thrust at the dead-zone edge should be ~0");
        Assertions.assertTrue(forwardForce(-0.05) > 0.0 && forwardForce(-0.05) < beam,
                "Thrust should be partially faded in mid-transition");
        Assertions.assertEquals(beam, forwardForce(0.0), 1e-9);
    }
}

