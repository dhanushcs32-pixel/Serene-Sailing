package com.serenesailing.hold;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Hold scanning settings. Registered as a SERVER config on purpose: both logical sides scan every ship (Sable's water
 * occlusion regions are not networked), and the dry volume drives entity physics on both. A SERVER config is synced from
 * the server to every client, so the two sides can never run different engines.
 */
public final class HoldConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<HoldScanMode> SCAN_MODE;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("Ship Holds");
        SCAN_MODE = builder.comment(
                "Which engine decides where the sea is kept out of a ship.",
                "ROW_SCANNER = the original row scanner: finds the outer walls of each row, calls the vessel a raft or a hold,",
                "              and seals everything between the walls. Always dry, even if the hull is holed.",
                "FLOOD_FILL  = floods from the open sea through every open face below the waterline. Only air pockets the sea",
                "              cannot reach stay dry; a hole at or under the waterline floods its compartment (water shows, you swim).")
                .defineEnum("holdScanMode", HoldScanMode.ROW_SCANNER);
        builder.pop();
        SPEC = builder.build();
    }

    private HoldConfig() {
    }

    /** The configured engine; ROW_SCANNER before the config is loaded (main menu, early client start, unit tests). */
    public static HoldScanMode scanMode() {
        try {
            if (SPEC.isLoaded()) {
                return SCAN_MODE.get();
            }
        } catch (Throwable ignored) {
            // config not available yet
        }
        return HoldScanMode.ROW_SCANNER;
    }
}

