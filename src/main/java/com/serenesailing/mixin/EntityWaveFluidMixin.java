package com.serenesailing.mixin;

import com.serenesailing.hold.HoldRegistry;
import com.serenesailing.physics.waves.WaveSurface;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes players, mobs and item entities "touch water" according to the wave surface, not the water blocks.
 *
 * <p>Vanilla derives {@code fluidHeight[WATER]} (which drives {@code isInWater()}, swimming, item buoyancy
 * and fall-damage reset) from block fluid heights. Over a crest those blocks are air, so a swimmer falls
 * through the visible wave; in a trough the blocks are still water, so an entity floats in mid-air.
 *
 * <p>Dry holds are checked EXPLICITLY here ({@link HoldRegistry#isEntityInDryHold}). This handler runs at RETURN of
 * the same overload {@link EntityAirPocketMixin} cancels at HEAD, and a RETURN injection can also attach to the return
 * that a HEAD cancellation inserts, so it cannot be assumed that this code is skipped when the hold mixin cancels:
 * without the guard an airborne entity (jumping, falling through a hatch) inside a dry hold would get wave-water
 * physics and the underwater overlay during a storm.
 */
@Mixin(Entity.class)
public abstract class EntityWaveFluidMixin {

    @Shadow protected Object2DoubleMap<TagKey<Fluid>> fluidHeight;
    @Shadow protected Object2DoubleMap<FluidType> forgeFluidTypeHeight;

    @Inject(method = "updateFluidHeightAndDoFluidPushing(Lnet/minecraft/tags/TagKey;D)Z",
            at = @At("RETURN"), cancellable = true, require = 1)
    private void serenesailing$waveFluidHeight(TagKey<Fluid> tag, double scale, CallbackInfoReturnable<Boolean> cir) {
        if (!FluidTags.WATER.equals(tag)) {
            return;
        }
        Entity self = (Entity) (Object) this;
        // Standing on a Sable deck / dock / the seabed is not swimming, whatever the crest below is doing.
        if (self.onGround() || !WaveSurface.isEntityInWaveZone(self)) {
            return;
        }
        // The sea cannot reach this entity: it is inside a dry hold. Leave whatever the hold logic decided.
        if (HoldRegistry.anyActive(self.level())
                && HoldRegistry.isEntityInDryHold(self.level(), self.position(), self.getBoundingBox().getCenter())) {
            return;
        }
        double surface = WaveSurface.surfaceY(self.level(), self.getX(), self.getZ(), 0.0f);
        double depth = surface - self.getBoundingBox().minY;
        FluidType water = NeoForgeMod.WATER_TYPE.value();

        if (depth > 0.0) {
            this.fluidHeight.put(FluidTags.WATER, depth);
            this.forgeFluidTypeHeight.put(water, depth);
            cir.setReturnValue(true);
            return;
        }

        // Wave surface is below the feet: the water blocks there are an illusion. Drop the vanilla water
        // contact unless some other fluid (lava) is what made the original call return true.
        boolean otherFluid = false;
        for (Object2DoubleMap.Entry<FluidType> e : this.forgeFluidTypeHeight.object2DoubleEntrySet()) {
            if (e.getKey() != water && e.getDoubleValue() > 0.0) {
                otherFluid = true;
                break;
            }
        }
        this.fluidHeight.put(FluidTags.WATER, 0.0);
        this.forgeFluidTypeHeight.removeDouble(water);
        if (!otherFluid) {
            cir.setReturnValue(false);
        }
    }

    /** The eyes are in water while they are below the wave surface (vanilla uses block fluid heights). */
    @Inject(method = "isEyeInFluid", at = @At("RETURN"), cancellable = true, require = 1)
    private void serenesailing$waveEyeInFluid(TagKey<Fluid> tag, CallbackInfoReturnable<Boolean> cir) {
        if (!FluidTags.WATER.equals(tag)) {
            return;
        }
        Entity self = (Entity) (Object) this;
        if (self.onGround() || !WaveSurface.isEntityInWaveZone(self)) {
            return;
        }
        if (HoldRegistry.anyActive(self.level()) && HoldRegistry.isOccluded(self.level(), self.getEyePosition())) {
            return; // eyes inside a dry hold: not in water, whatever the wave surface says
        }
        double surface = WaveSurface.surfaceY(self.level(), self.getX(), self.getZ(), 0.0f);
        cir.setReturnValue(self.getEyeY() - 0.11111111 < surface);
    }
}
