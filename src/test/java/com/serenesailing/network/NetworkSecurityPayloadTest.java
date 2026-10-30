package com.serenesailing.network;

import com.serenesailing.event.ServerPhysicsHandler;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security & boundary validation test suite for Serene Sailing network payloads
 * and server cache interfaces.
 */
public class NetworkSecurityPayloadTest {

    @Test
    @DisplayName("ServerboundSteerPayload guards against NaN and Infinity angle injection")
    public void testServerboundSteerPayloadNaNAndInfinity() {
        // NaN injection attack
        ServerboundSteerPayload nanPayload = new ServerboundSteerPayload(new BlockPos(10, 64, 10), Float.NaN, true);
        assertFalse(Float.isNaN(nanPayload.rudderAngle()), "Rudder angle must not be NaN");
        assertEquals(0.0f, nanPayload.rudderAngle(), 1e-6f, "NaN angle must be sanitized to 0.0f");

        // Positive Infinity injection attack
        ServerboundSteerPayload posInfPayload = new ServerboundSteerPayload(new BlockPos(10, 64, 10), Float.POSITIVE_INFINITY, false);
        assertFalse(Float.isInfinite(posInfPayload.rudderAngle()), "Rudder angle must not be Infinite");
        assertEquals(0.0f, posInfPayload.rudderAngle(), 1e-6f, "Positive Infinity must be sanitized to 0.0f");

        // Negative Infinity injection attack
        ServerboundSteerPayload negInfPayload = new ServerboundSteerPayload(new BlockPos(10, 64, 10), Float.NEGATIVE_INFINITY, false);
        assertFalse(Float.isInfinite(negInfPayload.rudderAngle()), "Rudder angle must not be Infinite");
        assertEquals(0.0f, negInfPayload.rudderAngle(), 1e-6f, "Negative Infinity must be sanitized to 0.0f");
    }

    @Test
    @DisplayName("ServerboundSteerPayload strictly clamps out-of-bounds angles")
    public void testServerboundSteerPayloadClamping() {
        ServerboundSteerPayload high = new ServerboundSteerPayload(new BlockPos(0, 0, 0), 1000.0f, false);
        assertEquals(35.0f, high.rudderAngle(), 1e-6f, "Angle above +35 must clamp to +35");

        ServerboundSteerPayload low = new ServerboundSteerPayload(new BlockPos(0, 0, 0), -500.0f, false);
        assertEquals(-35.0f, low.rudderAngle(), 1e-6f, "Angle below -35 must clamp to -35");

        ServerboundSteerPayload normal = new ServerboundSteerPayload(new BlockPos(0, 0, 0), 20.0f, false);
        assertEquals(20.0f, normal.rudderAngle(), 1e-6f, "Normal angle must remain intact");
    }

    @Test
    @DisplayName("ServerboundSteerPayload handles null BlockPos gracefully")
    public void testServerboundSteerPayloadNullPos() {
        ServerboundSteerPayload nullPosPayload = new ServerboundSteerPayload(null, 15.0f, false);
        assertNotNull(nullPosPayload.pos(), "Null BlockPos must default to non-null position");
        assertEquals(BlockPos.ZERO, nullPosPayload.pos());
    }

    @Test
    @DisplayName("SetHelmAnglePayload guards against NaN and Infinity angle injection")
    public void testSetHelmAnglePayloadNaNAndInfinity() {
        SetHelmAnglePayload nanPayload = new SetHelmAnglePayload(new BlockPos(5, 64, 5), Float.NaN);
        assertFalse(Float.isNaN(nanPayload.angle()), "Angle must not be NaN");
        assertEquals(0.0f, nanPayload.angle(), 1e-6f, "NaN angle must be sanitized to 0.0f");

        SetHelmAnglePayload infPayload = new SetHelmAnglePayload(new BlockPos(5, 64, 5), Float.POSITIVE_INFINITY);
        assertFalse(Float.isInfinite(infPayload.angle()), "Angle must not be Infinite");
        assertEquals(0.0f, infPayload.angle(), 1e-6f, "Positive Infinity must be sanitized to 0.0f");
    }

    @Test
    @DisplayName("SetHelmAnglePayload strictly clamps out-of-bounds angles")
    public void testSetHelmAnglePayloadClamping() {
        SetHelmAnglePayload high = new SetHelmAnglePayload(new BlockPos(0, 0, 0), 90.0f);
        assertEquals(45.0f, high.angle(), 1e-6f, "Angle above +45 must clamp to +45");

        SetHelmAnglePayload low = new SetHelmAnglePayload(new BlockPos(0, 0, 0), -120.0f);
        assertEquals(-45.0f, low.angle(), 1e-6f, "Angle below -45 must clamp to -45");
    }

    @Test
    @DisplayName("SetHelmAnglePayload handles null BlockPos gracefully")
    public void testSetHelmAnglePayloadNullPos() {
        SetHelmAnglePayload nullPosPayload = new SetHelmAnglePayload(null, 10.0f);
        assertNotNull(nullPosPayload.pos(), "Null BlockPos must default to non-null position");
        assertEquals(BlockPos.ZERO, nullPosPayload.pos());
    }

    @Test
    @DisplayName("ServerPhysicsHandler handles null UUIDs and NaN angles safely without NPE")
    public void testServerPhysicsHandlerNullAndNaNSafety() {
        // Null subLevel UUID tests
        assertDoesNotThrow(() -> ServerPhysicsHandler.setHelmAngle(null, 25.0f));
        assertEquals(0.0f, ServerPhysicsHandler.getHelmAngle(null));
        assertNotNull(ServerPhysicsHandler.getVesselVelocity(null));
        assertEquals(0.0, ServerPhysicsHandler.getForwardSpeed(null));
        assertDoesNotThrow(() -> ServerPhysicsHandler.invalidateSteeringCache(null));

        // Null dimension test
        assertNotNull(ServerPhysicsHandler.getDimensionWind(null));
        assertEquals(0.0f, ServerPhysicsHandler.getDimensionWind(null).speed());

        // NaN angle in setHelmAngle
        UUID validId = UUID.randomUUID();
        ServerPhysicsHandler.setHelmAngle(validId, Float.NaN);
        assertEquals(0.0f, ServerPhysicsHandler.getHelmAngle(validId), 1e-6f, "NaN helm angle must sanitize to 0.0f");

        ServerPhysicsHandler.setHelmAngle(validId, Float.POSITIVE_INFINITY);
        assertEquals(0.0f, ServerPhysicsHandler.getHelmAngle(validId), 1e-6f, "Infinite helm angle must sanitize to 0.0f");

        ServerPhysicsHandler.setHelmAngle(validId, 15.5f);
        assertEquals(15.5f, ServerPhysicsHandler.getHelmAngle(validId), 1e-6f);
    }
}
