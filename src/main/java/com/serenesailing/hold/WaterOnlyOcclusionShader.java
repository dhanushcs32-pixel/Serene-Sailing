package com.serenesailing.hold;

/**
 * Narrows Sable's water-occlusion shader patch to WATER.
 *
 * <p>Sable patches the whole vanilla {@code rendertype_translucent} fragment shader with
 * {@code if (waterDepth > closeDepth && waterDepth < farDepth) { discard; }}: every translucent fragment that lies
 * between the nearest and farthest face of a registered volume is thrown away. The shader cannot tell water from
 * anything else, so glass, stained glass, ice, slime and honey placed inside a sunken hold vanish together with the
 * ocean. The only per-fragment attribute that separates them is the vertex colour: water carries the strongly blue
 * biome tint (blue/red of about 3.6 for ocean water), glass is neutral. The vertex colour is also multiplied by the
 * lightmap, which shifts blue/red only between roughly 0.55 (dim torch light) and 1.4 (moonlight), so a 1.7 threshold
 * keeps a safe margin on both sides.
 *
 * <p>Pure Java so the string edit can be unit tested. Swamp water (almost no tint) is not matched and stays visible
 * inside a hold; that is the accepted cost of not discarding glass.
 */
public final class WaterOnlyOcclusionShader {

    /** blue / red of the fragment's vertex colour above which a fragment is treated as water. */
    public static final double WATER_BLUE_TO_RED_RATIO = 1.7;

    /** The tail of Sable's condition: everything before it is Sable's own depth-interval test. */
    private static final String SABLE_CONDITION_TAIL = "waterDepth < farDepth)";
    private static final String WATER_ONLY_MARKER = "vertexColor.b > vertexColor.r";

    private WaterOnlyOcclusionShader() {
    }

    /**
     * @param glsl a GLSL snippet handed to Sable's shader parser
     * @return the snippet with the discard restricted to water fragments, or the input unchanged if it is not the
     * occlusion snippet (or has already been edited)
     */
    public static String restrictToWater(String glsl) {
        if (glsl == null || !glsl.contains("discard") || !glsl.contains(SABLE_CONDITION_TAIL)
                || glsl.contains(WATER_ONLY_MARKER)) {
            return glsl;
        }
        return glsl.replace(SABLE_CONDITION_TAIL,
                "waterDepth < farDepth && vertexColor.b > vertexColor.r * " + WATER_BLUE_TO_RED_RATIO + ")");
    }
}
