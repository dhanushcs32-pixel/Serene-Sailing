package com.serenesailing.physics;

import com.serenesailing.physics.waves.WaveResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaveResponseTest {

    @Test
    @DisplayName("A raft / small boat keeps the full (legacy) wave response, which is the maximum")
    void smallBoatIsTheMaximum() {
        for (int blocks : new int[] {1, 10, 20}) {
            double legacy = WaveResponse.legacyAttenuation(blocks);
            assertEquals(legacy, WaveResponse.heaveResponse(800.0, 4.0, blocks), 1e-12);
            assertEquals(Math.max(0.35, legacy), WaveResponse.rotationResponse(800.0, 4.0, blocks), 1e-12);
        }
    }

    @Test
    @DisplayName("Response never exceeds the pre-existing value, for any mass / length / block count")
    void neverExceedsLegacy() {
        for (double m = 100; m <= 500_000; m *= 1.7) {
            for (double len = 1; len <= 120; len *= 1.5) {
                for (int b = 1; b <= 3000; b *= 2) {
                    assertTrue(WaveResponse.heaveResponse(m, len, b) <= WaveResponse.legacyAttenuation(b) + 1e-12);
                    assertTrue(WaveResponse.rotationResponse(m, len, b) <= Math.max(0.35, WaveResponse.legacyAttenuation(b)) + 1e-12);
                    assertTrue(WaveResponse.heaveResponse(m, len, b) > 0.0);
                }
            }
        }
    }

    @Test
    @DisplayName("Heavier hulls and longer hulls bob strictly less (monotonic)")
    void monotonicInMassAndLength() {
        double prev = Double.MAX_VALUE;
        for (double m = 1500; m <= 200_000; m *= 1.5) {
            double r = WaveResponse.heaveResponse(m, 20.0, 100);
            assertTrue(r <= prev + 1e-12);
            prev = r;
        }
        prev = Double.MAX_VALUE;
        for (double len = 6; len <= 100; len *= 1.3) {
            double r = WaveResponse.heaveResponse(20_000.0, len, 100);
            assertTrue(r <= prev + 1e-12);
            prev = r;
        }
    }

    @Test
    @DisplayName("A big ship bobs far less than a raft, and rotates less too")
    void bigShipVsRaft() {
        double raft = WaveResponse.heaveResponse(900.0, 4.0, 12);
        double cutter = WaveResponse.heaveResponse(8_000.0, 16.0, 100);
        double galleon = WaveResponse.heaveResponse(32_000.0, 40.0, 400);
        assertTrue(raft > cutter && cutter > galleon);
        assertTrue(galleon < 0.25 * raft);
        assertTrue(WaveResponse.rotationResponse(32_000.0, 40.0, 400) < WaveResponse.rotationResponse(900.0, 4.0, 12));
        assertTrue(galleon >= WaveResponse.HEAVE_FLOOR);
    }

    @Test
    @DisplayName("Bad inputs (NaN / zero) fall back to the full response instead of throwing")
    void degenerateInputs() {
        assertEquals(1.0, WaveResponse.massTerm(Double.NaN), 0.0);
        assertEquals(1.0, WaveResponse.lengthTerm(Double.NaN), 0.0);
        assertEquals(1.0, WaveResponse.heaveResponse(0.0, 0.0, 0), 1e-12);
    }
}

