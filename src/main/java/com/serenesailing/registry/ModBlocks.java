package com.serenesailing.registry;

import com.serenesailing.SereneSailing;
import com.serenesailing.block.CapstanBlock;
import com.serenesailing.block.HelmBlock;
import com.serenesailing.block.RudderBlock;
import com.serenesailing.block.SailBlock;
import com.serenesailing.block.TaffrailLogBlock;
import com.serenesailing.block.WindVaneBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(SereneSailing.MOD_ID);

    public static final DeferredBlock<Block> CANVAS_SAIL = BLOCKS.register(
            "canvas_sail",
            () -> new SailBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.SNOW)
                    .strength(0.8F)
                    .sound(SoundType.WOOL)
                    .noOcclusion()
            )
    );

    public static final DeferredBlock<Block> RUDDER = BLOCKS.register(
            "rudder",
            () -> new RudderBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.5F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
            )
    );

    public static final DeferredBlock<Block> HELM = BLOCKS.register(
            "helm",
            () -> new HelmBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.5F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
            )
    );

    public static final DeferredBlock<Block> WIND_VANE = BLOCKS.register(
            "wind_vane",
            () -> new WindVaneBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(0.8F)
                    .sound(SoundType.COPPER)
                    .noOcclusion()
            )
    );

    public static final DeferredBlock<Block> CAPSTAN = BLOCKS.register(
            "capstan",
            () -> new CapstanBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.5F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
            )
    );

    public static final DeferredBlock<Block> TAFFRAIL_LOG = BLOCKS.register(
            "taffrail_log",
            () -> new TaffrailLogBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.5F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
            )
    );
}