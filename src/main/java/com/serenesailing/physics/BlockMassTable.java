package com.serenesailing.physics;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-block mass estimates for the standalone engine.
 *
 * <p><b>Status: not wired into the game.</b> Used only by {@link SailingPhysicsEngine}.
 * Part of the standalone engine described in {@link SailingPhysicsEngine}; changes here do not
 * affect gameplay (the live physics is in {@link com.serenesailing.event.ServerPhysicsHandler}).
 */
public class BlockMassTable {

    /**
     * Estimates block mass in kilograms per single voxel block (1 m^3).
     */
    public static double getBlockMass(BlockState state) {
        if (state.isAir()) {
            return 1.2; // Air / empty cargo space (~1.2 kg/m^3)
        }

        Block block = state.getBlock();

        // Planks & Light Wood (~600 kg/m^3)
        if (block == Blocks.OAK_PLANKS || block == Blocks.SPRUCE_PLANKS ||
            block == Blocks.BIRCH_PLANKS || block == Blocks.JUNGLE_PLANKS ||
            block == Blocks.ACACIA_PLANKS || block == Blocks.DARK_OAK_PLANKS ||
            block == Blocks.MANGROVE_PLANKS || block == Blocks.CHERRY_PLANKS ||
            block == Blocks.BAMBOO_PLANKS) {
            return 600.0;
        }

        // Dense Wood / Logs (~700 kg/m^3)
        if (block == Blocks.OAK_LOG || block == Blocks.SPRUCE_LOG ||
            block == Blocks.STRIPPED_OAK_LOG || block == Blocks.STRIPPED_SPRUCE_LOG) {
            return 700.0;
        }

        // Stone Ballast (~2500 kg/m^3)
        if (block == Blocks.STONE || block == Blocks.COBBLESTONE || block == Blocks.DEEPSLATE) {
            return 2500.0;
        }

        // Heavy Iron / Metallic Ballast (~7800 kg/m^3)
        if (block == Blocks.IRON_BLOCK || block == Blocks.RAW_IRON_BLOCK) {
            return 7800.0;
        }

        // Wool & Canvas Sail Material (~150 kg/m^3)
        if (block == Blocks.WHITE_WOOL || block.getDescriptionId().contains("sail")) {
            return 150.0;
        }

        // Default baseline mass for unspecified solid blocks
        return 500.0;
    }
}