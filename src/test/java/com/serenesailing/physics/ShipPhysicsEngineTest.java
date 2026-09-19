package com.serenesailing.physics;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hand-calculated unit tests for ShipPhysicsEngine.evaluatePhysics().
 *
 * These run with plain JOML vectors and hand-picked hull configurations -
 * no Sable, no Minecraft, no game loop. Each test's expected numbers are
 * derived on paper in the comment above it; if the assertion fails, the
 * bug is in the math itself, not in Sable wiring, deltaTime, or hook points.
 *
 * Requires: JUnit 5 (org.junit.jupiter:junit-jupiter) on the test classpath.
 * Add to build.gradle if not already present:
 *
 *   dependencies {
 *       testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
 *   }
 *   test {
 *       useJUnitPlatform()
 *   }
 */
public class ShipPhysicsEngineTest {

    private static final double DELTA = 1e-2; // absolute tolerance for force/torque comparisons

    private static void assertVec(Vector3d expected, Vector3d actual, String label) {
        assertEquals(expected.x, actual.x, DELTA, label + " .x");
        assertEquals(expected.y, actual.y, DELTA, label + " .y");
        assertEquals(expected.z, actual.z, DELTA, label + " .z");
    }

    // ------------------------------------------------------------------
    // TEST 1: Symmetric vertical stack - buoyancy, gravity, COM, COB.
    //
    // Hull: two 1m^3 blocks stacked vertically, 100kg each.
    //   Block A at world (0,0,0)
    //   Block B at world (0,1,0)
    // Flat waterline at y = 0.25.
    //
    // Block A: bottom=-0.5, top=0.5, waterY=0.25 is between them.
    //   submergedFraction = (0.25 - (-0.5)) / (0.5 - (-0.5)) = 0.75 / 1.0 = 0.75
    // Block B: bottom=0.5, top=1.5, waterY=0.25 < bottom -> submergedFraction = 0
    //
    // totalMass = 200 kg
    // COM = (100*(0,0,0) + 100*(0,1,0)) / 200 = (0, 0.5, 0)
    // submergedVolume = 0.75 (only block A contributes)
    // COB = (0,0,0) * 0.75 / 0.75 = (0, 0, 0)   [block A's worldPos, weighted]
    //
    // gravityForce  = (0, -200 * 9.81, 0)              = (0, -1962.0, 0)
    // buoyantForce  = (0, 1000 * 9.81 * 0.75 * 1.0, 0) = (0,  7357.5, 0)
    // totalForce    = (0, 5395.5, 0)
    //
    // rightingTorque: offset = COB - COM = (0, -0.5, 0), which is parallel
    // to buoyantForce (0, 7357.5, 0) -> cross product of two parallel
    // vectors is zero -> torque = (0, 0, 0).
    // ------------------------------------------------------------------
    @Test
    void symmetricVerticalStack_buoyancyAndComComputeCorrectly() {
        var blockA = new ShipPhysicsEngine.BlockNode(new Vector3d(0, 0, 0), new Vector3d(0, 0, 0), 100.0);
        var blockB = new ShipPhysicsEngine.BlockNode(new Vector3d(0, 1, 0), new Vector3d(0, 1, 0), 100.0);

        SailingPhysicsConfig config = new SailingPhysicsConfig();
        WaterlineProvider flatAt025 = (x, z, t) -> 0.25;

        ShipPhysicsEngine.PhysicsState state = ShipPhysicsEngine.evaluatePhysics(
                List.of(blockA, blockB),
                new Vector3d(0, 0, 0),   // linear velocity
                new Vector3d(0, 0, 0),   // angular velocity
                new Vector3d(0, 0, 0),   // sail position (unused, area=0)
                new Vector3d(0, 0, 1),   // sail normal
                new Vector3d(0, 0, 0),   // wind vector (unused, area=0)
                0.0,                      // sail area -> sail branch skipped
                flatAt025,
                0.0,
                config
        );

        assertEquals(200.0, state.totalMass, DELTA, "totalMass");
        assertEquals(0.75, state.submergedVolume, DELTA, "submergedVolume");
        assertVec(new Vector3d(0, 0.5, 0), state.centerOfMass, "centerOfMass");
        assertVec(new Vector3d(0, 0, 0), state.centerOfBuoyancy, "centerOfBuoyancy");
        assertVec(new Vector3d(0, 5395.5, 0), state.totalForce, "totalForce");
        assertVec(new Vector3d(0, 0, 0), state.totalTorque, "totalTorque");
    }

    // ------------------------------------------------------------------
    // TEST 2: Asymmetric mass distribution - righting torque sign/magnitude.
    //
    // Hull: two fully-submerged 1m^3 blocks side by side on the x-axis.
    //   Heavy block at world (-1, 0, 0), mass = 200 kg
    //   Light block at world ( 1, 0, 0), mass = 100 kg
    // Waterline flat at y = 10 (both blocks fully underwater: fraction = 1.0 each).
    //
    // totalMass = 300 kg
    // COM = (200*(-1,0,0) + 100*(1,0,0)) / 300 = (-100,0,0)/300 = (-1/3, 0, 0)
    // submergedVolume = 1 + 1 = 2
    // COB = (1*(-1,0,0) + 1*(1,0,0)) / 2 = (0,0,0)/2 = (0, 0, 0)
    //
    // gravityForce = (0, -300 * 9.81, 0)             = (0, -2943.0, 0)
    // buoyantForce = (0, 1000 * 9.81 * 2 * 1.0, 0)   = (0, 19620.0, 0)
    // totalForce   = (0, 16677.0, 0)
    //
    // buoyancyOffset = COB - COM = (0,0,0) - (-1/3,0,0) = (1/3, 0, 0)
    // rightingTorque = (buoyancyOffset x buoyantForce) * metacentricTorqueScale
    //   cross((1/3,0,0), (0,19620,0)) = (0*0-0*19620, 0*0-(1/3)*0, (1/3)*19620-0*0)
    //                                  = (0, 0, 6540.0)
    //   * scale (0.85) = (0, 0, 5559.0)
    // ------------------------------------------------------------------
    @Test
    void asymmetricLoad_producesCorrectRightingTorqueSignAndMagnitude() {
        var heavy = new ShipPhysicsEngine.BlockNode(new Vector3d(-1, 0, 0), new Vector3d(-1, 0, 0), 200.0);
        var light = new ShipPhysicsEngine.BlockNode(new Vector3d(1, 0, 0), new Vector3d(1, 0, 0), 100.0);

        SailingPhysicsConfig config = new SailingPhysicsConfig(); // metacentricTorqueScale = 0.85
        WaterlineProvider flatAt10 = (x, z, t) -> 10.0; // fully submerges both 1m-tall blocks

        ShipPhysicsEngine.PhysicsState state = ShipPhysicsEngine.evaluatePhysics(
                List.of(heavy, light),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 1),
                new Vector3d(0, 0, 0),
                0.0,
                flatAt10,
                0.0,
                config
        );

        assertVec(new Vector3d(-1.0 / 3.0, 0, 0), state.centerOfMass, "centerOfMass");
        assertVec(new Vector3d(0, 0, 0), state.centerOfBuoyancy, "centerOfBuoyancy");
        assertVec(new Vector3d(0, 16677.0, 0), state.totalForce, "totalForce");
        assertVec(new Vector3d(0, 0, 5559.0), state.totalTorque, "rightingTorque");
    }

    // ------------------------------------------------------------------
    // TEST 3a: Sail thrust, face-on wind (cosAngle = 1.0).
    //
    // Single block at origin, mass 100, waterline far below -> not submerged,
    // so buoyancy is zero and only gravity + sail force act.
    //
    // sailPosition = (0, 1, 0), sailNormal = (0, 0, 1) [facing +z], area = 10 m^2
    // wind = (0, 0, 10) [blowing straight onto the sail face], ship velocity = 0
    // apparentWind = wind - velocity = (0, 0, 10), windSpeed = 10, windDir = (0,0,1)
    // cosAngle = dot((0,0,1),(0,0,1)) = 1.0
    //
    // sailForceMag = area * windSpeed^2 * dragCoeff * cosAngle
    //              = 10 * 100 * 1.2 * 1.0 = 1200.0
    // sailForce = (0, 0, 1200.0)
    //
    // gravityForce = (0, -100*9.81, 0) = (0, -981.0, 0)
    // totalForce = (0, -981.0, 1200.0)
    //
    // heelingTorque = (sailPosition - COM) x sailForce
    //   sailLever = (0,1,0) - (0,0,0) = (0,1,0)
    //   cross((0,1,0),(0,0,1200)) = (1*1200-0*0, 0*0-0*1200, 0*0-1*0) = (1200, 0, 0)
    // ------------------------------------------------------------------
    @Test
    void sailThrust_faceOnWind_fullThrustAndCorrectHeelingTorque() {
        var block = new ShipPhysicsEngine.BlockNode(new Vector3d(0, 0, 0), new Vector3d(0, 0, 0), 100.0);
        SailingPhysicsConfig config = new SailingPhysicsConfig(); // sailDragCoefficient = 1.2
        WaterlineProvider neverSubmerged = (x, z, t) -> -100.0;

        ShipPhysicsEngine.PhysicsState state = ShipPhysicsEngine.evaluatePhysics(
                List.of(block),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 1, 0),   // sail position
                new Vector3d(0, 0, 1),   // sail normal, facing +z
                new Vector3d(0, 0, 10),  // wind blowing +z
                10.0,                     // sail area
                neverSubmerged,
                0.0,
                config
        );

        assertEquals(0.0, state.submergedVolume, DELTA, "submergedVolume (should be dry)");
        assertVec(new Vector3d(0, -981.0, 1200.0), state.totalForce, "totalForce");
        assertVec(new Vector3d(1200.0, 0, 0), state.totalTorque, "heelingTorque");
    }

    // ------------------------------------------------------------------
    // TEST 3b: Sail thrust, wind perpendicular to sail face -> zero thrust.
    // sailNormal = (0,0,1), wind = (1,0,0) -> dot = 0 -> cosAngle clamps to 0.
    // Only gravity should act.
    // ------------------------------------------------------------------
    @Test
    void sailThrust_perpendicularWind_producesZeroThrust() {
        var block = new ShipPhysicsEngine.BlockNode(new Vector3d(0, 0, 0), new Vector3d(0, 0, 0), 100.0);
        SailingPhysicsConfig config = new SailingPhysicsConfig();
        WaterlineProvider neverSubmerged = (x, z, t) -> -100.0;

        ShipPhysicsEngine.PhysicsState state = ShipPhysicsEngine.evaluatePhysics(
                List.of(block),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 1, 0),
                new Vector3d(0, 0, 1),
                new Vector3d(1, 0, 0),   // wind perpendicular to sail face
                10.0,
                neverSubmerged,
                0.0,
                config
        );

        assertVec(new Vector3d(0, -981.0, 0), state.totalForce, "totalForce (gravity only)");
        assertVec(new Vector3d(0, 0, 0), state.totalTorque, "totalTorque (no heeling)");
    }

    // ------------------------------------------------------------------
    // TEST 3c: Sail thrust, backwinded (wind hits back of sail) -> clamped to zero,
    // not negative thrust. sailNormal = (0,0,1), wind = (0,0,-10).
    // ------------------------------------------------------------------
    @Test
    void sailThrust_backwindedSail_clampsToZeroNotNegative() {
        var block = new ShipPhysicsEngine.BlockNode(new Vector3d(0, 0, 0), new Vector3d(0, 0, 0), 100.0);
        SailingPhysicsConfig config = new SailingPhysicsConfig();
        WaterlineProvider neverSubmerged = (x, z, t) -> -100.0;

        ShipPhysicsEngine.PhysicsState state = ShipPhysicsEngine.evaluatePhysics(
                List.of(block),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 1, 0),
                new Vector3d(0, 0, 1),
                new Vector3d(0, 0, -10),  // wind hitting the back of the sail
                10.0,
                neverSubmerged,
                0.0,
                config
        );

        assertVec(new Vector3d(0, -981.0, 0), state.totalForce, "totalForce (gravity only, thrust clamped)");
    }

    // ------------------------------------------------------------------
    // TEST 4: Linear + angular hydrodynamic damping in isolation.
    //
    // Single fully-submerged 1m^3 block at origin, mass 100kg, no sail.
    // linearVelocity = (2, 0, 0), angularVelocity = (0, 0, 3)
    //
    // gravityForce = (0, -981.0, 0)
    // buoyantForce = (0, 1000*9.81*1*1.0, 0) = (0, 9810.0, 0)
    //
    // linearDrag = v * (-linearDampingCoeff * |v|) = (2,0,0) * (-250 * 2) = (-1000, 0, 0)
    // angularDrag = w * (-angularDampingCoeff * |w|) = (0,0,3) * (-1800 * 3) = (0, 0, -16200)
    //
    // totalForce = gravity + buoyant + linearDrag = (-1000, 8829.0, 0)
    // totalTorque = righting(0,0,0; COM==COB here) + angularDrag = (0, 0, -16200)
    // ------------------------------------------------------------------
    @Test
    void damping_linearAndAngular_matchQuadraticDragFormula() {
        var block = new ShipPhysicsEngine.BlockNode(new Vector3d(0, 0, 0), new Vector3d(0, 0, 0), 100.0);
        SailingPhysicsConfig config = new SailingPhysicsConfig(); // linearDampingCoeff=250, angularDampingCoeff=1800
        WaterlineProvider flatAt10 = (x, z, t) -> 10.0; // fully submerged

        ShipPhysicsEngine.PhysicsState state = ShipPhysicsEngine.evaluatePhysics(
                List.of(block),
                new Vector3d(2, 0, 0),   // linear velocity
                new Vector3d(0, 0, 3),   // angular velocity
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 1),
                new Vector3d(0, 0, 0),
                0.0,                      // no sail
                flatAt10,
                0.0,
                config
        );

        assertVec(new Vector3d(-1000.0, 8829.0, 0), state.totalForce, "totalForce with linear drag");
        assertVec(new Vector3d(0, 0, -16200.0), state.totalTorque, "totalTorque with angular drag");
    }

    // ------------------------------------------------------------------
    // TEST 5: Edge case - empty hull returns a zeroed, non-crashing state.
    // ------------------------------------------------------------------
    @Test
    void emptyHull_returnsZeroStateWithoutException() {
        SailingPhysicsConfig config = new SailingPhysicsConfig();

        ShipPhysicsEngine.PhysicsState state = ShipPhysicsEngine.evaluatePhysics(
                List.of(),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 1),
                new Vector3d(0, 0, 0),
                0.0,
                WaterlineProvider.FLAT_SEA,
                0.0,
                config
        );

        assertEquals(0.0, state.totalMass, DELTA, "totalMass");
        assertEquals(0.0, state.submergedVolume, DELTA, "submergedVolume");
        assertVec(new Vector3d(0, 0, 0), state.totalForce, "totalForce");
        assertVec(new Vector3d(0, 0, 0), state.totalTorque, "totalTorque");
    }

    // ------------------------------------------------------------------
    // TEST 6: Edge case - hull present but fully out of water (zero submerged
    // volume). Buoyancy/righting terms should be skipped cleanly, gravity
    // still applies, no NaN/divide-by-zero.
    // ------------------------------------------------------------------
    @Test
    void zeroSubmergedVolume_skipsBuoyancyWithoutNaNOrCrash() {
        var block = new ShipPhysicsEngine.BlockNode(new Vector3d(0, 5, 0), new Vector3d(0, 5, 0), 100.0);
        SailingPhysicsConfig config = new SailingPhysicsConfig();
        WaterlineProvider neverSubmerged = (x, z, t) -> -100.0;

        ShipPhysicsEngine.PhysicsState state = ShipPhysicsEngine.evaluatePhysics(
                List.of(block),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 1),
                new Vector3d(0, 0, 0),
                0.0,
                neverSubmerged,
                0.0,
                config
        );

        assertEquals(0.0, state.submergedVolume, DELTA);
        assertFalse(Double.isNaN(state.totalForce.x), "totalForce.x should not be NaN");
        assertFalse(Double.isNaN(state.totalForce.y), "totalForce.y should not be NaN");
        assertFalse(Double.isNaN(state.totalTorque.z), "totalTorque.z should not be NaN");
        assertVec(new Vector3d(0, -981.0, 0), state.totalForce, "totalForce (gravity only)");
    }

    // ------------------------------------------------------------------
    // TEST 7: Edge case - all blocks massless. Guards against divide-by-zero
    // in the center-of-mass average (totalMass == 0).
    // ------------------------------------------------------------------
    @Test
    void zeroMassHull_doesNotDivideByZeroOnCenterOfMass() {
        var block = new ShipPhysicsEngine.BlockNode(new Vector3d(0, 0, 0), new Vector3d(3, 0, 3), 0.0);
        SailingPhysicsConfig config = new SailingPhysicsConfig();
        WaterlineProvider flatAt10 = (x, z, t) -> 10.0;

        ShipPhysicsEngine.PhysicsState state = ShipPhysicsEngine.evaluatePhysics(
                List.of(block),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 1),
                new Vector3d(0, 0, 0),
                0.0,
                flatAt10,
                0.0,
                config
        );

        assertEquals(0.0, state.totalMass, DELTA);
        assertFalse(Double.isNaN(state.centerOfMass.x), "centerOfMass should default, not NaN");
        assertVec(new Vector3d(0, 0, 0), state.centerOfMass, "centerOfMass defaults to origin when massless");
    }
}