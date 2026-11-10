package com.serenesailing.mixin;

import com.serenesailing.physics.waves.WaveSurface;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes vanilla boats float on the Gerstner surface instead of the integer water blocks.
 *
 * <p>Vanilla {@code Boat.getStatus()} decides IN_WATER / UNDER_WATER / IN_AIR by reading water <i>blocks</i>.
 * Over a wave crest (Y up to ~65) the blocks above Y=62 are air, so vanilla reports IN_AIR, applies gravity
 * and the boat falls back to the flat sea. These three hooks replace the block-derived answer with the
 * {@link WaveSurface} height while the boat is over open sea, on whichever side runs the boat's physics
 * (the pilot's client for a player-driven boat, the server for an unmanned one).
 *
 * <p>All three targets are private vanilla methods; names are Mojang-mapped and {@code require = 1}
 * (the project default) makes a mismatch fail loudly at startup.
 */
@Mixin(Boat.class)
public abstract class BoatWaveMixin {

    /** Water surface Y that vanilla's floatBoat() uses as the buoyancy reference. */
    @Shadow private double waterLevel;

    /** A boat is only reported UNDER_WATER (which makes it sink and eject riders) when this deep below the surface. */
    private static final double SS_SUBMERGED_MARGIN = 1.0;

    /**
     * checkInWater(): vanilla scans fluid blocks under the hull and sets {@code waterLevel} to the highest
     * block fluid height. Replace it with the wave surface: the boat is "in water" while its keel is below it,
     * even when that surface is a crest high above the (air) blocks.
     */
    @Inject(method = "checkInWater", at = @At("RETURN"), cancellable = true, require = 1)
    private void serenesailing$waveCheckInWater(CallbackInfoReturnable<Boolean> cir) {
        Boat self = (Boat) (Object) this;
        if (!WaveSurface.isEntityInWaveZone(self)) {
            return;
        }
        double surface = WaveSurface.surfaceY(self.level(), self.getX(), self.getZ(), 0.0f);
        AABB box = self.getBoundingBox();
        this.waterLevel = surface;
        cir.setReturnValue(box.minY < surface);
    }

    /**
     * isUnderwater(): vanilla looks at the fluid block at the top of the hull. In a trough the blocks above the
     * wave are still water, so a boat sitting correctly on the surface would be flagged UNDER_WATER (it then
     * sinks and ejects its passengers after 60 ticks). Only report it when it is genuinely swamped.
     */
    @Inject(method = "isUnderwater", at = @At("RETURN"), cancellable = true, require = 1)
    private void serenesailing$waveIsUnderwater(CallbackInfoReturnable<Boat.Status> cir) {
        Boat self = (Boat) (Object) this;
        if (!WaveSurface.isEntityInWaveZone(self)) {
            return;
        }
        double surface = WaveSurface.surfaceY(self.level(), self.getX(), self.getZ(), 0.0f);
        double top = self.getBoundingBox().maxY;
        if (top + 0.001 < surface - SS_SUBMERGED_MARGIN) {
            this.waterLevel = top;
            cir.setReturnValue(Boat.Status.UNDER_WATER);
        } else {
            cir.setReturnValue(null);
        }
    }

    /**
     * getWaterLevelAbove(): used when a boat touches water again after IN_AIR to snap it to the surface
     * ({@code setPos(.., waterLevelAbove - height + 0.101, ..)}). Without this it would snap down to Y=62.89.
     */
    @Inject(method = "getWaterLevelAbove", at = @At("RETURN"), cancellable = true, require = 1)
    private void serenesailing$waveWaterLevelAbove(CallbackInfoReturnable<Float> cir) {
        Boat self = (Boat) (Object) this;
        if (!WaveSurface.isEntityInWaveZone(self)) {
            return;
        }
        cir.setReturnValue((float) WaveSurface.surfaceY(self.level(), self.getX(), self.getZ(), 0.0f));
    }
}

