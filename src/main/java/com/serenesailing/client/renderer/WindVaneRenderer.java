package com.serenesailing.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.serenesailing.SereneSailing;
import com.serenesailing.entity.WindVaneBlockEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Masthead wind vane block entity renderer.
 *
 * <p>The static block model ({@code wind_vane.json}) renders the timber
 * mount and the vertical bronze spindle; this BER renders ONLY the rotating
 * pennant / feather blade, spinning on the spindle's Y axis to point along
 * the apparent wind angle broadcast by the server ({@code be.getApparentWindAngle()}).</p>
 */
public class WindVaneRenderer implements BlockEntityRenderer<WindVaneBlockEntity> {

    /** Atlas material for the vane texture (same texture as the static model). */
    private static final Material VANE_MATERIAL = new Material(
            TextureAtlas.LOCATION_BLOCKS,
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "block/wind_vane"));

    // Pennant geometry in block-local units (16 = 1 block). The static model's
    // pennant occupied x [3,13], y [11,13], z [7,9]; the BER draws the same
    // box around the spindle axis at (0.5, *, 0.5).
    private static final float PX0 = -5.0f / 16.0f, PX1 = 5.0f / 16.0f;
    private static final float PY0 = 11.0f / 16.0f, PY1 = 13.0f / 16.0f;
    private static final float PZ0 = -1.0f / 16.0f, PZ1 = 1.0f / 16.0f;

    public WindVaneRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(WindVaneBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int light, int overlay) {
        float angleDeg = be.getInterpolatedWindAngle(partialTick);

        poseStack.pushPose();
        // Pivot on the bronze spindle at the centre of the block.
        poseStack.translate(0.5, 0.0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-angleDeg));

        VertexConsumer consumer = VANE_MATERIAL.buffer(bufferSource, RenderType::entitySolid);
        TextureAtlasSprite sprite = VANE_MATERIAL.sprite();
        int worldLight = be.getLevel() != null
                ? LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos())
                : LightTexture.FULL_BRIGHT;
        drawPennantBox(poseStack.last(), consumer, sprite, worldLight,
                OverlayTexture.NO_OVERLAY);

        poseStack.popPose();
    }

    /** Draws the pennant box with outside-facing CCW quads for all six sides. */
    private static void drawPennantBox(PoseStack.Pose pose, VertexConsumer vc,
                                       TextureAtlasSprite sprite, int light, int overlay) {
        float x0 = PX0, x1 = PX1, y0 = PY0, y1 = PY1, z0 = PZ0, z1 = PZ1;

        // North (-Z)
        quad(pose, vc, sprite, light, overlay, 0, 0, -1,
                x1, y0, z0,  x1, y1, z0,  x0, y1, z0,  x0, y0, z0);
        // South (+Z)
        quad(pose, vc, sprite, light, overlay, 0, 0, 1,
                x0, y0, z1,  x0, y1, z1,  x1, y1, z1,  x1, y0, z1);
        // West (-X)
        quad(pose, vc, sprite, light, overlay, -1, 0, 0,
                x0, y0, z0,  x0, y1, z0,  x0, y1, z1,  x0, y0, z1);
        // East (+X)
        quad(pose, vc, sprite, light, overlay, 1, 0, 0,
                x1, y0, z1,  x1, y1, z1,  x1, y1, z0,  x1, y0, z0);
        // Down (-Y)
        quad(pose, vc, sprite, light, overlay, 0, -1, 0,
                x0, y0, z0,  x0, y0, z1,  x1, y0, z1,  x1, y0, z0);
        // Up (+Y)
        quad(pose, vc, sprite, light, overlay, 0, 1, 0,
                x0, y1, z0,  x0, y1, z1,  x1, y1, z1,  x1, y1, z0);
    }

    /** Emits one texture-mapped, lit quad from four CCW corners. */
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
        float u = Mth.lerp(uFrac, sprite.getU0(), sprite.getU1());
        float v = Mth.lerp(vFrac, sprite.getV0(), sprite.getV1());
        vc.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
