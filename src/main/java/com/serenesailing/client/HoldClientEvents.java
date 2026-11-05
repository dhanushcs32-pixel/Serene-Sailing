package com.serenesailing.client;

import com.serenesailing.SereneSailing;
import com.serenesailing.hold.HoldRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives the client-side {@link HoldRegistry}. Sable's water-occlusion regions are not networked, so the client
 * scans and registers its own, around the player.
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID, value = Dist.CLIENT)
public final class HoldClientEvents {

    private HoldClientEvents() {
    }

    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ClientLevel level)) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final Vec3 focus = mc.player != null ? mc.player.position() : null;
        if (focus == null) {
            return;
        }
        HoldRegistry.tick(level, focus);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            HoldRegistry.discard(level);
        }
    }
}

