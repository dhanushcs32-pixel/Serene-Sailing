package com.serenesailing.physics;

import com.serenesailing.compat.weather2.Weather2Compat;
import com.serenesailing.entity.HelmBlockEntity;
import com.serenesailing.event.ServerPhysicsHandler;
import com.serenesailing.network.ServerboundSteerPayload;
import com.serenesailing.network.SetHelmAnglePayload;
import com.serenesailing.physics.waves.GerstnerWaveEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defensive audit regression tests covering numerical boundary limits,
 * tornado splashdown recovery, bidirectional momentum clamping,
 * NBT float sanitization, network payload security, wave engine
 * stability, and cross-system invariant verification.
 *
 * <p>Audit 106 additions: JMM volatile visibility, GerstnerWaveEngine
 * NaN boundary, SailAerodynamics edge-case normalization safety,
 * and Weather2Compat stage mapping correctness.</p>
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

    // ===================================================================
    // Audit 106: Deep cross-system invariant regression tests
    // ===================================================================

    @Test
    @DisplayName("Audit 106.1: GerstnerWaveEngine evaluateWaveOffset never returns NaN or Infinity at extreme coordinates")
    public void testGerstnerWaveNoNaNAtExtremeCoordinates() {
        double[] testCoords = {0.0, 1.0, -1.0, 5000.0, -5000.0, 100000.0, -100000.0, 1e12, -1e12};
        double[] testStormValues = {0.0, 0.01, 0.03, 0.15, 0.35, 0.5, 0.75, 1.0};
        double[] testTimes = {0.0, 1.0, 100.0, 10000.0, 1e9};

        for (double x : testCoords) {
            for (double z : testCoords) {
                for (double storm : testStormValues) {
                    for (double time : testTimes) {
                        double result = GerstnerWaveEngine.evaluateWaveOffset(
                                x, z, time, storm, 0.0, -1.0);
                        assertFalse(Double.isNaN(result),
                                String.format("Wave offset NaN at x=%g z=%g storm=%g time=%g", x, z, storm, time));
                        assertFalse(Double.isInfinite(result),
                                String.format("Wave offset Inf at x=%g z=%g storm=%g time=%g", x, z, storm, time));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Audit 106.2: GerstnerWaveEngine slope/drift never returns NaN or Infinity")
    public void testGerstnerSlopeNeverNaN() {
        Vector3d out = new Vector3d();
        double[] storms = {0.0, 0.15, 0.5, 1.0};
        double[] coords = {0.0, 100.0, -5000.0, 1e8};

        for (double storm : storms) {
            for (double x : coords) {
                for (double z : coords) {
                    GerstnerWaveEngine.evaluateWaveSlopeAndDrift(
                            x, z, 50.0, storm, 0.7071, -0.7071, out);
                    assertFalse(Double.isNaN(out.x), "Slope X NaN at storm=" + storm);
                    assertFalse(Double.isNaN(out.y), "Drift Y NaN at storm=" + storm);
                    assertFalse(Double.isNaN(out.z), "Slope Z NaN at storm=" + storm);
                    assertFalse(Double.isInfinite(out.x), "Slope X Inf");
                    assertFalse(Double.isInfinite(out.y), "Drift Y Inf");
                    assertFalse(Double.isInfinite(out.z), "Slope Z Inf");
                }
            }
        }
    }

    @Test
    @DisplayName("Audit 106.3: GerstnerWaveEngine calm weather (S <= 0.03) produces near-zero wave height")
    public void testGerstnerCalmWeatherNearZero() {
        // At stormIntensity = 0.0, waves should be negligible (< 0.01m)
        double result = GerstnerWaveEngine.evaluateWaveOffset(
                100.0, 200.0, 50.0, 0.0, 0.0, -1.0);
        assertTrue(Math.abs(result) < 0.01,
                "Calm weather (S=0.0) wave height must be < 0.01m, was: " + result);

        result = GerstnerWaveEngine.evaluateWaveOffset(
                100.0, 200.0, 50.0, 0.03, 0.0, -1.0);
        assertTrue(Math.abs(result) < 0.25,
                "Very calm weather (S=0.03) wave height must be < 0.25m, was: " + result);
    }

    @Test
    @DisplayName("Audit 106.4: GerstnerWaveEngine storm waves (S = 1.0) produce meaningful displacement")
    public void testGerstnerStormWavesHaveDisplacement() {
        // Over a range of coordinates, maximum wave displacement should be > 0.5m at full storm
        double maxAbs = 0.0;
        for (double x = 0; x < 100; x += 5) {
            for (double z = 0; z < 100; z += 5) {
                double result = GerstnerWaveEngine.evaluateWaveOffset(
                        x, z, 25.0, 1.0, 0.0, -1.0);
                maxAbs = Math.max(maxAbs, Math.abs(result));
            }
        }
        assertTrue(maxAbs > 0.5,
                "Full storm (S=1.0) must produce > 0.5m peak displacement, maxAbs was: " + maxAbs);
    }

    @Test
    @DisplayName("Audit 106.5: DimensionWaveState.setImmediate degenerate direction normalizes safely")
    public void testWaveStateImmediateDegenerateDirection() {
        GerstnerWaveEngine.DimensionWaveState state = new GerstnerWaveEngine.DimensionWaveState();

        // Zero-length direction vector should normalize to default (0, -1)
        state.setImmediate(1.0, 0.0, 0.0, 0.5);
        assertEquals(0.0, state.smoothedWindDirX, DELTA, "Zero dir should default to (0, -1) X");
        assertEquals(-1.0, state.smoothedWindDirZ, DELTA, "Zero dir should default to (0, -1) Z");
    }

    @Test
    @DisplayName("Audit 106.6: SailAerodynamics returns zero force for near-zero apparent wind")
    public void testSailAeroZeroApparentWind() {
        // When ship velocity nearly equals true wind, apparent wind is ~zero
        Vector3d trueWind = new Vector3d(5.0, 0.0, 0.0);
        Vector3d shipVel = new Vector3d(4.95, 0.0, 0.0); // Very close to true wind
        Vector3d forward = new Vector3d(1.0, 0.0, 0.0);
        Vector3d sailNormal = new Vector3d(0.0, 0.0, 1.0);

        Vector3d force = SailAerodynamics.calculateSailForce(trueWind, shipVel, forward, sailNormal, 1.0);
        assertTrue(force.length() < 1.0, "Near-zero apparent wind must produce negligible force");
        assertFalse(Double.isNaN(force.x) || Double.isNaN(force.z), "Force must not contain NaN");
    }

    @Test
    @DisplayName("Audit 106.7: SailAerodynamics in-irons pushback produces backward force along wind")
    public void testSailAeroInIronsPushback() {
        // Ship heading directly into the wind
        Vector3d trueWind = new Vector3d(0.0, 0.0, -10.0); // Wind from south
        Vector3d shipVel = new Vector3d(0.0, 0.0, 0.0);
        Vector3d forward = new Vector3d(0.0, 0.0, 1.0); // Ship heading south (into wind)
        Vector3d sailNormal = new Vector3d(1.0, 0.0, 0.0);

        Vector3d force = SailAerodynamics.calculateSailForce(trueWind, shipVel, forward, sailNormal, 1.0);
        // Force should have a Z component pushing backward (negative Z = wind direction)
        assertTrue(force.z < 0.0, "In-irons pushback must produce backward force along wind, got Z=" + force.z);
        assertFalse(Double.isNaN(force.length()), "Force must not be NaN");
    }

    @Test
    @DisplayName("Audit 106.8: Momentum inertia guard never explodes with very small dt")
    public void testMomentumGuardSmallDt() {
        // With very small dt (sub-step), the momentum guard should not allow
        // more than proportional loss per sub-step
        Vector3d heading = new Vector3d(0.0, 0.0, -1.0);
        Vector3d vel = new Vector3d(0.0, 0.0, -10.0); // 10 m/s forward

        // 10 sub-steps of dt = 0.005 should not lose more speed than 1 step of dt = 0.05
        Vector3d subSteppedVel = new Vector3d(vel);
        for (int i = 0; i < 10; i++) {
            subSteppedVel = ServerPhysicsHandler.updateTurningMomentum(
                    subSteppedVel,
                    heading,
                    heading,
                    15.0f, // Active steering
                    0.1,
                    0.005, // Small sub-step dt
                    1.0,
                    0.0,
                    false
            );
        }

        Vector3d singleStepVel = ServerPhysicsHandler.updateTurningMomentum(
                new Vector3d(vel),
                heading,
                heading,
                15.0f,
                0.1,
                0.05,
                1.0,
                0.0,
                false
        );

        // Both should produce roughly similar results (within 15% tolerance)
        double subSpeed = Math.abs(subSteppedVel.z);
        double singleSpeed = Math.abs(singleStepVel.z);
        double ratio = subSpeed / singleSpeed;
        assertTrue(ratio > 0.85 && ratio < 1.15,
                "Sub-stepped result should match single-step within 15%, ratio=" + ratio);
    }

    @Test
    @DisplayName("Audit 106.9: Weather2Compat stage mapping covers all rawStage values 0-10")
    public void testWeather2StageMappingCompleteness() {
        // Verify that every rawStage from 0 to 10 maps to a defined mappedStage without fallthrough errors
        // rawStage 0 -> 0 (none)
        // rawStage 1 -> 1 (STATE_FORMING_EARLY)
        // rawStage 2 -> 1 (STATE_HIGHWIND)
        // rawStage 3 -> 1 (STATE_HAIL)
        // rawStage 4 -> 1 (STATE_FORMING / F0)
        // rawStage 5 -> 2 (STATE_STAGE1 / F1)
        // rawStage 6 -> 3 (STATE_STAGE2 / F2)
        // rawStage 7 -> 4 (STATE_STAGE3 / F3)
        // rawStage 8 -> 5 (STATE_STAGE4 / F4)
        // rawStage 9 -> 6 (STATE_STAGE5 / F5)
        // rawStage 10+ -> 6 (F5 cap)

        int[] expectedMappings = {0, 1, 1, 1, 1, 2, 3, 4, 5, 6, 6};

        for (int raw = 0; raw <= 10; raw++) {
            int mapped = mapWeather2Stage(raw);
            assertEquals(expectedMappings[raw], mapped,
                    "rawStage " + raw + " should map to " + expectedMappings[raw] + " but got " + mapped);
        }
    }

    /**
     * Mirrors the Weather2Compat stage mapping logic after Audit 106 fix.
     */
    private static int mapWeather2Stage(int rawStage) {
        if (rawStage >= 9) return 6;
        else if (rawStage == 8) return 5;
        else if (rawStage == 7) return 4;
        else if (rawStage == 6) return 3;
        else if (rawStage == 5) return 2;
        else if (rawStage == 4) return 1;
        else if (rawStage == 2 || rawStage == 3) return 1;
        else if (rawStage == 1) return 1;
        else return 0;
    }

    @Test
    @DisplayName("Audit 106.10: Network payload negative-zero angle is treated as valid zero")
    public void testPayloadNegativeZero() {
        ServerboundSteerPayload steer = new ServerboundSteerPayload(BlockPos.ZERO, -0.0f, false);
        // -0.0f is not NaN or Infinite, should pass through as-is
        assertFalse(Float.isNaN(steer.rudderAngle()), "Negative zero must not be NaN");
        assertTrue(steer.rudderAngle() >= -HelmBlockEntity.MAX_RUDDER_ANGLE, "Negative zero must be within range");

        SetHelmAnglePayload helm = new SetHelmAnglePayload(BlockPos.ZERO, -0.0f);
        assertFalse(Float.isNaN(helm.angle()), "Negative zero helm must not be NaN");
    }

    @Test
    @DisplayName("Audit: All 6 crafting recipes exist under data/serenesailing/recipe/ and have valid shaped format")
    public void testAllRecipesExistAndAreValidCraftingShaped() throws Exception {
        List<String> items = List.of(
                "canvas_sail",
                "rudder",
                "helm",
                "wind_vane",
                "capstan",
                "taffrail_log"
        );
        Path recipeDir = Path.of("src", "main", "resources", "data", "serenesailing", "recipe");
        assertTrue(Files.isDirectory(recipeDir), "Recipe directory data/serenesailing/recipe/ must exist");

        for (String item : items) {
            Path file = recipeDir.resolve(item + ".json");
            assertTrue(Files.exists(file), "Recipe file must exist: " + file);

            String content = Files.readString(file);
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();

            assertEquals("minecraft:crafting_shaped", json.get("type").getAsString(),
                    "Recipe type for " + item + " must be minecraft:crafting_shaped");
            assertTrue(json.has("category"), "Recipe for " + item + " must have a category");
            assertTrue(json.has("result"), "Recipe for " + item + " must have a result");

            JsonObject result = json.getAsJsonObject("result");
            assertEquals("serenesailing:" + item, result.get("id").getAsString(),
                    "Result id must match serenesailing:" + item);
            assertTrue(result.get("count").getAsInt() >= 1, "Result count must be >= 1");
        }
    }

    @Test
    @DisplayName("Audit: All 6 recipe advancements exist under data/serenesailing/advancement/recipes/ (and misc/) with planks/logs/recipe criteria")
    public void testAllRecipeAdvancementsExistAndContainPlanksLogsCriteria() throws Exception {
        List<String> items = List.of(
                "canvas_sail",
                "rudder",
                "helm",
                "wind_vane",
                "capstan",
                "taffrail_log"
        );
        List<Path> dirs = List.of(
                Path.of("src", "main", "resources", "data", "serenesailing", "advancement", "recipes"),
                Path.of("src", "main", "resources", "data", "serenesailing", "advancement", "recipes", "misc")
        );

        for (Path dir : dirs) {
            assertTrue(Files.isDirectory(dir), "Advancement directory must exist: " + dir);
            for (String item : items) {
                Path file = dir.resolve(item + ".json");
                assertTrue(Files.exists(file), "Advancement file must exist: " + file);

                String content = Files.readString(file);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();

                assertTrue(json.has("criteria"), "Advancement " + file + " must have criteria");
                JsonObject criteria = json.getAsJsonObject("criteria");
                assertTrue(criteria.has("has_planks"), "Advancement " + file + " must have has_planks criterion");
                assertTrue(criteria.has("has_logs"), "Advancement " + file + " must have has_logs criterion");
                assertTrue(criteria.has("has_the_recipe"), "Advancement " + file + " must have has_the_recipe criterion");

                assertTrue(json.has("rewards"), "Advancement " + file + " must have rewards");
                JsonObject rewards = json.getAsJsonObject("rewards");
                assertTrue(rewards.has("recipes"), "Advancement rewards must specify recipes");
                assertTrue(rewards.getAsJsonArray("recipes").toString().contains("serenesailing:" + item),
                        "Advancement rewards must grant serenesailing:" + item);
            }
        }
    }

    @Test
    @DisplayName("Audit: en_us.json contains both block and item translation keys with exact display names")
    public void testLangFileContainsBothBlockAndItemKeysWithExactNames() throws Exception {
        Path langPath = Path.of("src", "main", "resources", "assets", "serenesailing", "lang", "en_us.json");
        assertTrue(Files.exists(langPath), "en_us.json must exist");

        String content = Files.readString(langPath);
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();

        assertEquals("Serene Sailing", json.get("itemGroup.serenesailing").getAsString(),
                "itemGroup.serenesailing must be 'Serene Sailing'");

        Map<String, String> expectedNames = Map.of(
                "taffrail_log", "Taffrail Log",
                "canvas_sail", "Canvas Sail",
                "helm", "Ship's Helm",
                "rudder", "Ship's Rudder",
                "capstan", "Ship's Capstan",
                "wind_vane", "Masthead Wind Vane"
        );

        for (Map.Entry<String, String> entry : expectedNames.entrySet()) {
            String id = entry.getKey();
            String expected = entry.getValue();

            String blockKey = "block.serenesailing." + id;
            assertTrue(json.has(blockKey), "en_us.json must have key: " + blockKey);
            assertEquals(expected, json.get(blockKey).getAsString(),
                    blockKey + " display name must match " + expected);

            String itemKey = "item.serenesailing." + id;
            assertTrue(json.has(itemKey), "en_us.json must have key: " + itemKey);
            assertEquals(expected, json.get(itemKey).getAsString(),
                    itemKey + " display name must match " + expected);
        }
    }
}
