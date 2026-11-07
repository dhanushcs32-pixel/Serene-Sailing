package com.serenesailing.hold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WaterOnlyOcclusionShaderTest {

    /** The snippet exactly as Sable 2.0.0 - 2.0.6 hands it to the GLSL parser. */
    private static final String SABLE_SNIPPET = """
            if(SableWaterOcclusionEnabled > 0.0) {
                float closeDepth = texture(SableCloseSampler, gl_FragCoord.xy / ScreenSize).r;
                float farDepth = texture(SableFarSampler, gl_FragCoord.xy / ScreenSize).r;
                float waterDepth = gl_FragCoord.z;
                if (waterDepth > closeDepth && waterDepth < farDepth) { discard; }
            }""";

    @Test
    @DisplayName("The discard condition gains a water-tint test and keeps Sable's depth interval")
    public void discardIsRestrictedToWater() {
        final String out = WaterOnlyOcclusionShader.restrictToWater(SABLE_SNIPPET);
        assertTrue(out.contains("waterDepth > closeDepth && waterDepth < farDepth && vertexColor.b > vertexColor.r * 1.7) { discard; }"), out);
        assertTrue(out.contains("float closeDepth = texture(SableCloseSampler"), "the rest of the snippet is untouched");
    }

    @Test
    @DisplayName("Other parseExpression arguments (uniform declarations) pass through unchanged")
    public void unrelatedSnippetsAreUntouched() {
        for (final String s : new String[]{"uniform vec2 ScreenSize;", "uniform sampler2D SableCloseSampler;",
                "uniform float SableWaterOcclusionEnabled;", ""}) {
            assertEquals(s, WaterOnlyOcclusionShader.restrictToWater(s));
        }
        assertEquals(null, WaterOnlyOcclusionShader.restrictToWater(null));
    }

    @Test
    @DisplayName("Applying it twice changes nothing more")
    public void idempotent() {
        final String once = WaterOnlyOcclusionShader.restrictToWater(SABLE_SNIPPET);
        assertEquals(once, WaterOnlyOcclusionShader.restrictToWater(once));
    }

    @Test
    @DisplayName("A snippet that has no depth-interval condition is not rewritten")
    public void discardWithoutSableConditionIsLeftAlone() {
        final String other = "if (alpha < 0.1) { discard; }";
        assertEquals(other, WaterOnlyOcclusionShader.restrictToWater(other));
        assertFalse(WaterOnlyOcclusionShader.restrictToWater(SABLE_SNIPPET).equals(SABLE_SNIPPET));
    }

    @Test
    @DisplayName("The tint threshold separates ocean-type water from glass under every lightmap tint")
    public void thresholdSeparatesWaterFromGlass() {
        final double ratio = WaterOnlyOcclusionShader.WATER_BLUE_TO_RED_RATIO;
        // ocean-type biome water colours: ocean/plains, lukewarm, warm, cold, frozen
        for (final int[] rgb : new int[][]{{0x3F, 0x76, 0xE4}, {0x45, 0xAD, 0xF2}, {0x43, 0xD5, 0xEE},
                {0x3D, 0x57, 0xD6}, {0x39, 0x38, 0xC9}}) {
            final double tint = (double) rgb[2] / rgb[0];
            // the lightmap can pull blue/red down to about 0.55 (dim warm torch light)
            assertTrue(tint * 0.55 > ratio, "water must stay above the threshold in dim torch light, tint=" + tint);
        }
        // glass is neutral; the lightmap alone moves blue/red between about 0.55 and 1.4 (moonlight)
        for (final double lightmapBlueToRed : new double[]{0.55, 1.0, 1.4}) {
            assertFalse(lightmapBlueToRed > ratio, "glass must stay below the threshold: " + lightmapBlueToRed);
        }
    }
}
