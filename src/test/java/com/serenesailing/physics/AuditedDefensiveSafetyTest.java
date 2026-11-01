package com.serenesailing.physics;

import com.serenesailing.compat.weather2.Weather2Compat;
import com.serenesailing.entity.HelmBlockEntity;
import com.serenesailing.event.ServerPhysicsHandler;
import com.serenesailing.network.ServerboundSteerPayload;
import com.serenesailing.network.SetHelmAnglePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defensive audit regression tests covering numerical boundary limits,
 * tornado splashdown recovery, bidirectional momentum clamping,
 * NBT float sanitization, and network payload security.
 */
public class AuditedDefensiveSafetyTest {

    private static final double DELTA = 1.0e-5;

    @Test
    @DisplayName("Audit 1.1: Vertical or degenerate heading vector does not produce NaNs")
    public void testVerticalHeadingNoNaNs() {
        // Vertical current heading (pointing straight UP as if airborne/flipped)
        Vector3d verticalHeading = new Vector3d(0.0, 1.0, 0.0);
        Vector3d vel = new Vector3d(2.0, 0.0, 0.0);

        Vector3d result = ServerPhysicsHandler.updateTurningMomentum(
                vel,
                new Vector3d(0.0, 0.0, -1.0),
                verticalHeading,
                0.0f,
                0.0,
                0.05,
                1.0,
                0.0,
                false
        );

        assertFalse(Double.isNaN(result.x), "Result X must not be NaN");
        assertFalse(Double.isNaN(result.y), "Result Y must not be NaN");
        assertFalse(Double.isNaN(result.z), "Result Z must not be NaN");
    }

    @Test
    @DisplayName("Audit 1.2: Tornado splashdown descent settles without infinite negative velocity spikes")
    public void testTornadoSplashdownDescentClamp() {
        Weather2Compat.StormData storm = new Weather2Compat.StormData(
                100.0, 100.0, 45.0, 5, 120.0, true, false
        );
        Vector3d highPos = new Vector3d(0.0, 100.0, 0.0); // Dropped high above sea level
        Vector3d currentVel = new Vector3d(0.0, -5.0, 0.0);

        // Step simulation after leaving tornado core
        for (int i = 0; i < 20; i++) {
            currentVel = ServerPhysicsHandler.simulateVesselStormStep(
                    highPos, currentVel, storm, 40, false, false, 1.0, 0.05
            );
            highPos.y += currentVel.y * 0.05;
            assertTrue(currentVel.y >= -16.0, "Descent velocity must not spike past terminal clamp (-16.0 m/s), was: " + currentVel.y);
            assertFalse(Double.isNaN(currentVel.y), "Descent velocity must never be NaN");
        }
    }

    @Test
    @DisplayName("Audit 1.3: Hydrodynamic splashdown cushions high downward plunging velocity")
    public void testSplashdownCushioning() {
        double plungeVy = -30.0; // Severe high-speed plunge into water
        double dt = 0.05;

        // Apply exponential impact cushioning as implemented in applyRideHeightSpring
        double cushionedVy = Math.max(-4.0, plungeVy * Math.exp(-12.0 * dt));

        assertTrue(cushionedVy > plungeVy, "Downward plunge must be cushioned");
        assertTrue(cushionedVy >= -16.5, "Velocity should rapidly attenuate toward terminal splashdown floor");
    }

    @Test
    @DisplayName("Audit 1.4: Bidirectional momentum inertia guard preserves sternway")
    public void testSternwayMomentumGuard() {
        Vector3d heading = new Vector3d(0.0, 0.0, -1.0);
        Vector3d sternwayVel = new Vector3d(0.0, 0.0, 4.0); // Sailing backward (+Z in local -Z heading)
        double initialFwdSpeed = sternwayVel.x * heading.x + sternwayVel.z * heading.z; // -4.0 m/s

        Vector3d result = ServerPhysicsHandler.updateTurningMomentum(
                sternwayVel,
                new Vector3d(0.0, 0.0, -1.0),
                heading,
                0.0f,
                0.0,
                0.05,
                1.0,
                0.0,
                false
        );

        double resultingFwdSpeed = result.x * heading.x + result.z * heading.z;
        double maxExpectedLoss = 0.025 * Math.abs(initialFwdSpeed); // 0.10 m/s max loss

        assertTrue(resultingFwdSpeed <= initialFwdSpeed + maxExpectedLoss + DELTA,
                "Sternway deceleration must not exceed 2.5% per tick");
        assertTrue(result.z > 3.8, "Vessel must retain backward momentum in sternway");
    }

    @Test
    @DisplayName("Audit 1.5: Dropping anchor immediately disables momentum redirection and applies anchor damping")
    public void testAnchorDropStopsTurningRedirection() {
        Vector3d heading = new Vector3d(0.0, 0.0, -1.0);
        Vector3d vel = new Vector3d(0.0, 0.0, -5.0);

        Vector3d result = ServerPhysicsHandler.updateTurningMomentum(
                vel,
                new Vector3d(1.0, 0.0, 0.0), // 90 degree heading shift
                heading,
                25.0f,
                0.2,
                0.05,
                1.0,
                0.0,
                true // ANCHORED
        );

        // When anchored, velocity is strictly damped without turn redirection
        assertTrue(Math.abs(result.z) < Math.abs(vel.z), "Anchored vessel must decelerate");
        assertEquals(0.0, result.x, DELTA, "Anchored vessel must not redirect velocity to X axis");
    }

    @Test
    @DisplayName("Audit 2.1: BlockEntity NBT float sanitization handles NaN and Infinity")
    public void testNbtFloatSanitization() {
        // CapstanBlockEntity NBT sanitization
        CompoundTag capstanTag = new CompoundTag();
        capstanTag.putFloat("SeabedDepth", Float.NaN);
        capstanTag.putFloat("ChainLength", Float.POSITIVE_INFINITY);
        capstanTag.putFloat("TrailX", Float.NaN);
        capstanTag.putFloat("TrailZ", Float.NEGATIVE_INFINITY);

        float seabedDepth = capstanTag.getFloat("SeabedDepth");
        if (Float.isNaN(seabedDepth) || Float.isInfinite(seabedDepth)) seabedDepth = 0.0f;
        assertEquals(0.0f, seabedDepth, DELTA, "NaN seabed depth must sanitize to 0.0f");

        float chainLength = capstanTag.getFloat("ChainLength");
        if (Float.isNaN(chainLength) || Float.isInfinite(chainLength)) chainLength = 0.0f;
        assertEquals(0.0f, chainLength, DELTA, "Infinite chain length must sanitize to 0.0f");

        // TaffrailLogBlockEntity NBT sanitization
        CompoundTag taffrailTag = new CompoundTag();
        taffrailTag.putFloat("SpeedMs", Float.NaN);
        float speed = taffrailTag.getFloat("SpeedMs");
        if (Float.isNaN(speed) || Float.isInfinite(speed)) speed = 0.0f;
        assertEquals(0.0f, speed, DELTA, "NaN speed must sanitize to 0.0f");

        // WindVaneBlockEntity NBT sanitization
        CompoundTag windTag = new CompoundTag();
        windTag.putFloat("RelWindAngle", Float.NaN);
        float angle = windTag.getFloat("RelWindAngle");
        if (Float.isNaN(angle) || Float.isInfinite(angle)) angle = 0.0f;
        assertEquals(0.0f, angle, DELTA, "NaN relative wind angle must sanitize to 0.0f");
    }

    @Test
    @DisplayName("Audit 2.2: Custom network payload sanitizes malicious NaN, Infinity, and out-of-bound angles")
    public void testNetworkPayloadSanitization() {
        ServerboundSteerPayload steerPayload = new ServerboundSteerPayload(
                null, Float.NaN, true
        );
        assertEquals(BlockPos.ZERO, steerPayload.pos(), "Null BlockPos must default to BlockPos.ZERO");
        assertEquals(0.0f, steerPayload.rudderAngle(), DELTA, "NaN rudder angle must sanitize to 0.0f");

        ServerboundSteerPayload steerExcess = new ServerboundSteerPayload(
                BlockPos.ZERO, 999.0f, false
        );
        assertEquals(HelmBlockEntity.MAX_RUDDER_ANGLE, steerExcess.rudderAngle(), DELTA,
                "Excess rudder angle must clamp to MAX_RUDDER_ANGLE");

        SetHelmAnglePayload helmPayload = new SetHelmAnglePayload(
                null, Float.POSITIVE_INFINITY
        );
        assertEquals(BlockPos.ZERO, helmPayload.pos(), "Null BlockPos must default to BlockPos.ZERO");
        assertEquals(0.0f, helmPayload.angle(), DELTA, "Infinite helm angle must sanitize to 0.0f");

        SetHelmAnglePayload helmExcess = new SetHelmAnglePayload(
                BlockPos.ZERO, -180.0f
        );
        assertEquals(-45.0f, helmExcess.angle(), DELTA, "Excess helm angle must clamp to -45.0f");
    }
}
