package com.serenesailing.hold;

import com.serenesailing.SereneSailing;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives the server-side {@link HoldRegistry}; the client side is driven from
 * {@code com.serenesailing.client.HoldClientEvents}, which knows the camera.
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID)
public final class HoldEvents {

    private HoldEvents() {
    }

    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        final Level level = event.getLevel();
        if (!level.isClientSide) {
            HoldRegistry.tick(level, null);
        }
    }

    /**
     * A block changed (placed, broken, exploded, burnt ...). If it is part of a ship, rescan that ship soon so that e.g.
     * knocking a plank out below the waterline floods the hold within a moment instead of at the next background scan.
     */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide) {
            HoldRegistry.markDirty(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            HoldRegistry.discard(level);
        }
    }
}

