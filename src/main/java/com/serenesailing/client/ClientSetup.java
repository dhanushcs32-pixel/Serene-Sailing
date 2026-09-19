package com.serenesailing.client;

import com.serenesailing.SereneSailing;
import com.serenesailing.client.renderer.CapstanRenderer;
import com.serenesailing.client.renderer.WindVaneRenderer;
import com.serenesailing.registry.ModBlockEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Client-side lifecycle setup.
 *
 * <p>The class must always contain at least one {@code @SubscribeEvent}
 * method: NeoForge's auto-registration throws
 * {@code IllegalArgumentException} during bootstrap for an auto-subscribed
 * class with no listeners.</p>
 *
 * <p>Registers the dynamic masthead wind vane renderer. All other blocks
 * (helm, rudder, sail, capstan) render entirely through standard JSON
 * block/item models, and ships are assembled via the Create Aeronautics
 * Physics Assembler (Sable sub-levels), so there is no entity renderer.</p>
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class ClientSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        SereneSailing.LOGGER.info("[SereneSailing] Client initialization complete.");
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Dynamic pennant on the masthead wind vane; base + spindle stay in
        // the static JSON block model.
        event.registerBlockEntityRenderer(ModBlockEntities.WIND_VANE.get(),
                WindVaneRenderer::new);
        // Dynamic mooring chain + anchor fluke under the capstan.
        event.registerBlockEntityRenderer(ModBlockEntities.CAPSTAN.get(),
                CapstanRenderer::new);
    }
}

