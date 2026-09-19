package com.serenesailing.physics;

import org.joml.Vector3d;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class RaftPropagationUnitTest {

    private static final double RAFT_HULL_MASS = 1500.0; // kg (15-block raft)
    private static final double DELTA_TIME = 0.05;       // 1 tick = 0.05s

    private Vector3d raftPosition;
    private Vector3d raftVelocity;
    private Vector3d raftForward;
    private double raftYawDegrees;

    @BeforeEach
    public void setup() {
        raftPosition = new Vector3d(0.0, 63.0, 0.0);
        raftVelocity = new Vector3d(0.0, 0.0, 0.0);
        raftForward = new Vector3d(0.0, 0.0, -1.0); // Facing North (-Z)
        raftYawDegrees = 0.0;
    }

    @Test
    @DisplayName("Test 1: Downwind Wind Accelerates Raft Forward Over 20 Ticks")
    public void testDownwindThrustAndPropagation() {
        Vector3d trueWind = new Vector3d(0.0, 0.0, -10.0); // 10 m/s North (-Z)
        Vector3d sailNormal = new Vector3d(0.0, 0.0, -1.0);

        double initialZ = raftPosition.z;

        for (int tick = 0; tick < 20; tick++) {
            Vector3d perSailForce = SailAerodynamics.calculateSailForce(
                    trueWind, raftVelocity, raftForward, sailNormal, 1.0
            );
            Vector3d totalSailForce = new Vector3d(perSailForce).mul(3.0);

            Assertions.assertTrue(totalSailForce.length() > 0.0, "Sail force must be positive while below wind speed.");

            // a = F / m
            Vector3d acceleration = new Vector3d(totalSailForce).div(RAFT_HULL_MASS);

            // v = v + a * dt
            raftVelocity.add(new Vector3d(acceleration).mul(DELTA_TIME));
            raftVelocity.mul(0.99, 1.0, 0.99); // Water drag

            // p = p + v * dt
            raftPosition.add(new Vector3d(raftVelocity).mul(DELTA_TIME));
        }

        Assertions.assertTrue(raftPosition.z < initialZ, "Raft must propagate forward (-Z) when sailing downwind.");
        Assertions.assertTrue(raftVelocity.length() > 0.05, "Raft must build realistic forward velocity.");

        System.out.printf("[JUnit Propagation] Downwind Final Velocity: %.2f m/s | Total Distance: %.2f blocks%n",
                raftVelocity.length(), Math.abs(raftPosition.z - initialZ));
    }

    @Test
    @DisplayName("Test 2: Beam Reach (Crosswind) Generates Forward Propulsion")
    public void testBeamReachCrosswindThrust() {
        Vector3d crossWind = new Vector3d(-8.0, 0.0, 0.0); // Blowing East to West
        Vector3d sailNormal = new Vector3d(0.707, 0.0, -0.707).normalize();

        Vector3d force = SailAerodynamics.calculateSailForce(
                crossWind, raftVelocity, raftForward, sailNormal, 1.0
        );

        double forwardThrust = force.dot(raftForward);
        Assertions.assertTrue(forwardThrust > 0.0, "Beam reach must generate forward thrust.");

        System.out.printf("[JUnit Propagation] Beam Reach Forward Thrust: %.1f N%n", forwardThrust);
    }

    @Test
    @DisplayName("Test 3: Dead-Ahead Headwind Pushes Backward (Sternway), Never Forward")
    public void testInIronsNoPropulsion() {
        Vector3d headWind = new Vector3d(0.0, 0.0, 10.0); // Blowing South (+Z) directly against ship
        Vector3d sailNormal = new Vector3d(0.0, 0.0, -1.0);

        Vector3d force = SailAerodynamics.calculateSailForce(
                headWind, raftVelocity, raftForward, sailNormal, 1.0
        );

        double forwardThrust = force.dot(raftForward);
        Assertions.assertTrue(forwardThrust <= 0.0,
                "In-irons headwind must never propel the ship forward.");

        // Dead ahead (within 30 degrees), the sails act as drag: the pushback
        // bleeds speed and initiates sternway so the rudder can kick the bow
        // out of irons.
        Assertions.assertTrue(force.length() > 0.0,
                "Dead-ahead headwind must generate a backward pushback force.");
        Assertions.assertTrue(force.z > 0.0,
                "Pushback must point along the wind vector (+Z), i.e. backward relative to the bow.");

        System.out.printf("[JUnit Propagation] In-Irons Sternway Pushback: %.1f N (backward)%n",
                force.length());
    }

    @Test
    @DisplayName("Test 4: Rudder Deflection Generates Yaw Turning Torque")
    public void testRudderTurningTorqueAndYaw() {
        raftVelocity.set(0.0, 0.0, -2.0); // Moving forward North
        Vector3d rudderOffset = new Vector3d(0.0, 0.0, 2.5);
        float rudderAngle = 20.0f;

        RudderHydrodynamics.RudderForceResult result = RudderHydrodynamics.calculateRudderForces(
                raftVelocity, new Vector3d(0, 0, 0), raftForward, rudderOffset, rudderAngle
        );

        Assertions.assertTrue(result.force().length() > 0.0, "Rudder must generate lateral force.");
        Assertions.assertTrue(Math.abs(result.torque().y) > 0.0, "Rudder must produce yaw torque.");

        double momentOfInertia = 4250.0;
        double angularVelYaw = 0.0;

        for (int i = 0; i < 10; i++) {
            double angularAccel = result.torque().y / momentOfInertia;
            angularVelYaw += angularAccel * DELTA_TIME;
            raftYawDegrees += Math.toDegrees(angularVelYaw * DELTA_TIME);
        }

        Assertions.assertNotEquals(0.0, raftYawDegrees, "Raft heading must turn when rudder is deflected.");
        System.out.printf("[JUnit Propagation] Rudder Yaw Torque: %.1f Nm | Heading Change: %.2f°%n",
                result.torque().y, raftYawDegrees);
    }
}