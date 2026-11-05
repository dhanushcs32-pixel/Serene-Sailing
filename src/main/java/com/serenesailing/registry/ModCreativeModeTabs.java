package com.serenesailing.registry;

import com.serenesailing.SereneSailing;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SereneSailing.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SAILING_TAB =
            CREATIVE_MODE_TABS.register("sailing_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("creativetab.serenesailing.sailing_tab"))
                    .icon(() -> new ItemStack(ModItems.CANVAS_SAIL.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.CANVAS_SAIL.get());
                        output.accept(ModItems.RUDDER.get());
                        output.accept(ModItems.HELM.get());
                        output.accept(ModItems.WIND_VANE.get());
                        output.accept(ModItems.CAPSTAN.get());
                        output.accept(ModItems.TAFFRAIL_LOG.get());
                        output.accept(ModItems.SHIPWRIGHT_SPECTACLES.get());
                    })
                    .build());
}