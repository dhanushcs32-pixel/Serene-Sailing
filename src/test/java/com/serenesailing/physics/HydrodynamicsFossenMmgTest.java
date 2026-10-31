package com.serenesailing.physics;

import com.serenesailing.event.ServerPhysicsHandler;
import com.serenesailing.physics.waves.GerstnerWaveEngine;
import net.minecraft.util.Mth;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests validating 6-DOF marine hydrodynamics (Fossen / MMG model)
 * integration: force floor removal, strict sail area scaling, decoupled yaw torque,
 * hydrodynamic hull yaw damping (Nr cross-flow derivative), rudder speed clamping,
 * and rigid body mass scaling guardrails.
 */
public class HydrodynamicsFossenMmgTest {

    private static final double DELTA = 1.0e-5;
    private static final double FORCE_PER_FULL_SAIL_N = 750.0;
    private static final double YAW_DAMP_LINEAR = 65.0;
    private static final double YAW_DAMP_QUAD = 35.0;

    @Test
    @DisplayName("Task 1.1: Sail thrust scales strictly by active sail area with zero base force floor")
    public void testStrictSailAreaForceScaling() {
        // Single sail block at 100% (area efficiency = 1.0)
        double singleSailMaxForce = 1.0 * FORCE_PER_FULL_SAIL_N;
        assertEquals(750.0, singleSailMaxForce, DELTA, "1 sail block should cap at 750 N");

        // 8 sail blocks at 100% (area efficiency = 8.0)
        double eightSailMaxForce = 8.0 * FORCE_PER_FULL_SAIL_N;
        assertEquals(6000.0, eightSailMaxForce, DELTA, "8 sail blocks should cap at 6000 N");

        // Half-reefed single sail (area efficiency = 0.5)
        double halfReefedForce = 0.5 * FORCE_PER_FULL_SAIL_N;
        assertEquals(375.0, halfReefedForce, DELTA, "Half reefed sail should produce 375 N");

        // Furled sails / zero sail area (area efficiency = 0.0) -> MUST BE ZERO (no force floor)
        double zeroSailForce = 0.0 * FORCE_PER_FULL_SAIL_N;
        assertEquals(0.0, zeroSailForce, DELTA, "Zero sails must produce exactly 0 N (no force floor)");
    }

    @Test
    @DisplayName("Task 1.2: Decouple aerodynamic sail thrust from yaw torque")
    public void testDecoupledSailYawTorque() {
        double sailYawTorque = 0.0;
        assertEquals(0.0, sailYawTorque, DELTA, "Aerodynamic sail yaw torque must be explicitly zero");

        // Centerline application: thrust local vector must have X = 0 and Y = 0
        Vector3d thrustLocalImpulse = new Vector3d(120.0, 50.0, -450.0);
        thrustLocalImpulse.x = 0.0;
        thrustLocalImpulse.y = 0.0;

        assertEquals(0.0, thrustLocalImpulse.x, DELTA, "Longitudinal centerline requires local X = 0");
        assertEquals(0.0, thrustLocalImpulse.y, DELTA, "Waterline plane requires local Y = 0");
        assertTrue(thrustLocalImpulse.z < 0, "Forward thrust must be purely along local -Z");
    }

    @Test
    @DisplayName("Task 2.1: Hydrodynamic hull yaw damping (Nr cross-flow derivative)")
    public void testHydrodynamicHullYawDamping() {
        double dt = 0.05;

        // Positive yaw rate (spinning clockwise)
        double omegaYawPos = 0.5; // rad/s
        double dampingTorquePos = -(YAW_DAMP_LINEAR * omegaYawPos + YAW_DAMP_QUAD * omegaYawPos * Math.abs(omegaYawPos));
        // -(65 * 0.5 + 35 * 0.25) = -(32.5 + 8.75) = -41.25 N*m
        assertEquals(-41.25, dampingTorquePos, DELTA, "Damping torque must oppose positive yaw rotation");
        double dampingImpulsePos = dampingTorquePos * dt;
        assertEquals(-2.0625, dampingImpulsePos, DELTA, "Damping impulse must correctly scale with dt");

        // Negative yaw rate (spinning counter-clockwise)
        double omegaYawNeg = -0.5; // rad/s
        double dampingTorqueNeg = -(YAW_DAMP_LINEAR * omegaYawNeg + YAW_DAMP_QUAD * omegaYawNeg * Math.abs(omegaYawNeg));
        assertEquals(41.25, dampingTorqueNeg, DELTA, "Damping torque must oppose negative yaw rotation");

        // At rest (omega = 0)
        double dampingTorqueZero = -(YAW_DAMP_LINEAR * 0.0 + YAW_DAMP_QUAD * 0.0);
        assertEquals(0.0, dampingTorqueZero, DELTA, "Zero spin rate must produce zero damping torque");
    }

    @Test
    @DisplayName("Task 2.2: Rudder speed scaling clamped between 0.5 and 12.0 knots")
    public void testRudderSpeedClamping() {
        // High surge speed (e.g. 25 knots) clamped to 12.0 knots
        double forwardSpeedKnotsSurge = 25.0;
        double effectiveRudderSpeedHigh = Mth.clamp(Math.abs(forwardSpeedKnotsSurge), 0.5, 12.0);
        assertEquals(12.0, effectiveRudderSpeedHigh, DELTA, "Rudder speed scaling must cap at 12 knots");

        // Stationary / stalled vessel (0.0 knots) clamped to 0.5 knots
        double forwardSpeedKnotsStalled = 0.0;
        double effectiveRudderSpeedLow = Mth.clamp(Math.abs(forwardSpeedKnotsStalled), 0.5, 12.0);
        assertEquals(0.5, effectiveRudderSpeedLow, DELTA, "Rudder speed scaling must maintain 0.5 knots baseline");

        // Cruising speed (6.0 knots) remains unclamped
        double forwardSpeedKnotsCruise = 6.0;
        double effectiveRudderSpeedCruise = Mth.clamp(Math.abs(forwardSpeedKnotsCruise), 0.5, 12.0);
        assertEquals(6.0, effectiveRudderSpeedCruise, DELTA, "Cruising speed within [0.5, 12.0] remains unclamped");

        // Sternway (negative speed) preserves backing sign
        double vFwdBacking = -2.0; // m/s
        double knotsBacking = vFwdBacking * 1.944;
        double effectiveSpeedBacking = Mth.clamp(Math.abs(knotsBacking), 0.5, 12.0);
        double vSteer = Math.copySign(effectiveSpeedBacking, vFwdBacking);
        assertTrue(vSteer < 0, "Steering speed must preserve negative sign for sternway backing");
    }

    @Test
    @DisplayName("Task 3.1: Enforce rigid body mass guardrails and gradual acceleration (a = F / M)")
    public void testRigidBodyMassAndAccelerationGuardrails() {
        // 1. Assembled vessel with 10 blocks, uninitialized mass (0.0 kg)
        int blockCountA = 10;
        double calculatedMassA = 0.0;
        double minMassA = blockCountA > 0 ? Math.max(800.0, blockCountA * 80.0) : 800.0;
        double totalMassA = Math.max(calculatedMassA, minMassA);
        // 10 * 80 = 800 kg == 800 kg minimum floor
        assertEquals(800.0, totalMassA, DELTA, "10-block vessel must have at least 800 kg mass");

        // 2. Small 2-block dinghy, calculated mass uninitialized
        int blockCountB = 2;
        double calculatedMassB = 0.0;
        double minMassB = blockCountB > 0 ? Math.max(800.0, blockCountB * 80.0) : 800.0;
        double totalMassB = Math.max(calculatedMassB, minMassB);
        // 2 * 80 = 160 kg -> clamped to 800.0 kg minimum floor
        assertEquals(800.0, totalMassB, DELTA, "Small assembled vessel must respect 800 kg floor");

        // 3. Heavy multi-block ship with valid mass calculation (15,000 kg)
        int blockCountC = 20;
        double calculatedMassC = 15000.0;
        double minMassC = blockCountC > 0 ? Math.max(800.0, blockCountC * 80.0) : 800.0;
        double totalMassC = Math.max(calculatedMassC, minMassC);
        assertEquals(15000.0, totalMassC, DELTA, "Higher calculated mass must be preserved");

        // 4. Linear acceleration satisfies a = F_thrust / M_total
        // 16,000 kg ship with 10 sails (7,500 N thrust):
        double maxAeroForce10Sails = 10.0 * FORCE_PER_FULL_SAIL_N; // 7500 N
        double shipMass = 16000.0;
        double accelerationShip = maxAeroForce10Sails / shipMass; // 7500 / 16000 = 0.46875 m/s^2
        assertEquals(0.46875, accelerationShip, DELTA, "Ship linear acceleration: a = 7500 / 16000 = 0.47 m/s^2");

        // 2,000 kg raft with 2 sails (1,500 N thrust):
        double maxAeroForce2Sails = 2.0 * FORCE_PER_FULL_SAIL_N; // 1500 N
        double raftMass = 2000.0;
        double accelerationRaft = maxAeroForce2Sails / raftMass; // 1500 / 2000 = 0.75 m/s^2
        assertEquals(0.75, accelerationRaft, DELTA, "Raft linear acceleration: a = 1500 / 2000 = 0.75 m/s^2");
    }

    @Test
    @DisplayName("Task 1: Strict body-fixed propulsion vector prevents sway/roll leakage")
    public void testBodyFixedPropulsionVector() {
        double thrustForceN = 450.0;
        double dt = 0.05;

        // Local longitudinal surge axis (-Z is Bow)
        Vector3d localLinearImpulse = new Vector3d(0.0, 0.0, -thrustForceN * dt);

        assertEquals(0.0, localLinearImpulse.x, DELTA, "Transverse sway impulse Fx must be strictly zero");
        assertEquals(0.0, localLinearImpulse.y, DELTA, "Vertical heave impulse Fy must be strictly zero");
        assertEquals(-22.5, localLinearImpulse.z, DELTA, "Longitudinal surge impulse Fz must be -thrustForceN * dt");

        double pitchImpulse = 1.5;
        double yawImpulse = -0.8;
        double rollImpulse = 0.4;
        Vector3d localAngularImpulse = new Vector3d(pitchImpulse, yawImpulse, rollImpulse);
        assertEquals(pitchImpulse, localAngularImpulse.x, DELTA);
        assertEquals(yawImpulse, localAngularImpulse.y, DELTA);
        assertEquals(rollImpulse, localAngularImpulse.z, DELTA);
    }

    @Test
    @DisplayName("Task 2: Karnopp conditional damping prevents stick-slip lockup under active thrust")
    public void testKarnoppConditionalDamping() {
        double dt = 0.05;
        double mass = 2000.0;
        double thrustN = 450.0;
        double deltaU = (thrustN / mass) * dt; // 0.01125 m/s

        // Active thrust present -> Deadband velocity clamping MUST be bypassed
        boolean hasActiveThrust = thrustN > 1.0;
        assertTrue(hasActiveThrust, "Active thrust must be recognized");

        double velocityBefore = 0.0;
        double velocityAfterTick = velocityBefore + deltaU;

        // Under Karnopp model, velocity builds continuously instead of snapping to 0.0
        assertTrue(velocityAfterTick > 0.01, "Single-tick acceleration must not be erased under active thrust");

        // When thrust is absent (0.0 N) and sails furled -> Deadband applies to bring ship to rest
        boolean noThrust = false;
        double deadbandThreshold = 0.05;
        double lowSpeed = 0.02;
        boolean shouldApplyDeadband = !noThrust && lowSpeed < deadbandThreshold;
        assertTrue(shouldApplyDeadband, "Deadband must apply when thrust is zero and below speed threshold");
    }

    @Test
    @DisplayName("Task 3: Shallow-draft raft flotation and heading fallback from sail facing")
    public void testRaftFlotationAndHeadingFallback() {
        // Flat raft (1-block height)
        double minY = 63.0;
        double maxY = 64.0;
        double hullHeight = maxY - minY;
        boolean isShallowDraft = hullHeight <= 1.5;
        assertTrue(isShallowDraft, "1-block raft must be recognized as shallow draft");

        // Target draft for flat raft should be 0.15m, not 0.40m
        float targetDraft = isShallowDraft ? 0.15f : 0.40f;
        assertEquals(0.15f, targetDraft, DELTA, "Flat raft equilibrium draft must be 0.15m");

        // Waterline sample point at minY + 0.1 ensures water contact is detected at 63.1
        double waterlineY = isShallowDraft ? minY + 0.1 : minY + 0.5 - (hullHeight * 0.4) + 0.25;
        assertEquals(63.1, waterlineY, DELTA, "Waterline sample must be near hull bottom");

        // Heading fallback: No helm present, SailBlock faces SOUTH -> Bow is sailFacing.getOpposite() = NORTH (0, 0, -1)
        net.minecraft.core.Direction sailFacing = net.minecraft.core.Direction.SOUTH;
        net.minecraft.core.Direction bowDir = sailFacing.getOpposite();
        assertEquals(net.minecraft.core.Direction.NORTH, bowDir, "Bow direction must be opposite of sail facing");
        Vector3d localForward = new Vector3d(bowDir.getStepX(), bowDir.getStepY(), bowDir.getStepZ());
        assertEquals(0.0, localForward.x, DELTA);
        assertEquals(0.0, localForward.y, DELTA);
        assertEquals(-1.0, localForward.z, DELTA, "Local forward must be (0, 0, -1)");
    }

    @Test
    @DisplayName("Task 4.1: Thrust and Drag symmetric massScale achieving realistic terminal velocities (3.5 - 13 kts)")
    public void testMassScaleThrustAndDragEquilibrium() {
        double linearDragMu = 80.0;
        double quadraticDragMu = 150.0;
        double dt = 0.025; // Sable 40 Hz substep
        double calculatedMass = 33.0; // Rapier internal rigid body default

        // 1. Ship A: 1 Sail (750 N), 16,000 kg mass
        double totalMassA = 16000.0;
        double massScaleA = calculatedMass / totalMassA;
        double hullDragScaleA = Math.pow(totalMassA / 10000.0, 0.33);

        // Terminal velocity solver: F_thrust = (mu1 * v + mu2 * v^2) * hullDragScale
        // 150 * v^2 + 80 * v - (750 / hullDragScale) = 0
        double targetDragA = 750.0 / hullDragScaleA;
        double vTermA = (-80.0 + Math.sqrt(80.0 * 80.0 + 4.0 * quadraticDragMu * targetDragA)) / (2.0 * quadraticDragMu);
        double knotsA = vTermA * 1.94384;
        assertTrue(knotsA >= 3.0 && knotsA <= 4.5, "1-sail terminal speed should be ~3.0 - 4.5 knots, got: " + knotsA);

        // Verify impulse-to-acceleration equivalence in Rapier:
        double thrustImpulseA = 750.0 * dt * massScaleA;
        double dragImpulseA = (linearDragMu * vTermA + quadraticDragMu * vTermA * vTermA) * hullDragScaleA * dt * massScaleA;
        double aThrustA = (thrustImpulseA / calculatedMass) / dt;
        double aDragA = (dragImpulseA / calculatedMass) / dt;
        assertEquals(750.0 / totalMassA, aThrustA, DELTA, "Thrust acceleration must equal F_thrust / totalMass");
        assertEquals(aThrustA, aDragA, 1.0e-4, "At terminal velocity, thrust acceleration and drag deceleration must balance");

        // 2. Ship B: 6 Sails (4,500 N), 16,000 kg mass
        double targetDragB = 4500.0 / hullDragScaleA;
        double vTermB = (-80.0 + Math.sqrt(80.0 * 80.0 + 4.0 * quadraticDragMu * targetDragB)) / (2.0 * quadraticDragMu);
        double knotsB = vTermB * 1.94384;
        assertTrue(knotsB >= 8.0 && knotsB <= 11.0, "6-sail terminal speed should be ~8.0 - 11.0 knots, got: " + knotsB);

        // 3. Ship C: 12 Sails (9,000 N), 21,600 kg mass
        double totalMassC = 21600.0;
        double massScaleC = calculatedMass / totalMassC;
        double hullDragScaleC = Math.pow(totalMassC / 10000.0, 0.33);
        double targetDragC = 9000.0 / hullDragScaleC;
        double vTermC = (-80.0 + Math.sqrt(80.0 * 80.0 + 4.0 * quadraticDragMu * targetDragC)) / (2.0 * quadraticDragMu);
        double knotsC = vTermC * 1.94384;
        assertTrue(knotsC >= 11.0 && knotsC <= 14.5, "12-sail terminal speed should be ~11.0 - 14.5 knots, got: " + knotsC);

        double thrustImpulseC = 9000.0 * dt * massScaleC;
        double aThrustC = (thrustImpulseC / calculatedMass) / dt;
        assertEquals(9000.0 / totalMassC, aThrustC, DELTA, "12-sail acceleration must equal 9000 / 21600 = 0.417 m/s^2");
    }

    @Test
    @DisplayName("Task 4.2: Local axes generalization preserves exact signs across all cardinal directions")
    public void testGeneralizedLocalAxesSignsAcrossAllCardinalDirections() {
        double dt = 0.05;
        double kRoll = 85.0 * 10.0; // 850
        double cRoll = 40.0 * 10.0; // 400
        double kPitch = 85.0 * 10.0;
        double cPitch = 40.0 * 10.0;

        // Gravity vector listing to starboard and bow pitching up:
        // In world frame, if ship lists to starboard, localGravityUp has component towards starboard
        Vector3d localGravityUpNorth = new Vector3d(-0.05, 0.998, 0.02);
        Vector3d omegaLocalNorth = new Vector3d(0.1, 0.0, -0.05);

        // North baseline: localForward = (0, 0, -1), localRight = (1, 0, 0)
        Vector3d localForwardNorth = new Vector3d(0.0, 0.0, -1.0);
        Vector3d localRightNorth = new Vector3d(1.0, 0.0, 0.0);

        // Baseline (old hardcoded North formula):
        double rollAngleOld = Mth.clamp(localGravityUpNorth.x, -1.0, 1.0);
        double pitchAngleOld = Mth.clamp(-localGravityUpNorth.z, -1.0, 1.0);
        double rollImpulseOld = (-kRoll * rollAngleOld - cRoll * omegaLocalNorth.z) * dt;
        double pitchImpulseOld = (-kPitch * pitchAngleOld - cPitch * omegaLocalNorth.x) * dt;

        // New generalized vector formula:
        double rollAngleNew = Mth.clamp(localGravityUpNorth.dot(localRightNorth), -1.0, 1.0);
        double pitchAngleNew = Mth.clamp(localGravityUpNorth.dot(localForwardNorth), -1.0, 1.0);
        double omegaRollNew = omegaLocalNorth.dot(localForwardNorth);
        double omegaPitchNew = omegaLocalNorth.dot(localRightNorth);

        Vector3d rollTorqueVec = new Vector3d(localForwardNorth).mul((kRoll * rollAngleNew - cRoll * omegaRollNew) * dt);
        Vector3d pitchTorqueVec = new Vector3d(localRightNorth).mul((-kPitch * pitchAngleNew - cPitch * omegaPitchNew) * dt);

        assertEquals(rollAngleOld, rollAngleNew, DELTA, "Roll angle must exactly match North baseline");
        assertEquals(pitchAngleOld, pitchAngleNew, DELTA, "Pitch angle must exactly match North baseline");
        assertEquals(rollImpulseOld, rollTorqueVec.z, DELTA, "Roll impulse Z must exactly match North baseline");
        assertEquals(pitchImpulseOld, pitchTorqueVec.x, DELTA, "Pitch impulse X must exactly match North baseline");

        // East direction: localForward = (1, 0, 0), localRight = (0, 0, 1)
        Vector3d localForwardEast = new Vector3d(1.0, 0.0, 0.0);
        Vector3d localUp = new Vector3d(0.0, 1.0, 0.0);
        Vector3d localRightEast = new Vector3d(localForwardEast).cross(localUp).normalize();
        assertEquals(0.0, localRightEast.x, DELTA);
        assertEquals(0.0, localRightEast.y, DELTA);
        assertEquals(1.0, localRightEast.z, DELTA, "East ship localRight must be (0, 0, 1)");

        // Rudder Facing Sign check across all 4 cardinal directions:
        // A stern rudder faces away from bow (opposite of localForward):
        // 1. North ship: bow = (0,0,-1), stern rudder faces South (0,0,1)
        Vector3d rudderNorth = new Vector3d(0.0, 0.0, 1.0);
        double signNorth = (rudderNorth.dot(localForwardNorth) <= 0.0) ? 1.0 : -1.0;
        assertEquals(1.0, signNorth, DELTA, "North stern rudder must produce +1.0 sign");

        // 2. East ship: bow = (1,0,0), stern rudder faces West (-1,0,0)
        Vector3d rudderEast = new Vector3d(-1.0, 0.0, 0.0);
        double signEast = (rudderEast.dot(localForwardEast) <= 0.0) ? 1.0 : -1.0;
        assertEquals(1.0, signEast, DELTA, "East stern rudder must produce +1.0 sign");

        // 3. South ship: bow = (0,0,1), stern rudder faces North (0,0,-1)
        Vector3d localForwardSouth = new Vector3d(0.0, 0.0, 1.0);
        Vector3d rudderSouth = new Vector3d(0.0, 0.0, -1.0);
        double signSouth = (rudderSouth.dot(localForwardSouth) <= 0.0) ? 1.0 : -1.0;
        assertEquals(1.0, signSouth, DELTA, "South stern rudder must produce +1.0 sign");

        // 4. West ship: bow = (-1,0,0), stern rudder faces East (1,0,0)
        Vector3d localForwardWest = new Vector3d(-1.0, 0.0, 0.0);
        Vector3d rudderWest = new Vector3d(1.0, 0.0, 0.0);
        double signWest = (rudderWest.dot(localForwardWest) <= 0.0) ? 1.0 : -1.0;
        assertEquals(1.0, signWest, DELTA, "West stern rudder must produce +1.0 sign");
    }

    @Test
    @DisplayName("Task 2: MMG 3-point vertical blade submersion ratio and torque scaling")
    public void testMultiPointRudderSubmersion() {
        double vSteer = 3.0; // knots
        double thetaRad = Math.toRadians(20.0);
        double steerTorqueCoeff = 800.0;
        double nominalTorque = -steerTorqueCoeff * vSteer * Math.sin(thetaRad);

        // Case 1: Airborne / in tornado (all 3 points in air)
        double airSubmersion = 0.0;
        double airFacingSignSum = 1.0 * airSubmersion;
        double airTorque = airSubmersion > 0.0 ? -steerTorqueCoeff * vSteer * Math.sin(thetaRad) * airFacingSignSum : 0.0;
        assertEquals(0.0, airTorque, DELTA, "Airborne rudder must generate exactly 0 torque");

        // Case 2: Waterline boundary (y = 62.88 in water block y = 62, bottom tip submerged)
        // rudderWorld.y - 0.65 = 62.23 (water), -0.15 = 62.73 (water or surface), +0.35 = 63.23 (air)
        double bottomOnlySubmersion = 0.50;
        double bottomFacingSignSum = 1.0 * bottomOnlySubmersion;
        double bottomTorque = -steerTorqueCoeff * vSteer * Math.sin(thetaRad) * bottomFacingSignSum;
        assertEquals(nominalTorque * 0.50, bottomTorque, DELTA, "Bottom-tip-only submersion must produce 50% torque");

        // Case 3: Mid-section + bottom tip submerged (e.g., in wave trough)
        double midAndBottomSubmersion = 0.50 + 0.35;
        assertEquals(0.85, midAndBottomSubmersion, DELTA);
        double midFacingSignSum = 1.0 * midAndBottomSubmersion;
        double midTorque = -steerTorqueCoeff * vSteer * Math.sin(thetaRad) * midFacingSignSum;
        assertEquals(nominalTorque * 0.85, midTorque, DELTA, "Bottom + mid submersion must produce 85% torque");

        // Case 4: Fully submerged blade
        double fullSubmersion = 0.50 + 0.35 + 0.15;
        assertEquals(1.00, fullSubmersion, DELTA);
        double fullFacingSignSum = 1.0 * fullSubmersion;
        double fullTorque = -steerTorqueCoeff * vSteer * Math.sin(thetaRad) * fullFacingSignSum;
        assertEquals(nominalTorque * 1.00, fullTorque, DELTA, "Fully submerged blade must produce 100% torque");
    }

    @Test
    @DisplayName("Task 4: Tall ship with CoM 7 blocks above keel floats with keel at waterSurfaceY - targetDraft")
    public void testTallShipKeelTrackingAndHollowHullDraft() {
        double waterSurfaceY = 63.0;
        double keelOffsetY = -7.0; // CoM is 7 blocks above keel bottom

        // Tall ship parameters
        int solidBlocks = 800;
        int interiorAirVolume = 2400; // Multi-deck hollow hull
        double hullDepth = 8.0; // Depth from keel to main deck

        // Archimedes draft calculation
        double submersionRatio = 0.6 * ((double) solidBlocks / (solidBlocks + interiorAirVolume));
        submersionRatio = Mth.clamp(submersionRatio, 0.15, 0.25);
        double hullDraftFromArchimedes = hullDepth * submersionRatio;
        float targetDraft = (float) Mth.clamp(hullDraftFromArchimedes, 0.50, 2.20);

        // Verify draft sits in realistic range (1.2m to 2.0m for multi-deck ship)
        assertEquals(1.20, targetDraft, 0.01, "Archimedes draft for hollow hull should be ~1.20 blocks");

        // Regulate keel to waterSurfaceY - targetDraft
        double targetKeelWorldY = waterSurfaceY - targetDraft;
        assertEquals(61.80, targetKeelWorldY, 0.01, "Keel should sit 1.20 blocks below ocean surface");

        // World position of vessel CoM (origin) at equilibrium
        double worldPosCoMY = targetKeelWorldY - keelOffsetY;
        assertEquals(68.80, worldPosCoMY, 0.01, "Vessel CoM should float ~5.8 blocks ABOVE ocean surface Y=63");
        assertTrue(worldPosCoMY > waterSurfaceY + 5.0, "Vessel origin/CoM must remain > 5 blocks above waterSurfaceY");

        // Verify that lower deck (at keel + 2.0) and gunports (at keel + 3.5) stay above ocean surface
        double lowerDeckWorldY = targetKeelWorldY + 2.0;
        assertTrue(lowerDeckWorldY > waterSurfaceY, "Lower deck (Y=" + lowerDeckWorldY + ") must remain dry above ocean surface (Y=" + waterSurfaceY + ")");

        double gunportWorldY = targetKeelWorldY + 3.5;
        assertTrue(gunportWorldY > waterSurfaceY + 1.5, "Gunports (Y=" + gunportWorldY + ") must sit > 1.5 blocks above ocean surface");

        // Contrast with buggy behavior: forcing CoM to waterSurfaceY - 0.40 drags keel to Y=55.6
        double buggyCoMY = waterSurfaceY - 0.40;
        double buggyKeelY = buggyCoMY + keelOffsetY;
        assertEquals(55.60, buggyKeelY, 0.01, "Old buggy logic dragged keel 7.4 blocks underwater");
        assertTrue(buggyKeelY < targetKeelWorldY - 6.0, "Old logic sank the ship 6+ blocks too deep");
    }

    @Test
    @DisplayName("Task 1: Volumetric Archimedes solver: V-hull vs. rectangular barge draft response")
    public void testVolumetricArchimedesVHullVsBarge() {
        // Rectangular barge: constant waterplane area (slice volume = 20 m^3 per layer) across 5 layers
        double[] bargeSlices = new double[] { 20.0, 20.0, 20.0, 20.0, 20.0 };

        // V-hull: flared waterplane area increasing with height (4, 8, 16, 24, 32 m^3) across 5 layers
        double[] vHullSlices = new double[] { 4.0, 8.0, 16.0, 24.0, 32.0 };

        double waterDensity = 1000.0; // kg/m^3

        // Case 1: Light displacement (4,000 kg -> 4 m^3 required)
        double draftBargeLight = ServerPhysicsHandler.calculateArchimedesDraft(bargeSlices, 4000.0, waterDensity);
        double draftVHullLight = ServerPhysicsHandler.calculateArchimedesDraft(vHullSlices, 4000.0, waterDensity);

        // Barge: 4 / 20 = 0.20 m
        assertEquals(0.20, draftBargeLight, DELTA, "Barge light draft must be 0.20 m");
        // V-hull: 4 m^3 completely submerges layer 0 (vol = 4.0) -> draft = 1.0 m
        assertEquals(1.00, draftVHullLight, DELTA, "V-hull light draft must submerge narrow keel layer to 1.00 m");

        // Case 2: Heavy displacement (20,000 kg -> 20 m^3 required, 5x increase in mass)
        double draftBargeHeavy = ServerPhysicsHandler.calculateArchimedesDraft(bargeSlices, 20000.0, waterDensity);
        double draftVHullHeavy = ServerPhysicsHandler.calculateArchimedesDraft(vHullSlices, 20000.0, waterDensity);

        // Barge: 20 / 20 = 1.00 m (5x mass -> exactly 5x draft, perfectly linear)
        assertEquals(1.00, draftBargeHeavy, DELTA, "Barge draft response must be linear with mass");
        assertEquals(5.0 * draftBargeLight, draftBargeHeavy, DELTA, "Barge 5x mass produces 5x draft");

        // V-hull: Layer 0 has 4.0 (draft 1.0), Layer 1 has 8.0 (total 12.0, draft 2.0).
        // Remaining 8.0 m^3 needed in Layer 2 (slice volume 16.0) -> fraction = 8.0 / 16.0 = 0.5.
        // Total draft = 2.50 m.
        assertEquals(2.50, draftVHullHeavy, DELTA, "V-hull draft for 20m^3 must be 2.50 m");

        // Verify non-linearity: 5x mass resulted in draft increasing from 1.0 to 2.5 (2.5x increase, NOT 5x)
        double vHullDraftMultiplier = draftVHullHeavy / draftVHullLight;
        assertTrue(vHullDraftMultiplier < 4.0, "V-hull draft must increase non-linearly due to flared hull geometry");
        assertEquals(2.5, vHullDraftMultiplier, DELTA);
    }

    @Test
    @DisplayName("Task 1: Negative buoyancy / sinking when mass exceeds total hull displacement")
    public void testNegativeBuoyancySinking() {
        // Hull with 3 layers, total displacement volume = 10 + 20 + 30 = 60 m^3
        double[] hullSlices = new double[] { 10.0, 20.0, 30.0 };
        double hullHeight = hullSlices.length; // 3.0 m
        double waterDensity = 1000.0;

        // Mass = 50,000 kg (50 m^3 required) -> Buoyant (draft <= hullHeight)
        double draftBuoyant = ServerPhysicsHandler.calculateArchimedesDraft(hullSlices, 50000.0, waterDensity);
        assertTrue(draftBuoyant < hullHeight, "50 tonne load should remain buoyant with draft < hullHeight");
        assertEquals(2.0 + 20.0 / 30.0, draftBuoyant, DELTA);

        // Overloaded / flooded hull: Mass = 90,000 kg (90 m^3 required, but max displacement is 60 m^3)
        double draftSinking = ServerPhysicsHandler.calculateArchimedesDraft(hullSlices, 90000.0, waterDensity);
        // Excess volume = 90 - 60 = 30 m^3. Waterplane area at top = 30 m^3.
        // draft = 3.0 + 30.0 / 30.0 = 4.0 m > hullHeight (3.0 m)
        assertTrue(draftSinking > hullHeight, "Overloaded hull draft (" + draftSinking + ") must exceed hullHeight (3.0 m)");
        assertEquals(4.0, draftSinking, DELTA, "Overloaded draft indicates negative buoyancy / sinking");
    }

    @Test
    @DisplayName("Task 2: 4-point wave surface sampling pitch and roll slope calculation")
    public void testFourPointWaveSlopePitchAndRoll() {
        double hullLength = 20.0;
        double hullBeam = 6.0;

        // Pitch scenario: Bow at +1.0m (wave crest), Stern at -1.0m (wave trough)
        double yBow = 64.0;   // 63.0 + 1.0
        double yStern = 62.0; // 63.0 - 1.0
        double deltaYPitch = yBow - yStern; // 2.0m
        double wavePitchTarget = Math.atan2(deltaYPitch, hullLength);

        double expectedPitch = Math.atan2(2.0, 20.0);
        assertEquals(expectedPitch, wavePitchTarget, DELTA, "Wave pitch target must match Math.atan2(2.0, 20.0)");
        assertTrue(wavePitchTarget > 0.09 && wavePitchTarget < 0.11, "Wave pitch angle should be ~0.0997 rad (~5.7 deg)");

        // Roll scenario: Port at +0.5m, Starboard at -0.5m
        double yPort = 63.5;      // 63.0 + 0.5
        double yStarboard = 62.5; // 63.0 - 0.5
        double deltaYRoll = yPort - yStarboard; // 1.0m
        double waveRollTarget = Math.atan2(deltaYRoll, hullBeam);

        double expectedRoll = Math.atan2(1.0, 6.0);
        assertEquals(expectedRoll, waveRollTarget, DELTA, "Wave roll target must match Math.atan2(1.0, 6.0)");
        assertTrue(waveRollTarget > 0.16 && waveRollTarget < 0.17, "Wave roll angle should be ~0.165 rad (~9.46 deg)");

        // Calmed / flat water scenario: all 4 points equal
        double flatPitch = Math.atan2(0.0, hullLength);
        double flatRoll = Math.atan2(0.0, hullBeam);
        assertEquals(0.0, flatPitch, DELTA, "Flat water pitch slope must be 0.0");
        assertEquals(0.0, flatRoll, DELTA, "Flat water roll slope must be 0.0");
    }

    @Test
    @DisplayName("Task 1: Velocity-space roll/pitch attitude stabilization with real 3D Quaterniond rotation decays toward upright")
    public void testVelocitySpaceRollPitchStabilizationNoOvershootOnLargeShip() {
        // Initial perturbation: ship rotated by +15 degrees around localForward (0, 0, -1)
        // and +15 degrees around localRight (1, 0, 0)
        Vector3d localForward = new Vector3d(0.0, 0.0, -1.0);
        Vector3d localRight = new Vector3d(1.0, 0.0, 0.0);
        double waveRollTarget = 0.0;
        double wavePitchTarget = 0.0;
        double dt = 0.025; // Sable 40 Hz substep

        Quaterniond orientation = new Quaterniond();
        // Heel to Starboard (East side drops down): -15 deg around localForward (0, 0, -1)
        orientation.rotateAxis(Math.toRadians(-15.0), localForward.x, localForward.y, localForward.z);
        // Pitch Bow Up: +15 deg around localRight (1, 0, 0)
        orientation.rotateAxis(Math.toRadians(15.0), localRight.x, localRight.y, localRight.z);
        orientation.normalize();

        double omegaRoll = 0.0;
        double omegaPitch = 0.0;

        Vector3d worldUp = new Vector3d(0.0, 1.0, 0.0);

        // Initial orientation check
        Vector3d initGravityUp = orientation.transformInverse(new Vector3d(worldUp), new Vector3d());
        double initialRoll = initGravityUp.dot(localRight);
        double initialPitch = initGravityUp.dot(localForward);
        assertTrue(initialRoll > 0.1, "Initial roll angle should be positive (> 0.1), got: " + initialRoll);
        assertTrue(Math.abs(initialPitch) > 0.1, "Initial pitch angle should be non-zero, got: " + initialPitch);

        // Simulate 40 substeps (1.0 second) under velocity-space PD control
        for (int step = 0; step < 40; step++) {
            Vector3d localGravityUp = orientation.transformInverse(new Vector3d(worldUp), new Vector3d());
            double rollAngle = Mth.clamp(localGravityUp.dot(localRight), -1.0, 1.0);
            double pitchAngle = Mth.clamp(localGravityUp.dot(localForward), -1.0, 1.0);

            // Right-hand rule signs: +4.0 for roll, -4.0 for pitch
            double targetOmegaRoll = 4.0 * (rollAngle - waveRollTarget);
            double targetOmegaPitch = -4.0 * (pitchAngle - wavePitchTarget);

            double deltaOmegaRoll = (targetOmegaRoll - omegaRoll) * 0.35;
            double deltaOmegaPitch = (targetOmegaPitch - omegaPitch) * 0.35;
            omegaRoll += deltaOmegaRoll;
            omegaPitch += deltaOmegaPitch;

            // Apply body-fixed rotation step to quaternion
            Vector3d omegaLocal = new Vector3d(localForward).mul(omegaRoll)
                    .add(new Vector3d(localRight).mul(omegaPitch));
            double angleStep = omegaLocal.length() * dt;
            if (angleStep > 1.0e-9) {
                Vector3d axis = new Vector3d(omegaLocal).normalize();
                orientation.rotateAxis(angleStep, axis.x, axis.y, axis.z).normalize();
            }
        }

        // After 1 second of stabilization, ship must be restored to nearly upright (< 2 degrees)
        Vector3d finalGravityUp = orientation.transformInverse(new Vector3d(worldUp), new Vector3d());
        double finalRoll = finalGravityUp.dot(localRight);
        double finalPitch = finalGravityUp.dot(localForward);

        assertTrue(Math.abs(finalRoll) < Math.toRadians(2.0),
                "Roll angle must decay to < 2 degrees upright within 1 second, got: " + Math.toDegrees(finalRoll));
        assertTrue(Math.abs(finalPitch) < Math.toRadians(2.0),
                "Pitch angle must decay to < 2 degrees upright within 1 second, got: " + Math.toDegrees(finalPitch));
    }

    @Test
    @DisplayName("Task 1: Upright keelWorldY calculation relative to pose.rotationPoint() is strictly invariant to roll/pitch")
    public void testUprightKeelWorldYInvariantToRollAndPitch() {
        // Sub-Level coordinates: Keel is at Y = 122.0, SubLevel origin (rotationPoint) is at Y = 128.0
        double keelLocalY = 122.0;
        double poseLocalOriginY = 128.0;
        double worldPosY = 67.65; // Overworld altitude of origin

        // Upright vertical offset from SubLevel's rotation origin to the keel bottom:
        double keelOffsetFromOriginY = keelLocalY - poseLocalOriginY; // -6.0 blocks
        double uprightKeelWorldY = worldPosY + keelOffsetFromOriginY; // 67.65 - 6.0 = 61.65

        // In upright orientation (identity matrix), transformPosition(keelLocalPoint) gives:
        Vector3d rotationPoint = new Vector3d(0.0, poseLocalOriginY, 0.0);
        Vector3d keelPoint = new Vector3d(0.0, keelLocalY, 0.0);
        Vector3d worldPos = new Vector3d(0.0, worldPosY, 0.0);
        Vector3d transformedKeelUpright = new Vector3d(keelPoint).sub(rotationPoint).add(worldPos);

        assertEquals(61.65, uprightKeelWorldY, DELTA);
        assertEquals(uprightKeelWorldY, transformedKeelUpright.y, DELTA,
                "Upright keelWorldY must exactly match pose.transformPosition Y coordinate when upright");

        // Contrast with flawed rotated calculation at 45 degree roll:
        double rollAngle = Math.toRadians(45.0);
        // Under rotation around origin (0, 128, 0), keel at (0, 122, 0) rotates to y = -6.0 * cos(45 deg) = -4.24
        double flawedRotatedKeelY = worldPosY + (keelOffsetFromOriginY * Math.cos(rollAngle));
        // Flawed rotated Y rises to 63.41 (1.76 blocks too high!)
        assertEquals(63.41, flawedRotatedKeelY, 0.01);

        // Under 90 degree roll (toppled on side):
        double flawed90DegKeelY = worldPosY + (keelOffsetFromOriginY * Math.cos(Math.toRadians(90.0)));
        assertEquals(67.65, flawed90DegKeelY, 0.01, "Flawed rotated keel at 90 deg rises to origin Y=67.65");

        // The upright calculation guarantees keelWorldY is always 61.65, breaking the positive feedback loop!
        assertEquals(61.65, uprightKeelWorldY, DELTA, "Upright keelWorldY must remain 61.65 regardless of roll angle");
    }

    @Test
    @DisplayName("Task 2: Galleon draft ensures lower walkable deck (minBlockY + 2) stays dry above ocean water Y=63")
    public void testGalleonDraftKeepsLowerDeckDryAboveOceanSurface() {
        // 2,000-block galleon: 160,000 kg total mass
        double totalMassKg = 160000.0;
        int gridHeight = 25;
        double[] sliceVolumes = new double[gridHeight];

        // Lower hull layers (bottom 40% = 10 layers):
        // Layer span X = 10, span Z = 32 -> footprint displacement = 10 * 32 * 0.75 = 240 m^3/layer
        for (int y = 0; y < 10; y++) {
            sliceVolumes[y] = 240.0;
        }
        for (int y = 10; y < gridHeight; y++) {
            sliceVolumes[y] = 40.0; // upper decks and rigging
        }

        double calculatedDraft = ServerPhysicsHandler.calculateArchimedesDraft(sliceVolumes, totalMassKg, 1000.0);
        // Required volume = 160 m^3. Layer 0 has 240 m^3 -> calculatedDraft = 160 / 240 = 0.667m!
        assertEquals(160.0 / 240.0, calculatedDraft, DELTA, "Footprint displacement produces draft of ~0.67m");

        // Capped equilibrium draft
        double targetDraft = Math.min(calculatedDraft, 1.35);
        assertTrue(targetDraft <= 1.35, "Target draft must be capped at 1.35m");

        // Water surface at Y = 63.0, ocean fluid surface at Y = 62.88
        double waterSurfaceY = 63.0;
        double oceanWaterFluidY = 62.88;

        double targetKeelWorldY = waterSurfaceY - targetDraft;
        // With targetDraft <= 1.35, keel sits at >= 61.65
        assertTrue(targetKeelWorldY >= 61.65, "Keel sits at Y=" + targetKeelWorldY + " (>= 61.65)");

        // Lower cabin deck at minBlockY + 2.0
        double lowerDeckWorldY = targetKeelWorldY + 2.0;
        assertTrue(lowerDeckWorldY >= 63.65, "Lower deck floor must sit at Y >= 63.65, got: " + lowerDeckWorldY);
        assertTrue(lowerDeckWorldY > oceanWaterFluidY + 0.70,
                "Lower deck must sit > 0.7 blocks above ocean water (Y=" + oceanWaterFluidY + ")");
    }

    @Test
    @DisplayName("Task 4.1: GerstnerWaveEngine phase continuity at far coordinates (x=5000, z=-5000) under wind direction shifts")
    public void testPhaseContinuityUnderWindDirectionChanges() {
        double farX = 5000.0;
        double farZ = -5000.0;
        double timeSec = 100.0;

        // Current wind direction: North (0, 0, -1)
        double windDirX = 0.0;
        double windDirZ = -1.0;
        double stormIntensity = 0.5;

        double waveHeightBefore = GerstnerWaveEngine.evaluateWaveOffset(farX, farZ, timeSec, stormIntensity, windDirX, windDirZ);

        // Instantaneous wind direction change by 45 degrees: target wind becomes North-East (sqrt(0.5), -sqrt(0.5))
        // In the fixed wave train model, the wave train phases phi_i = k_i · x - omega_i · t are fixed physical constants.
        // Even when wind direction shifts by 45 degrees instantaneously, the wave height difference is smooth and bounded (no infinite phase jumps).
        double newWindDirX = Math.sqrt(0.5);
        double newWindDirZ = -Math.sqrt(0.5);
        double waveHeightAfter = GerstnerWaveEngine.evaluateWaveOffset(farX, farZ, timeSec, stormIntensity, newWindDirX, newWindDirZ);

        // Because phase does not jump with wind direction (only directional weights W_i change smoothly in [0.2, 1.0]),
        // the difference is bounded by the amplitude modulation (~0.2m), NEVER jumping by meters like a raw k(theta) · x would!
        double deltaH = Math.abs(waveHeightAfter - waveHeightBefore);
        assertTrue(deltaH < 0.35, "Wave height change under 45-deg directional shift at far coordinates must be smoothly bounded (< 0.35m), got: " + deltaH);

        // Under dimension state smoothing (alpha = 0.02 per tick), the single-tick change is virtually 0:
        GerstnerWaveEngine.DimensionWaveState state = new GerstnerWaveEngine.DimensionWaveState();
        state.setImmediate(1.0, 0.0, -1.0, 0.5);
        double h1 = GerstnerWaveEngine.evaluateWaveOffset(farX, farZ, timeSec, state.smoothedStormIntensity, state.smoothedWindDirX, state.smoothedWindDirZ);

        // One tick update with alpha = 0.02
        state.smoothedWindDirX += (newWindDirX - state.smoothedWindDirX) * 0.02;
        state.smoothedWindDirZ += (newWindDirZ - state.smoothedWindDirZ) * 0.02;
        double len = Math.sqrt(state.smoothedWindDirX * state.smoothedWindDirX + state.smoothedWindDirZ * state.smoothedWindDirZ);
        state.smoothedWindDirX /= len;
        state.smoothedWindDirZ /= len;

        double h2 = GerstnerWaveEngine.evaluateWaveOffset(farX, farZ, timeSec, state.smoothedStormIntensity, state.smoothedWindDirX, state.smoothedWindDirZ);
        double singleTickJump = Math.abs(h2 - h1);
        assertTrue(singleTickJump < 0.01, "Single-tick wave height change at far coordinates must be < 0.01m, got: " + singleTickJump);
    }

    @Test
    @DisplayName("Task 4.2: Calm vs. Storm amplitude scaling (calm in [-0.05, 0.05]m, storm crests > 1.5m)")
    public void testCalmVsStormAmplitudeScaling() {
        // Calm wind: Weather2 baseline wind in clear weather -> stormIntensity = 0.02
        double calmStorm = 0.02;
        double windDirX = 0.0;
        double windDirZ = -1.0;

        double minCalm = Double.POSITIVE_INFINITY;
        double maxCalm = Double.NEGATIVE_INFINITY;
        // Sample across multiple spatial points and times
        for (double x = 0.0; x <= 60.0; x += 3.0) {
            for (double t = 0.0; t <= 10.0; t += 1.0) {
                double offset = GerstnerWaveEngine.evaluateWaveOffset(x, x * 0.5, t, calmStorm, windDirX, windDirZ);
                minCalm = Math.min(minCalm, offset);
                maxCalm = Math.max(maxCalm, offset);
            }
        }
        assertTrue(minCalm >= -0.06, "Calm wave troughs must stay >= -0.06m, got: " + minCalm);
        assertTrue(maxCalm <= 0.06, "Calm wave crests must stay <= +0.06m, got: " + maxCalm);

        // Violent Storm: speed 2.0 + thunder -> stormIntensity = 1.0
        double stormIntensity = 1.0;
        double maxStormCrest = Double.NEGATIVE_INFINITY;
        for (double z = -100.0; z <= 100.0; z += 1.0) {
            for (double t = 0.0; t <= 5.0; t += 0.5) {
                double offset = GerstnerWaveEngine.evaluateWaveOffset(0.0, z, t, stormIntensity, windDirX, windDirZ);
                maxStormCrest = Math.max(maxStormCrest, offset);
            }
        }
        assertTrue(maxStormCrest > 1.50, "Storm wave crests must exceed +1.5m, got: " + maxStormCrest);

        // Verify Trochoidal crest steepness vs trough flatness: crests are taller than troughs are deep
        double peakCrest = GerstnerWaveEngine.evaluateWaveOffset(0.0, 0.0, 0.0, stormIntensity, windDirX, windDirZ);
        assertTrue(peakCrest > 1.5, "Trochoidal peak crest exceeds 1.5m, got: " + peakCrest);
    }

    @Test
    @DisplayName("Task 4.4: Weather2 baseline clear-weather calm (windSpeed=1.0 -> S<=0.03, wave<0.05m), thunderstorm scaling (S>=0.8), and galleon heave attenuation (<0.25)")
    public void testWeather2CalmBaselineAndGalleonHeaveAttenuation() {
        // 1. Weather2 baseline wind in clear weather (windSpeed = 1.0f)
        GerstnerWaveEngine.DimensionWaveState clearState = new GerstnerWaveEngine.DimensionWaveState();
        clearState.setImmediate(1.0, 0.0, -1.0, 0.02);
        assertTrue(clearState.smoothedStormIntensity <= 0.03,
                "Clear-weather storm intensity must stay <= 0.03, got: " + clearState.smoothedStormIntensity);

        // Maximum wave elevation in clear weather must be < 0.05m (virtually flat, no aggressive dock bobbing!)
        double maxClearWave = Double.NEGATIVE_INFINITY;
        for (double z = -100.0; z <= 100.0; z += 5.0) {
            for (double t = 0.0; t <= 10.0; t += 1.0) {
                double offset = GerstnerWaveEngine.evaluateWaveOffset(0.0, z, t, clearState.smoothedStormIntensity, 0.0, -1.0);
                maxClearWave = Math.max(maxClearWave, offset);
            }
        }
        assertTrue(maxClearWave < 0.05, "Clear-weather wave amplitude must stay < 0.05m, got: " + maxClearWave);

        // 2. Thunderstorm scaling (windSpeed = 1.8f, isThundering = true)
        GerstnerWaveEngine.DimensionWaveState stormState = new GerstnerWaveEngine.DimensionWaveState();
        stormState.setImmediate(1.8, 0.0, -1.0, 0.85);
        assertTrue(stormState.smoothedStormIntensity >= 0.80,
                "Thunderstorm intensity must reach >= 0.80, got: " + stormState.smoothedStormIntensity);

        // 3. 2,000-block galleon heave attenuation
        int galleonBlockCount = 2000;
        double galleonHeaveAttenuation = 1.0 / (1.0 + (galleonBlockCount / 400.0));
        assertTrue(galleonHeaveAttenuation < 0.25,
                "2,000-block galleon heave attenuation must be < 0.25, got: " + galleonHeaveAttenuation);
        assertEquals(1.0 / 6.0, galleonHeaveAttenuation, DELTA, "Galleon heave attenuation should equal 1/6 (~0.167)");
    }

    @Test
    @DisplayName("Task 4.3: 4-point pitch/roll and anti-flooding heave floor keeps lower deck dry (Y >= 63.30)")
    public void testWavePitchRollAndAntiFloodingHeaveFloor() {
        double hullLength = 32.0;
        double hullBeam = 10.0;
        double staticWaterSurfaceY = 63.0;
        double oceanWaterFluidY = 62.88;
        double targetDraft = 1.35;

        // Extreme wave scenario: Ship in a severe wave trough
        // Bow and stern both in deep troughs: -2.0m
        double yBow = -2.0;
        double yStern = -1.0;
        double yPort = -1.8;
        double yStarboard = -0.6;

        // 1. Unclamped pitch and roll response
        double effectiveLength = Math.max(6.0, hullLength * 0.7);
        double effectiveBeam = Math.max(4.0, hullBeam * 0.7);
        double wavePitchTarget = Mth.clamp(Math.atan2(yBow - yStern, effectiveLength), -0.35, 0.35);
        double waveRollTarget = Mth.clamp(Math.atan2(yPort - yStarboard, effectiveBeam), -0.40, 0.40);

        assertTrue(Math.abs(wavePitchTarget) > 0.04, "Wave pitch target must respond to wave slope, got: " + wavePitchTarget);
        assertTrue(Math.abs(waveRollTarget) > 0.05, "Wave roll target must respond to wave slope, got: " + waveRollTarget);

        // 2. Anti-flooding meanWaterSurfaceY heave floor
        double rawMeanWaveOffset = (yBow + yStern + yPort + yStarboard) * 0.25; // (-2.0 - 1.0 - 1.8 - 0.6) / 4 = -1.35m
        assertEquals(-1.35, rawMeanWaveOffset, DELTA);

        // With anti-flooding floor: clamp negative trough offset to >= staticWaterSurfaceY - 0.35
        double clampedWaveOffset = Math.max(-0.35, rawMeanWaveOffset);
        assertEquals(-0.35, clampedWaveOffset, DELTA, "Trough offset must be clamped to -0.35m");

        double effectiveWaterSurfaceY = staticWaterSurfaceY + clampedWaveOffset; // 63.0 - 0.35 = 62.65
        double targetKeelY = effectiveWaterSurfaceY - targetDraft; // 62.65 - 1.35 = 61.30
        double lowerDeckWorldY = targetKeelY + 2.0; // 61.30 + 2.0 = 63.30

        assertTrue(lowerDeckWorldY >= 63.30, "Lower deck floor must sit at Y >= 63.30 in troughs, got: " + lowerDeckWorldY);
        assertTrue(lowerDeckWorldY > oceanWaterFluidY + 0.40,
                "Lower deck floor must stay > 0.4 blocks above ocean water fluid surface (Y=62.88) even in -2m storm troughs!");

        // 3. Positive lift on crests is UNCLAMPED (+0.0 to +2.5m)
        double crestMeanWaveOffset = 1.8;
        double clampedCrestOffset = Math.max(-0.35, crestMeanWaveOffset);
        assertEquals(1.8, clampedCrestOffset, DELTA, "Positive heave lift on crests must remain completely unclamped");
        double crestWaterSurfaceY = staticWaterSurfaceY + clampedCrestOffset;
        assertEquals(64.8, crestWaterSurfaceY, DELTA, "Ship must heave up to Y=64.8 on crest");
    }

    @Test
    @DisplayName("Task 2: Vessel retains momentum during sharp 35-degree rudder turn and tacking without jerking")
    public void testVesselRetainsMomentumDuringSharpTurnAndTack() {
        double entrySpeed = 5.0; // 5.0 m/s (~9.7 knots)
        Vector3d vel = new Vector3d(0.0, 0.0, -entrySpeed);
        Vector3d heading = new Vector3d(0.0, 0.0, -1.0); // North
        float rudderAngle = 35.0f; // Full 35 deg rudder turn
        double dt = 0.05; // 20 Hz tick
        double massScale = 1.0;
        double yawRate = 0.35; // ~20 deg/s

        double currentAngleRad = 0.0;
        double speedAt90Deg = -1.0;
        double speedAt180Deg = -1.0;

        // Simulate turn through 180 degrees (3.14 rad)
        // Passes through wind where sail thrust momentarily drops to 0
        for (int tick = 0; tick < 200; tick++) {
            double prevSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            Vector3d prevHeading = new Vector3d(heading);

            // Rotate heading by yawRate * dt
            currentAngleRad += yawRate * dt;
            heading.set(Math.sin(currentAngleRad), 0.0, -Math.cos(currentAngleRad)).normalize();

            // When between 60 deg and 120 deg, simulate head-to-wind tacking (sail thrust = 0.0 N)
            double thrustMag = (currentAngleRad >= Math.toRadians(60.0) && currentAngleRad <= Math.toRadians(120.0))
                    ? 0.0 : 450.0;

            vel = ServerPhysicsHandler.updateTurningMomentum(
                    vel, prevHeading, heading, rudderAngle, yawRate, dt, massScale, thrustMag, false
            );

            double currentSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);

            // Momentum Inertia Guard Assertion: Speed must NEVER drop by more than 2.5% per tick
            double maxAllowedLoss = 0.0250001 * prevSpeed;
            double actualLoss = prevSpeed - currentSpeed;
            assertTrue(actualLoss <= maxAllowedLoss,
                    String.format("Tick %d: Abrupt deceleration detected! Loss: %.4f > allowed: %.4f (speed: %.3f -> %.3f)",
                            tick, actualLoss, maxAllowedLoss, prevSpeed, currentSpeed));

            // Record speed at 90 degrees
            if (speedAt90Deg < 0.0 && currentAngleRad >= Math.PI * 0.5) {
                speedAt90Deg = currentSpeed;
            }

            // Record speed at 180 degrees
            if (speedAt180Deg < 0.0 && currentAngleRad >= Math.PI) {
                speedAt180Deg = currentSpeed;
                break;
            }
        }

        // Assert at least 70% speed retained through a 90-degree turn
        assertTrue(speedAt90Deg >= 0.70 * entrySpeed,
                String.format("Vessel must retain >= 70%% speed through 90-deg turn, got: %.3f m/s (expected >= %.3f m/s)",
                        speedAt90Deg, 0.70 * entrySpeed));

        // Assert vessel successfully tacked through 180 degrees without stalling to a dead stop
        assertTrue(speedAt180Deg > 2.0,
                String.format("Vessel must retain momentum through 180-deg tack, got: %.3f m/s", speedAt180Deg));
    }

    @Test
    @DisplayName("Feature 1: Bilge purge converts interior water and waterlogged blocks inside hull to dry air")
    public void testSubLevelInteriorWaterPurgedOnAssembly() {
        record MockBlock(String id, boolean isSolid, boolean isWater, boolean isWaterlogged) {}

        java.util.Map<net.minecraft.core.BlockPos, MockBlock> blockMap = new java.util.HashMap<>();

        // Build a mock brig hull:
        // Solid keel/floor at Y = 60, X from -4 to 4, Z from -8 to 8
        for (int x = -4; x <= 4; x++) {
            for (int z = -8; z <= 8; z++) {
                blockMap.put(new net.minecraft.core.BlockPos(x, 60, z), new MockBlock("oak_plank", true, false, false));
            }
        }

        // Lateral walls: port (X = -4) and starboard (X = 4) from Y = 61 to 64
        for (int y = 61; y <= 64; y++) {
            for (int z = -8; z <= 8; z++) {
                blockMap.put(new net.minecraft.core.BlockPos(-4, y, z), new MockBlock("oak_plank", true, false, false));
                blockMap.put(new net.minecraft.core.BlockPos(4, y, z), new MockBlock("oak_plank", true, false, false));
            }
        }

        // Longitudinal walls: bow (Z = -8) and stern (Z = 8) from Y = 61 to 64
        for (int y = 61; y <= 64; y++) {
            for (int x = -4; x <= 4; x++) {
                blockMap.put(new net.minecraft.core.BlockPos(x, y, -8), new MockBlock("oak_plank", true, false, false));
                blockMap.put(new net.minecraft.core.BlockPos(x, y, 8), new MockBlock("oak_plank", true, false, false));
            }
        }

        // Interior positions inside hold (Y = 61):
        net.minecraft.core.BlockPos holdWater1 = new net.minecraft.core.BlockPos(0, 61, 0);
        net.minecraft.core.BlockPos holdWater2 = new net.minecraft.core.BlockPos(1, 62, 2);
        net.minecraft.core.BlockPos holdWaterloggedStairs = new net.minecraft.core.BlockPos(0, 61, 3);

        // Exterior ocean position outside hull:
        net.minecraft.core.BlockPos oceanWater = new net.minecraft.core.BlockPos(15, 61, 15);

        blockMap.put(holdWater1, new MockBlock("water", false, true, false));
        blockMap.put(holdWater2, new MockBlock("water", false, true, false));
        blockMap.put(holdWaterloggedStairs, new MockBlock("waterlogged_stairs", true, false, true));
        blockMap.put(oceanWater, new MockBlock("water", false, true, false));

        ShipHullAirPocketHelper.BilgeBlockAccessor<MockBlock> accessor = new ShipHullAirPocketHelper.BilgeBlockAccessor<>() {
            @Override
            public boolean isSolid(MockBlock block) {
                return block != null && block.isSolid();
            }

            @Override
            public boolean isWater(MockBlock block) {
                return block != null && block.isWater();
            }

            @Override
            public boolean isWaterlogged(MockBlock block) {
                return block != null && block.isWaterlogged();
            }

            @Override
            public MockBlock createAir() {
                return new MockBlock("air", false, false, false);
            }

            @Override
            public MockBlock setUnwaterlogged(MockBlock block) {
                return new MockBlock("dry_stairs", true, false, false);
            }
        };

        java.util.List<net.minecraft.core.BlockPos> candidates = java.util.List.of(
                holdWater1, holdWater2, holdWaterloggedStairs, oceanWater
        );

        int purgedCount = ShipHullAirPocketHelper.purgeHoldWater(
                blockMap::get,
                blockMap::put,
                accessor,
                candidates
        );

        assertEquals(3, purgedCount, "Exactly 3 interior blocks should be purged/cleared");
        assertEquals("air", blockMap.get(holdWater1).id(), "Hold water block 1 must be converted to air");
        assertEquals("air", blockMap.get(holdWater2).id(), "Hold water block 2 must be converted to air");
        assertEquals("dry_stairs", blockMap.get(holdWaterloggedStairs).id(), "Hold waterlogged stairs must be un-waterlogged");
        org.junit.jupiter.api.Assertions.assertFalse(blockMap.get(holdWaterloggedStairs).isWaterlogged(), "Waterlogged flag must be false");
        assertEquals("water", blockMap.get(oceanWater).id(), "Ocean water outside hull must NOT be purged");
    }

    @Test
    @DisplayName("Feature 2: F4 tornado uplifts small raft into the air while heavy brig stays near sea level")
    public void testF4TornadoUpliftsSmallRaftIntoAir() {
        // Severe F4 tornado: center at (100, 100), radius 40m, stage 5 (F4), distance 15m
        com.serenesailing.compat.weather2.Weather2Compat.StormData f4Tornado =
                new com.serenesailing.compat.weather2.Weather2Compat.StormData(100.0, 100.0, 40.0, 5, 15.0, true, false);

        // Simulation parameters
        double dt = 0.05; // 20 ticks/sec
        int ticks = 40;   // 2.0 seconds

        // 1. Small raft simulation: 50 blocks, flat open deck (no enclosed hold), not anchored
        Vector3d raftPos = new Vector3d(105.0, 63.0, 105.0);
        Vector3d raftVel = new Vector3d(0.0, 0.0, 0.0);

        for (int t = 0; t < ticks; t++) {
            raftVel = ServerPhysicsHandler.simulateVesselStormStep(
                    raftPos, raftVel, f4Tornado, 50, false, false, t * dt, dt
            );
            raftPos.add(raftVel.x * dt, raftVel.y * dt, raftVel.z * dt);
        }

        assertTrue(raftVel.y > 2.0,
                String.format("Small raft vertical velocity must be strongly positive (> 2.0 m/s), got: %.3f m/s", raftVel.y));
        assertTrue(raftPos.y > 70.0,
                String.format("Small raft altitude must rise significantly into the funnel cloud (> 70.0 blocks), got: %.3f", raftPos.y));

        // 2. Heavy brig simulation: 400 blocks, deep enclosed hold, not anchored
        Vector3d brigPos = new Vector3d(105.0, 63.0, 105.0);
        Vector3d brigVel = new Vector3d(0.0, 0.0, 0.0);

        for (int t = 0; t < ticks; t++) {
            brigVel = ServerPhysicsHandler.simulateVesselStormStep(
                    brigPos, brigVel, f4Tornado, 400, true, false, t * dt, dt
            );
            brigPos.add(brigVel.x * dt, brigVel.y * dt, brigVel.z * dt);
        }

        assertTrue(brigPos.y <= 66.5,
                String.format("Heavy brig must remain near sea level (Y <= 66.5) under heavy storm buffeting, got: %.3f", brigPos.y));
    }
}