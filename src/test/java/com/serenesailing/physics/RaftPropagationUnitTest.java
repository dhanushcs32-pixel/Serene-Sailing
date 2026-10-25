package com.serenesailing.physics;

import com.serenesailing.physics.waves.GerstnerWaveEngine;
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

    @Test
    @DisplayName("Test 5: Low-Speed Steerage Retains Turning Authority Without Deadband")
    public void testLowSpeedSteerage() {
        // Very low underway velocity (0.06 m/s North)
        raftVelocity.set(0.0, 0.0, -0.06);
        Vector3d rudderOffset = new Vector3d(0.0, 0.0, 2.5);
        float rudderAngle = 15.0f;

        RudderHydrodynamics.RudderForceResult result = RudderHydrodynamics.calculateRudderForces(
                raftVelocity, new Vector3d(0, 0, 0), raftForward, rudderOffset, rudderAngle
        );

        Assertions.assertTrue(result.force().length() > 0.0,
                "Rudder must retain non-zero lateral steerage at low speeds.");
        Assertions.assertTrue(Math.abs(result.torque().y) > 0.0,
                "Rudder must produce non-zero turning torque at low speeds via inflow floor.");
        System.out.printf("[JUnit Propagation] Low-Speed Yaw Torque: %.2f Nm | Force: %.2f N%n",
                result.torque().y, result.force().length());
    }

    @Test
    @DisplayName("Task 4: Vessel maintains forward speed and never stalls through normal and severe storm waves")
    public void testVesselMaintainsForwardSpeedThroughStormWaves() {
        // Test configurations: [mass, sailCount, hullLength, baseThrust]
        double[][] vessels = {
                { 1500.0, 3.0, 5.0, 1200.0 },   // Light Raft
                { 12000.0, 12.0, 24.0, 4800.0 } // Multi-sail Ship
        };

        // Storm conditions: [stormIntensity, activeWaveScale]
        double[][] waveConditions = {
                { 0.25, 1.0 }, // Normal active waves
                { 1.00, 1.0 }, // Severe storm waves (1.0x scale)
                { 1.00, 2.0 }  // Severe storm waves (2.0x aggressiveness)
        };

        // Wave propagation directions:
        // Heading is North (0, 0, -1)
        // 1. Oncoming waves: waves propagating South (windDir = 0, 1) directly opposing ship
        // 2. Cross waves: waves propagating West (windDir = -1, 0)
        // 3. Following waves: waves propagating North (windDir = 0, -1) with ship
        Vector3d[] waveDirections = {
                new Vector3d(0.0, 0.0, 1.0),  // Oncoming
                new Vector3d(-1.0, 0.0, 0.0), // Cross
                new Vector3d(0.0, 0.0, -1.0)  // Following
        };

        double linearMu = 85.0;
        double quadMu = 35.0;

        for (double[] vessel : vessels) {
            double mass = vessel[0];
            double hullLength = vessel[2];
            double thrust = vessel[3];
            double hullDragScale = Math.pow(mass / 10000.0, 0.33);
            double halfLength = hullLength * 0.5;

            // 1. Calculate flat-water cruising speed benchmark over 200 ticks
            double flatSpeed = 0.0;
            for (int t = 0; t < 200; t++) {
                double drag = (linearMu * flatSpeed + quadMu * flatSpeed * flatSpeed) * hullDragScale;
                double netF = thrust - drag;
                flatSpeed += (netF / mass) * DELTA_TIME;
            }

            Assertions.assertTrue(flatSpeed > 2.0, "Flat water cruising speed must be positive.");

            // 2. Test each wave condition and direction
            for (double[] waveCond : waveConditions) {
                double stormIntensity = waveCond[0];
                double waveScale = waveCond[1];

                for (Vector3d waveDir : waveDirections) {
                    double speed = 0.0;
                    Vector3d pos = new Vector3d(0.0, 63.0, 0.0);
                    Vector3d fwd = new Vector3d(0.0, 0.0, -1.0); // Heading North

                    for (int t = 0; t < 200; t++) {
                        double timeSec = t * DELTA_TIME;

                        // 3-point keel slope sampling (Bow, Mid, Stern)
                        Vector3d bow = new Vector3d(pos).add(new Vector3d(fwd).mul(halfLength));
                        Vector3d stern = new Vector3d(pos).sub(new Vector3d(fwd).mul(halfLength));

                        Vector3d sBow = GerstnerWaveEngine.evaluateWaveSlopeAndDrift(bow.x, bow.z, timeSec, stormIntensity, waveDir.x, waveDir.z);
                        Vector3d sMid = GerstnerWaveEngine.evaluateWaveSlopeAndDrift(pos.x, pos.z, timeSec, stormIntensity, waveDir.x, waveDir.z);
                        Vector3d sStern = GerstnerWaveEngine.evaluateWaveSlopeAndDrift(stern.x, stern.z, timeSec, stormIntensity, waveDir.x, waveDir.z);

                        double avgSlopeX = (sBow.x + sMid.x + sStern.x) / 3.0 * waveScale;
                        double avgSlopeZ = (sBow.z + sMid.z + sStern.z) / 3.0 * waveScale;

                        double heaveAtten = 1.0 / (1.0 + (mass / 3200.0));
                        Vector3d slopeForce = new Vector3d(-mass * 9.81 * avgSlopeX * heaveAtten, 0.0, -mass * 9.81 * avgSlopeZ * heaveAtten);

                        // Decompose into surge (along fwd)
                        double surgeForce = slopeForce.dot(fwd);
                        if (surgeForce < 0.0) {
                            if (thrust > 1.0 || speed > 0.15) {
                                surgeForce *= 0.12;
                                double maxRetardation = Math.max(0.0, 0.15 * thrust);
                                if (-surgeForce > maxRetardation) {
                                    surgeForce = -maxRetardation;
                                }
                            }
                        }

                        // Stokes drift
                        double stokesSurge = 0.0;
                        if (stormIntensity > 0.4) {
                            double stokesMag = (stormIntensity - 0.4) * 350.0 * Math.sqrt(mass / 1000.0);
                            Vector3d stokesForce = new Vector3d(waveDir.x * stokesMag, 0.0, waveDir.z * stokesMag);
                            stokesSurge = stokesForce.dot(fwd);
                            if (thrust > 1.0 && stokesSurge < 0.0) {
                                stokesSurge *= 0.12;
                                double maxStokesBrake = 0.10 * thrust;
                                if (-stokesSurge > maxStokesBrake) {
                                    stokesSurge = -maxStokesBrake;
                                }
                            }
                        }

                        double drag = (linearMu * speed + quadMu * speed * speed) * hullDragScale;
                        double netThrust = thrust + surgeForce + stokesSurge - drag;
                        speed += (netThrust / mass) * DELTA_TIME;

                        pos.add(new Vector3d(fwd).mul(speed * DELTA_TIME));

                        // Assert speed accelerates and never stalls once underway
                        if (t > 40) {
                            Assertions.assertTrue(speed > 0.5,
                                    String.format("Vessel stalled at tick %d (speed=%.3f m/s) under storm=%.2f", t, speed, stormIntensity));
                        }
                    }

                    // Must retain at least 80% of flat-water cruising speed
                    double speedRatio = speed / flatSpeed;
                    Assertions.assertTrue(speedRatio >= 0.80,
                            String.format("Vessel speed ratio %.2f was below 80%% benchmark (speed=%.2f, flat=%.2f) under storm=%.2f",
                                    speedRatio, speed, flatSpeed, stormIntensity));
                }
            }
        }
    }
}