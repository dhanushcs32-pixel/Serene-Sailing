package com.serenesailing.mixin;

import com.serenesailing.physics.ShipHullAirPocketHelper;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents entities inside dry below-waterline ship holds from entering water states,
 * swimming, or triggering endless water splashes and bubble particle eruptions.
 */
@Mixin(Entity.class)
public abstract class EntityAirPocketMixin {

    @Shadow public abstract Level level();
    @Shadow public abstract Vec3 position();
    @Shadow public abstract void setSwimming(boolean swimming);
    @Shadow protected boolean wasTouchingWater;
    @Shadow protected boolean wasEyeInWater;

    @Inject(method = "updateInWaterStateAndDoFluidPushing", at = @At("HEAD"), cancellable = true, require = 0)
    private void serenesailing$cancelWaterUpdateInDryHold(CallbackInfoReturnable<Boolean> cir) {
        if (ShipHullAirPocketHelper.isEntityInDryShipHold(this.level(), this.position())) {
            this.wasTouchingWater = false;
            this.wasEyeInWater = false;
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "doWaterSplashEffect", at = @At("HEAD"), cancellable = true, require = 0)
    private void serenesailing$cancelSplashEffectInDryHold(CallbackInfo ci) {
        if (ShipHullAirPocketHelper.isEntityInDryShipHold(this.level(), this.position())) {
            this.wasTouchingWater = false;
            ci.cancel();
        }
    }

    @Inject(method = "updateSwimming", at = @At("HEAD"), cancellable = true, require = 0)
    private void serenesailing$cancelSwimmingInDryHold(CallbackInfo ci) {
        if (ShipHullAirPocketHelper.isEntityInDryShipHold(this.level(), this.position())) {
            this.setSwimming(false);
            ci.cancel();
        }
    }

    @Inject(method = "isInWater", at = @At("HEAD"), cancellable = true, require = 0)
    private void serenesailing$overrideIsInWaterInDryHold(CallbackInfoReturnable<Boolean> cir) {
        if (ShipHullAirPocketHelper.isEntityInDryShipHold(this.level(), this.position())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isEyeInFluid", at = @At("HEAD"), cancellable = true, require = 0)
    private void serenesailing$overrideIsEyeInFluidInDryHold(TagKey<Fluid> fluidTag, CallbackInfoReturnable<Boolean> cir) {
        if (fluidTag == FluidTags.WATER && ShipHullAirPocketHelper.isEntityInDryShipHold(this.level(), this.position())) {
            cir.setReturnValue(false);
        }
    }
}
