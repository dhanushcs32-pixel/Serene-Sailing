package com.serenesailing.mixin;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guards {@link NoiseBasedChunkGenerator#getBaseHeight} - the concrete override
 * feeding {@code iterateNoiseColumn} and the crashing
 * {@code Aquifer$NoiseBasedAquifer}. (getBaseHeight is abstract on ChunkGenerator
 * and cannot be injected there; this subclass override is where every real
 * terrain query lands.)
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {

    @Inject(
            method = "getBaseHeight(IILnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void serenesailing$guardBaseHeight(int x, int z, Heightmap.Types type,
                                               LevelHeightAccessor level, RandomState random,
                                               CallbackInfoReturnable<Integer> cir) {
        // Not Math.abs: Math.abs(Integer.MIN_VALUE) is still negative, and -2^31 is exactly the plot boundary.
        if (x > 100_000_000 || x < -100_000_000 || z > 100_000_000 || z < -100_000_000) {
            cir.setReturnValue(level.getMinBuildHeight());
        }
    }
}
