package com.serenesailing.mixin;

import com.serenesailing.physics.ShipCabinHelper;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Mixin into {@link Entity} to mask water state, fluid movement resistance,
 * and eye-in-fluid effects when an entity is standing inside an enclosed, dry
 * cabin or lower deck of an active sailing vessel.
 */
@Mixin(Entity.class)
public abstract class EntityCabinWaterMaskMixin {

    @Shadow
    protected boolean wasTouchingWater;

    @Shadow
    private boolean wasEyeInWater;

    @Shadow
    protected Object2DoubleMap<TagKey<Fluid>> fluidHeight;

    @Shadow
    protected Object2DoubleMap<FluidType> forgeFluidTypeHeight;

    @Shadow
    private Set<TagKey<Fluid>> fluidOnEyes;

    @Shadow
    private FluidType forgeFluidTypeOnEyes;

    @Shadow
    public abstract Level level();

    @Shadow
    public abstract double getX();

    @Shadow
    public abstract double getY();

    @Shadow
    public abstract double getZ();

    @Shadow
    public abstract double getEyeY();

    @Inject(method = "updateInWaterStateAndDoWaterCurrentPushing", at = @At("RETURN"))
    private void serenesailing$maskWaterInCabin(CallbackInfo ci) {
        if (ShipCabinHelper.isInsideShipCabin(this.level(), this.getX(), this.getY(), this.getZ())) {
            this.wasTouchingWater = false;
            if (this.fluidHeight != null) {
                this.fluidHeight.clear();
            }
            if (this.forgeFluidTypeHeight != null) {
                this.forgeFluidTypeHeight.clear();
            }
        }
    }

    @Inject(method = "updateFluidOnEyes", at = @At("RETURN"))
    private void serenesailing$maskFluidOnEyesInCabin(CallbackInfo ci) {
        if (ShipCabinHelper.isInsideShipCabin(this.level(), this.getX(), this.getEyeY(), this.getZ())) {
            this.wasEyeInWater = false;
            if (this.fluidOnEyes != null) {
                this.fluidOnEyes.clear();
            }
            this.forgeFluidTypeOnEyes = NeoForgeMod.EMPTY_TYPE.value();
        }
    }

    @Inject(method = "isEyeInFluid", at = @At("HEAD"), cancellable = true)
    private void serenesailing$cancelEyeInFluidInCabin(TagKey<Fluid> fluidTag, CallbackInfoReturnable<Boolean> cir) {
        if (ShipCabinHelper.isInsideShipCabin(this.level(), this.getX(), this.getEyeY(), this.getZ())) {
            cir.setReturnValue(false);
        }
    }
}
