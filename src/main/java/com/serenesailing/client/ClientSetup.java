package com.serenesailing.client;

import com.serenesailing.SereneSailing;
import com.serenesailing.client.renderer.CapstanRenderer;
import com.serenesailing.client.renderer.HelmBlockEntityRenderer;
import com.serenesailing.client.renderer.RudderRenderer;
import com.serenesailing.client.renderer.WindVaneRenderer;
import com.serenesailing.config.SereneSailingClientConfig;
import com.serenesailing.registry.ModBlockEntities;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.util.function.Consumer;

/**
 * Client-side lifecycle setup.
 *
 * <p>Registers dynamic block entity renderers, NeoForge configuration screen factory,
 * and wave aggressiveness sliders injected into Minecraft's Video Settings screen.</p>
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class ClientSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Register NeoForge's built-in ConfigurationScreen for the "Config" button in the Mods menu
        ModLoadingContext.get().registerExtensionPoint(
                IConfigScreenFactory.class,
                () -> ConfigurationScreen::new);

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(com.serenesailing.client.renderer.ClientWaveRenderer.class);
        SereneSailing.LOGGER.info("[SereneSailing] Client initialization complete, registered ClientWaveRenderer to game bus.");
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
        // Dynamic rudder blade deflection.
        event.registerBlockEntityRenderer(ModBlockEntities.RUDDER.get(),
                RudderRenderer::new);
        // Dynamic 8-spoke ship's wheel with rotation.
        event.registerBlockEntityRenderer(ModBlockEntities.HELM.get(),
                HelmBlockEntityRenderer::new);
    }

    /**
     * Injects the Calm and Storm Wave Aggressiveness sliders directly into Minecraft's Options -> Video Settings screen.
     */
    @EventBusSubscriber(modid = SereneSailing.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
    public static class VideoSettingsHandler {

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            if (event.getScreen() instanceof VideoSettingsScreen videoScreen) {
                for (var child : videoScreen.children()) {
                    if (child instanceof OptionsList optionsList) {
                        OptionInstance<Double> calmSlider = createWaveSlider(
                                "serenesailing.options.calmWaveAggressiveness",
                                SereneSailingClientConfig.getCalmAggressiveness(),
                                val -> {
                                    try {
                                        SereneSailingClientConfig.CALM_WAVE_AGGRESSIVENESS.set(val);
                                        SereneSailingClientConfig.SPEC.save();
                                    } catch (Throwable ignored) {
                                    }
                                });
                        OptionInstance<Double> stormSlider = createWaveSlider(
                                "serenesailing.options.stormWaveAggressiveness",
                                SereneSailingClientConfig.getStormAggressiveness(),
                                val -> {
                                    try {
                                        SereneSailingClientConfig.STORM_WAVE_AGGRESSIVENESS.set(val);
                                        SereneSailingClientConfig.SPEC.save();
                                    } catch (Throwable ignored) {
                                    }
                                });
                        optionsList.addSmall(calmSlider, stormSlider);
                        break;
                    }
                }
            }
        }

        private static OptionInstance<Double> createWaveSlider(String translationKey, double initialVal, Consumer<Double> onUpdate) {
            return new OptionInstance<>(
                    translationKey,
                    OptionInstance.noTooltip(),
                    (caption, val) -> Component.translatable(translationKey, (int) Math.round(val * 100)),
                    OptionInstance.UnitDouble.INSTANCE.xmap(
                            sliderVal -> {
                                double raw = sliderVal * 3.0;
                                double snapped = Math.round(raw / 0.05) * 0.05;
                                return Mth.clamp(snapped, 0.0, 3.0);
                            },
                            val -> Mth.clamp(val / 3.0, 0.0, 1.0)
                    ),
                    initialVal,
                    onUpdate
            );
        }
    }
}
