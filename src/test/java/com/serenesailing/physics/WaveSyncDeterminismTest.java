package com.serenesailing.physics;

import com.serenesailing.physics.waves.GerstnerWaveEngine;
import com.serenesailing.physics.waves.WaveSurface;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the invariants that keep the wave mesh a player sees and the surface physics rides identical.
 */
class WaveSyncDeterminismTest {

    private static void setTargets(GerstnerWaveEngine.DimensionWaveState s) {
        s.targetStormIntensity = 0.8;
        s.targetWindSpeed = 1.9;
        s.targetWindDirX = 0.6;
        s.targetWindDirZ = 0.8;
    }

    @Test
    @DisplayName("Server stepping every tick == remote client replaying from a snapshot taken earlier")
    void snapshotReplayMatchesServer() {
        var server = new GerstnerWaveEngine.DimensionWaveState();
        server.serverDriven = true; // targets injected below instead of derived from a Level
        setTargets(server);
        server.lastUpdateTick = 0L;

        var client = new GerstnerWaveEngine.DimensionWaveState();
        for (long t = 1; t <= 100; t++) {
            server.update(null, t);
            if (t == 60) { // snapshot sent at tick 60; the client applies it and replays the 40 ticks that follow
                client.applyServerSnapshot(t,
                        server.smoothedWindSpeed, server.smoothedWindDirX, server.smoothedWindDirZ,
                        server.smoothedStormIntensity,
                        server.targetWindSpeed, server.targetWindDirX, server.targetWindDirZ,
                        server.targetStormIntensity);
            }
        }
        client.update(null, 100L); // state is still far from converged here, so this is a real test
        assertEquals(server.smoothedStormIntensity, client.smoothedStormIntensity, 1e-12);
        assertEquals(server.smoothedWindDirX, client.smoothedWindDirX, 1e-12);
        assertEquals(server.smoothedWindDirZ, client.smoothedWindDirZ, 1e-12);
    }

    @Test
    @DisplayName("update() is idempotent per tick and replays skipped ticks")
    void updateIsIdempotentAndCatchesUp() {
        var a = new GerstnerWaveEngine.DimensionWaveState();
        var b = new GerstnerWaveEngine.DimensionWaveState();
        a.serverDriven = true; b.serverDriven = true;
        setTargets(a); setTargets(b);
        a.lastUpdateTick = 0L; b.lastUpdateTick = 0L;

        for (long t = 1; t <= 50; t++) {
            a.update(null, t);
            a.update(null, t); // repeated call in the same tick must not advance the state
        }
        b.update(null, 50L);   // one call that has to replay 50 ticks
        assertEquals(a.smoothedStormIntensity, b.smoothedStormIntensity, 1e-12);
    }

    @Test
    @DisplayName("A clock that goes backwards (new world) resynchronises instead of freezing")
    void clockReset() {
        var s = new GerstnerWaveEngine.DimensionWaveState();
        s.serverDriven = true;
        setTargets(s);
        s.lastUpdateTick = 1_000_000L;
        double before = s.smoothedStormIntensity;
        s.update(null, 10L);
        assertNotEquals(before, s.smoothedStormIntensity);
    }

    @Test
    @DisplayName("shape(): C1-smooth one-sided floor, never below zero, ~identity for crests")
    void shapeFloor() {
        for (double h = -5.0; h <= 5.0; h += 0.01) {
            assertTrue(WaveSurface.shape(h) > 0.0);
            assertTrue(WaveSurface.shape(h) >= Math.max(h, 0.0) - 1e-12);
        }
        assertEquals(2.0, WaveSurface.shape(2.0), 0.02);
        assertTrue(WaveSurface.shape(-3.0) < 0.01);
        // monotonic and continuous
        double prev = WaveSurface.shape(-5.0);
        for (double h = -4.99; h <= 5.0; h += 0.01) {
            double v = WaveSurface.shape(h);
            assertTrue(v >= prev && v - prev < 0.011);
            prev = v;
        }
    }

    @Test
    @DisplayName("ChopField reproduces the renderer's original three harmonics")
    void chopFieldMatchesLegacyRendererMath() {
        double storm = 0.6, wx = 0.6, wz = 0.8, x = 123.4, z = -56.7, t = 12.3;
        double windAngle = Math.atan2(wz, wx);
        double scale = GerstnerWaveEngine.getActiveWaveScale(storm);
        double ss = storm > 0.4 ? (1.0 + (storm - 0.4) * 0.8) : 1.0;
        double a1 = (0.12 * storm + 0.035) * ss * scale, a2 = (0.07 * storm + 0.020) * ss * scale;
        double a3 = (storm > 0.25 ? (0.055 * storm + 0.015) : 0.0) * ss * scale;
        double legacy =
                Math.sin(0.5712 * (Math.cos(windAngle + 0.30) * x + Math.sin(windAngle + 0.30) * z) - 2.367 * t) * a1
              + Math.sin(1.0833 * (Math.cos(windAngle - 0.40) * x + Math.sin(windAngle - 0.40) * z) - 3.259 * t) * a2
              + Math.sin(1.8480 * (Math.cos(windAngle + 0.733) * x + Math.sin(windAngle + 0.733) * z) - 4.150 * t) * a3;
        assertEquals(legacy, new GerstnerWaveEngine.ChopField(storm, wx, wz).offset(x, z, t), 1e-12);
    }
}

