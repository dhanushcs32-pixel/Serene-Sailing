package com.serenesailing.mixin;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses vanilla terrain-height queries inside Sable sub-level plot space
 * (re-introduced alongside the Aeronautics Physics Assembler integration).
 *
 * <p>These injection points receive <b>block</b> coordinates. At the Sable plot
 * boundary the block coordinate is exactly -2^31 and overflows/wraps for chunks
 * beyond it - magnitudes stay around 2.1e9. Legit gameplay never exceeds the
 * vanilla world border (+-30,000,000 blocks), so a 1e8 threshold catches every
 * plot-space query and never false-positives on real terrain.</p>
 *
 * <p>No refmap: NeoForge runs Mojang official mappings at runtime.</p>
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    /** Plot-space guard threshold in block coordinates. */
    private static final int SABLE_PLOT_COORD_LIMIT = 100_000_000;

    private static boolean serenesailing$isPlotSpaceCoordinate(int x, int z) {
        return Math.abs(x) > SABLE_PLOT_COORD_LIMIT || Math.abs(z) > SABLE_PLOT_COORD_LIMIT;
    }

    /**
     * Direct entry point on the crashing pipeline:
     * {@code BuriedTreasureStructure.findValidGenerationPoint} ->
     * {@code getFirstOccupiedHeight}. Returning the minimum build height reports
     * an empty column, so structures bail out before any aquifer math can run.
     */
    @Inject(
            method = "getFirstOccupiedHeight(IILnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void serenesailing$guardFirstOccupiedHeight(int x, int z, Heightmap.Types type,
                                                        LevelHeightAccessor level, RandomState random,
                                                        CallbackInfoReturnable<Integer> cir) {
        if (serenesailing$isPlotSpaceCoordinate(x, z)) {
            cir.setReturnValue(level.getMinBuildHeight());
        }
    }
}
