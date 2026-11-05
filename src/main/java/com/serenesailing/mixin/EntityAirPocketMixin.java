package com.serenesailing.mixin;

import com.serenesailing.hold.HoldRegistry;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps entities inside dry sunken holds out of the ocean's water physics, independent of whether Sable's own
 * water-occlusion hook runs for the call.
 *
 * <p>Why this hooks {@code updateFluidHeightAndDoFluidPushing(TagKey, double)} and NOT
 * {@code updateInWaterStateAndDoFluidPushing}: NeoForge's {@code updateInWaterStateAndDoFluidPushing} starts by
 * clearing {@code fluidHeight} and {@code forgeFluidTypeHeight}. Cancelling it at HEAD (what this mixin used to
 * do) skipped those clears, so the last ocean-water height an entity had before stepping into the hold stayed
 * for ever, {@code isInFluidType()} stayed true and {@code LivingEntity.travel} ran wading / swimming physics in
 * dry air. Here the outer method runs untouched (clears included) and only the water probe is skipped; the two
 * height maps are additionally purged of water so a caller that reaches this overload directly can never leave
 * a stale value behind.
 *
 * <p>Known limitation, shared with Sable's own hook: NeoForge resolves ALL fluid types in one pass behind this
 * overload, so lava is not probed for an entity inside a dry hold either.
 */
@Mixin(Entity.class)
public abstract class EntityAirPocketMixin {

    @Shadow public abstract Level level();
    @Shadow public abstract Vec3 position();
    @Shadow public abstract AABB getBoundingBox();
    @Shadow public abstract Vec3 getEyePosition();
    @Shadow protected Object2DoubleMap<TagKey<Fluid>> fluidHeight;
    @Shadow protected Object2DoubleMap<FluidType> forgeFluidTypeHeight;

    // require = 1 (also the mixin config default): a wrong target must fail loudly at startup, not silently.
    @Inject(method = "updateFluidHeightAndDoFluidPushing(Lnet/minecraft/tags/TagKey;D)Z",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void serenesailing$dryHoldFluidPush(TagKey<Fluid> tag, double scale, CallbackInfoReturnable<Boolean> cir) {
        if (!FluidTags.WATER.equals(tag)) {
            return;
        }
        if (HoldRegistry.isEntityInDryHold(this.level(), this.position(), this.getBoundingBox().getCenter())) {
            this.fluidHeight.removeDouble(FluidTags.WATER);
            this.forgeFluidTypeHeight.removeDouble(NeoForgeMod.WATER_TYPE.value());
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isEyeInFluid", at = @At("HEAD"), cancellable = true, require = 1)
    private void serenesailing$dryHoldEyes(TagKey<Fluid> tag, CallbackInfoReturnable<Boolean> cir) {
        if (FluidTags.WATER.equals(tag) && HoldRegistry.isOccluded(this.level(), this.getEyePosition())) {
            cir.setReturnValue(false);
        }
    }
}
