package com.serenesailing.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.serenesailing.SereneSailing;
import com.serenesailing.block.RudderBlock;
import com.serenesailing.entity.RudderBlockEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * Dynamic rudder block entity renderer: swings the physical rudder blade
 * in real time based on the authoritative steering angle.
 */
public class RudderRenderer implements BlockEntityRenderer<RudderBlockEntity> {

    /** Atlas material for the rudder texture. */
    private static final Material RUDDER_MATERIAL = new Material(
            TextureAtlas.LOCATION_BLOCKS,
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "block/rudder"));

    // Rudder blade geometry relative to the hinge pivot pin (in units of blocks):
    // 2-pixel-thick centered blade spanning along the vessel's centerline.
    private static final float BLADE_HALF_W = 1.0f / 16.0f; // 2 pixels thick ([-1/16, +1/16])
    private static final float BLADE_Y0 = 0.0f;
    private static final float BLADE_Y1 = 1.0f; // 1 block tall
    private static final float BLADE_Z0 = -0.5f;
    private static final float BLADE_Z1 = 0.25f;

    public RudderRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RudderBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int light, int overlay) {
        float steeringAngle = blockEntity.getSteeringAngle();

        poseStack.pushPose();
        // Translate to the hinge pivot pin at the center of the block.
        poseStack.translate(0.5, 0.0, 0.5);

        // Orient according to the block's placed facing direction.
        Direction facing = blockEntity.getBlockState().hasProperty(RudderBlock.FACING)
                ? blockEntity.getBlockState().getValue(RudderBlock.FACING)
                : Direction.NORTH;
        float facingAngle = switch (facing) {
            case EAST -> 90.0f;
            case SOUTH -> 180.0f;
            case WEST -> 270.0f;
            default -> 0.0f;
        };
        poseStack.mulPose(Axis.YP.rotationDegrees(-facingAngle));

        // Apply visual rudder deflection angle.
        poseStack.mulPose(Axis.YP.rotationDegrees(-steeringAngle));

        VertexConsumer consumer = RUDDER_MATERIAL.buffer(bufferSource, RenderType::entityCutoutNoCull);
        TextureAtlasSprite sprite = RUDDER_MATERIAL.sprite();
        int packedLight = blockEntity.getLevel() != null
                ? LevelRenderer.getLightColor(blockEntity.getLevel(), blockEntity.getBlockPos())
                : LightTexture.FULL_BRIGHT;

        drawBladeBox(poseStack.last(), consumer, sprite, packedLight, OverlayTexture.NO_OVERLAY);

        poseStack.popPose();
    }

    private static void drawBladeBox(PoseStack.Pose pose, VertexConsumer vc,
                                     TextureAtlasSprite sprite, int light, int overlay) {
        float x0 = -BLADE_HALF_W, x1 = BLADE_HALF_W;
        float y0 = BLADE_Y0, y1 = BLADE_Y1;
        float z0 = BLADE_Z0, z1 = BLADE_Z1;

        // North (-Z)
        quad(pose, vc, sprite, light, overlay, 0, 0, -1,
                x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z0);
        // South (+Z)
        quad(pose, vc, sprite, light, overlay, 0, 0, 1,
                x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
        // West (-X)
        quad(pose, vc, sprite, light, overlay, -1, 0, 0,
                x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
        // East (+X)
        quad(pose, vc, sprite, light, overlay, 1, 0, 0,
                x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0);
        // Down (-Y)
        quad(pose, vc, sprite, light, overlay, 0, -1, 0,
                x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0);
        // Up (+Y)
        quad(pose, vc, sprite, light, overlay, 0, 1, 0,
                x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer vc, TextureAtlasSprite sprite,
                             int light, int overlay, float nx, float ny, float nz,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz) {
        vertex(pose, vc, sprite, light, overlay, ax, ay, az, 1.0f, 1.0f, nx, ny, nz);
        vertex(pose, vc, sprite, light, overlay, bx, by, bz, 1.0f, 0.0f, nx, ny, nz);
        vertex(pose, vc, sprite, light, overlay, cx, cy, cz, 0.0f, 0.0f, nx, ny, nz);
        vertex(pose, vc, sprite, light, overlay, dx, dy, dz, 0.0f, 1.0f, nx, ny, nz);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, TextureAtlasSprite sprite,
                               int light, int overlay,
                               float x, float y, float z,
                               float uFrac, float vFrac,
                               float nx, float ny, float nz) {
        float u = sprite.getU0() + (sprite.getU1() - sprite.getU0()) * uFrac;
        float v = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * vFrac;
        vc.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
