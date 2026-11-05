package com.serenesailing.client;

import com.serenesailing.SereneSailing;
import com.serenesailing.hold.HoldRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Removes the underwater fog and tint when the camera is below the vanilla surface (62.8889) but inside a dry
 * hold. Sable's camera mixin already reports FogType.NONE for an occluded camera, but its FogRenderer mixin
 * deliberately bypasses that ("for now we're okay with water fog in the camera"), so vanilla still fogs the
 * hold blue whenever the eye dips below the water surface, e.g. standing on a low hold floor or crouching.
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID, value = Dist.CLIENT)
public final class HoldFogHandler {

    private HoldFogHandler() {
    }

    /** Camera in water by vanilla rules (below the water block's surface) AND inside a registered hold. */
    private static boolean cameraInDryHold(Camera camera) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        final Vec3 pos = camera.getPosition();
        final BlockPos block = camera.getBlockPosition();
        final FluidState fluid = level.getFluidState(block);
        if (!fluid.is(FluidTags.WATER) || pos.y >= block.getY() + fluid.getHeight(level, block)) {
            return false; // vanilla would not fog this camera as water anyway
        }
        return HoldRegistry.isOccluded(level, pos);
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getType() != FogType.WATER || !cameraInDryHold(event.getCamera())) {
            return;
        }
        // Vanilla air fog (FogRenderer.setupFog, FogType.NONE): terrain fog hugs the render distance, sky fog does not.
        final float renderDistance = event.getRenderer().getRenderDistance();
        if (event.getMode() == FogRenderer.FogMode.FOG_SKY) {
            event.setNearPlaneDistance(0.0F);
            event.setFarPlaneDistance(renderDistance);
            event.setFogShape(FogShape.SPHERE);
        } else {
            event.setNearPlaneDistance(renderDistance - Mth.clamp(renderDistance / 10.0F, 4.0F, 64.0F));
            event.setFarPlaneDistance(renderDistance);
            event.setFogShape(FogShape.CYLINDER);
        }
    }

    /**
     * ComputeFogColor carries no fog type and Sable hides the occlusion from vanilla's colour code, so the
     * underwater (biome water fog) colour has to be replaced by an air colour. The level's sky colour is used as an
     * approximation of vanilla's biome fog colour.
     */
    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (!cameraInDryHold(event.getCamera())) {
            return;
        }
        final ClientLevel level = Minecraft.getInstance().level;
        final Vec3 sky = level.getSkyColor(event.getCamera().getPosition(), (float) event.getPartialTick());
        event.setRed((float) sky.x);
        event.setGreen((float) sky.y);
        event.setBlue((float) sky.z);
    }
}
