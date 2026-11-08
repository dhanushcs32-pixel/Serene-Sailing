package com.serenesailing;

import com.serenesailing.config.SailingConfig;
import com.serenesailing.config.SereneSailingClientConfig;
import com.serenesailing.registry.ModBlockEntities;
import com.serenesailing.registry.ModBlocks;
import com.serenesailing.registry.ModCreativeModeTabs;
import com.serenesailing.registry.ModItems;
import com.serenesailing.registry.ModSounds;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(SereneSailing.MOD_ID)
public class SereneSailing {
    public static final String MOD_ID = "serenesailing";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SereneSailing(IEventBus modEventBus) {
        // Register Deferred Registers to the Mod Event Bus
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModCreativeModeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);

        // Register configuration
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.COMMON, SailingConfig.SPEC);
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, SereneSailingClientConfig.SPEC);
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.SERVER, com.serenesailing.hold.HoldConfig.SPEC);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);

        // ServerPhysicsHandler registers itself via @EventBusSubscriber (game bus).
        // Do NOT also call NeoForge.EVENT_BUS.register(...) here - that would
        // register the class twice and apply every sailing force twice per step.
    }

    private void addCreative(final BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES
                || event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ModItems.SHIPWRIGHT_SPECTACLES.get());
        }
    }

    private void commonSetup(final net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (net.neoforged.fml.ModList.get().isLoaded("weather2")) {
                com.serenesailing.compat.weather2.SafeWeatherNetworking.install();
            }
        });
    }
}
