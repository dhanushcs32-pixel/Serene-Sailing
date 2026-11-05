package com.serenesailing.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side configuration options for Serene Sailing ocean wave simulation.
 *
 * <p>Provides user-facing sliders to adjust ocean swell, peak rise, trough drop,
 * and chop aggressiveness in both calm/normal sailing weather and severe storms.</p>
 */
public class SereneSailingClientConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue CALM_WAVE_AGGRESSIVENESS;
    public static final ModConfigSpec.DoubleValue STORM_WAVE_AGGRESSIVENESS;
    public static final ModConfigSpec.EnumValue<HoldDepthPlaneMode> HOLD_DEPTH_PLANES;

    /** When the built-in depth-only barrier planes that hide ocean water inside sunken holds are drawn. */
    public enum HoldDepthPlaneMode {
        /** Always, alongside Sable's own water occlusion (belt and braces). */
        ALWAYS,
        /** Only when Sable's water occlusion cannot do it: its client option is off, or no region is registered. */
        FALLBACK_ONLY,
        /** Never; rely on Sable alone. */
        OFF
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("Wave Settings");
        CALM_WAVE_AGGRESSIVENESS = builder.comment(
                "Linearly scales wave amplitude, choppiness, and crest-to-trough variation during calm and normal sailing weather.")
                .defineInRange("calmWaveAggressiveness", 1.0, 0.0, 3.0);
        STORM_WAVE_AGGRESSIVENESS = builder.comment(
                "Linearly scales wave amplitude, choppiness, crest sharpness, and rogue-wave variation during stormy weather.")
                .defineInRange("stormWaveAggressiveness", 1.0, 0.0, 3.0);
        builder.pop();

        builder.push("Ship Holds");
        HOLD_DEPTH_PLANES = builder.comment(
                "Depth-only barrier planes that keep ocean water out of sunken ship holds. ALWAYS = alongside Sable's water occlusion,",
                "FALLBACK_ONLY = only when Sable's sub_level_water_occlusion option is off or has no region for the ship, OFF = never.")
                .defineEnum("holdDepthPlanes", HoldDepthPlaneMode.ALWAYS);
        builder.pop();

        SPEC = builder.build();
    }

    /**
     * Safely reads the calm wave aggressiveness multiplier (0.0 to 3.0).
     * Defaults to 1.0 if accessed before config initialization or during headless unit testing.
     */
    public static double getCalmAggressiveness() {
        try {
            if (CALM_WAVE_AGGRESSIVENESS != null && SPEC.isLoaded()) {
                return CALM_WAVE_AGGRESSIVENESS.get();
            }
        } catch (Throwable ignored) {
        }
        return 1.0;
    }

    /**
     * Safely reads the storm wave aggressiveness multiplier (0.0 to 3.0).
     * Defaults to 1.0 if accessed before config initialization or during headless unit testing.
     */
    public static double getStormAggressiveness() {
        try {
            if (STORM_WAVE_AGGRESSIVENESS != null && SPEC.isLoaded()) {
                return STORM_WAVE_AGGRESSIVENESS.get();
            }
        } catch (Throwable ignored) {
        }
        return 1.0;
    }

    /** Safe read of the hold depth plane mode (ALWAYS before config load / in headless tests). */
    public static HoldDepthPlaneMode getHoldDepthPlaneMode() {
        try {
            if (HOLD_DEPTH_PLANES != null && SPEC.isLoaded()) {
                return HOLD_DEPTH_PLANES.get();
            }
        } catch (Throwable ignored) {
        }
        return HoldDepthPlaneMode.ALWAYS;
    }
}
