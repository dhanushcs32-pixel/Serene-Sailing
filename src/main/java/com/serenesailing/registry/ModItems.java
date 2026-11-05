package com.serenesailing.registry;

import com.serenesailing.SereneSailing;
import com.serenesailing.item.ShipwrightSpectaclesItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(SereneSailing.MOD_ID);

    public static final DeferredItem<ShipwrightSpectaclesItem> SHIPWRIGHT_SPECTACLES = ITEMS.register(
            "shipwright_spectacles",
            () -> new ShipwrightSpectaclesItem(new Item.Properties().stacksTo(1))
    );

    public static final DeferredItem<BlockItem> CANVAS_SAIL = ITEMS.registerSimpleBlockItem(
            "canvas_sail", ModBlocks.CANVAS_SAIL);

    public static final DeferredItem<BlockItem> RUDDER = ITEMS.registerSimpleBlockItem(
            "rudder", ModBlocks.RUDDER);

    public static final DeferredItem<BlockItem> HELM = ITEMS.registerSimpleBlockItem(
            "helm", ModBlocks.HELM);

    public static final DeferredItem<BlockItem> WIND_VANE = ITEMS.registerSimpleBlockItem(
            "wind_vane", ModBlocks.WIND_VANE);

    public static final DeferredItem<BlockItem> CAPSTAN = ITEMS.registerSimpleBlockItem(
            "capstan", ModBlocks.CAPSTAN);

    public static final DeferredItem<BlockItem> TAFFRAIL_LOG = ITEMS.registerSimpleBlockItem(
            "taffrail_log", ModBlocks.TAFFRAIL_LOG);
}