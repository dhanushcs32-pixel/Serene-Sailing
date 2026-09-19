package com.serenesailing;

import com.serenesailing.block.CapstanBlock;
import com.serenesailing.config.SailingConfig;
import com.serenesailing.registry.ModBlockEntities;
import com.serenesailing.registry.ModBlocks;
import com.serenesailing.registry.ModCreativeModeTabs;
import com.serenesailing.registry.ModItems;
import com.serenesailing.registry.ModSounds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

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

        // ServerPhysicsHandler registers itself via @EventBusSubscriber (game bus).
        // Do NOT also call NeoForge.EVENT_BUS.register(...) here - that would
        // register the class twice and apply every sailing force twice per step.
    }
}
