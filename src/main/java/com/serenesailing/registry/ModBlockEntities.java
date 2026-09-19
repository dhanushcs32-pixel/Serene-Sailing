package com.serenesailing.registry;

import com.serenesailing.SereneSailing;
import com.serenesailing.entity.CapstanBlockEntity;
import com.serenesailing.entity.HelmBlockEntity;
import com.serenesailing.entity.RudderBlockEntity;
import com.serenesailing.entity.TaffrailLogBlockEntity;
import com.serenesailing.entity.WindVaneBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SereneSailing.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RudderBlockEntity>> RUDDER =
            BLOCK_ENTITY_TYPES.register("rudder", () ->
                    BlockEntityType.Builder.of(RudderBlockEntity::new, ModBlocks.RUDDER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HelmBlockEntity>> HELM =
            BLOCK_ENTITY_TYPES.register("helm", () ->
                    BlockEntityType.Builder.of(HelmBlockEntity::new, ModBlocks.HELM.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WindVaneBlockEntity>> WIND_VANE =
            BLOCK_ENTITY_TYPES.register("wind_vane", () ->
                    BlockEntityType.Builder.of(WindVaneBlockEntity::new, ModBlocks.WIND_VANE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CapstanBlockEntity>> CAPSTAN =
            BLOCK_ENTITY_TYPES.register("capstan", () ->
                    BlockEntityType.Builder.of(CapstanBlockEntity::new, ModBlocks.CAPSTAN.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TaffrailLogBlockEntity>> TAFFRAIL_LOG =
            BLOCK_ENTITY_TYPES.register("taffrail_log", () ->
                    BlockEntityType.Builder.of(TaffrailLogBlockEntity::new, ModBlocks.TAFFRAIL_LOG.get()).build(null));
}