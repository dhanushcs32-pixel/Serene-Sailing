package com.serenesailing.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Client-only isolated opener for the Helm GUI slider.
 * Keeps Minecraft and Screen classes off the dedicated server classpath.
 */
public final class HelmScreenOpener {

    private HelmScreenOpener() {}

    public static void open(BlockPos pos, float angle) {
        Minecraft.getInstance().setScreen(new HelmScreen(pos, angle));
    }
}
