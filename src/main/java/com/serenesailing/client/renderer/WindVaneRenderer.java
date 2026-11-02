package com.serenesailing.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.serenesailing.SereneSailing;
import com.serenesailing.entity.WindVaneBlockEntity;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Masthead nautical wind vane BlockEntityRenderer.
 *
 * <p>The static block model ({@code wind_vane.json}) renders the stepped deck plate
 * and slender vertical spindle rod. This BER renders the sleek, minimalist rotating pointer
 * (pivot collar, slender horizontal boom, windward arrowhead, and leeward stabilizing tail fin)
 * rotating smoothly on the spindle axis to point directly into the apparent wind.</p>
 */
public class WindVaneRenderer implements BlockEntityRenderer<WindVaneBlockEntity> {

    /** Atlas material for the vane texture. */
    private static final Material VANE_MATERIAL = new Material(
            TextureAtlas.LOCATION_BLOCKS,
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "block/wind_vane"));

    // 17th-century wrought-iron pointer and copper tail pennant palette
    private static final int IRON_RING_R = 140, IRON_RING_G = 140, IRON_RING_B = 145;
    private static final int IRON_SHAFT_R = 170, IRON_SHAFT_G = 170, IRON_SHAFT_B = 175;
    private static final int COPPER_PENNANT_R = 185, COPPER_PENNANT_G = 120, COPPER_PENNANT_B = 80;

    public WindVaneRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(WindVaneBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int light, int overlay) {
        float angleDeg = Mth.rotLerp(partialTick, be.getPrevAngle(), be.getCurrentAngle());
        net.minecraft.core.Direction facing = be.getBlockState().hasProperty(com.serenesailing.block.WindVaneBlock.FACING)
                ? be.getBlockState().getValue(com.serenesailing.block.WindVaneBlock.FACING)
                : net.minecraft.core.Direction.NORTH;
        float facingYaw = facing.toYRot();
        float renderAngle = angleDeg - facingYaw;

        poseStack.pushPose();
        // Pivot on the vertical spindle axis at block center (0.5, 0.0, 0.5)
        poseStack.translate(0.5, 0.0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-renderAngle));

        VertexConsumer consumer = VANE_MATERIAL.buffer(bufferSource, RenderType::entityCutoutNoCull);
        TextureAtlasSprite sprite = VANE_MATERIAL.sprite();
        int worldLight = be.getLevel() != null
                ? LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos())
                : LightTexture.FULL_BRIGHT;
        int packedOverlay = overlay != 0 ? overlay : OverlayTexture.NO_OVERLAY;

        PoseStack.Pose pose = poseStack.last();

        // 1. Wrought-Iron Pivot Ring around spindle (Y = 12.0..13.2)
        renderBox(pose, consumer, sprite, worldLight, packedOverlay,
                IRON_RING_R, IRON_RING_G, IRON_RING_B, 255,
                -0.056f, 0.750f, -0.056f,
                 0.056f, 0.825f,  0.056f);

        // 2. Slender Horizontal Boom Shaft (0.8px thick, Z = -0.42..+0.42)
        renderBox(pose, consumer, sprite, worldLight, packedOverlay,
                IRON_SHAFT_R, IRON_SHAFT_G, IRON_SHAFT_B, 255,
                -0.025f, 0.770f, -0.420f,
                 0.025f, 0.810f,  0.420f);

        // 3. Wrought-Iron Pointed Arrowhead at front (-Z / Windward)
        // Apex tip
        renderBox(pose, consumer, sprite, worldLight, packedOverlay,
                IRON_SHAFT_R, IRON_SHAFT_G, IRON_SHAFT_B, 255,
                -0.015f, 0.775f, -0.520f,
                 0.015f, 0.805f, -0.420f);
        // Barb flares
        renderBox(pose, consumer, sprite, worldLight, packedOverlay,
                IRON_SHAFT_R, IRON_SHAFT_G, IRON_SHAFT_B, 255,
                -0.050f, 0.765f, -0.420f,
                 0.050f, 0.815f, -0.340f);

        // 4. Weathered Copper Tail Pennant at back (+Z / Leeward)
        renderBox(pose, consumer, sprite, worldLight, packedOverlay,
                COPPER_PENNANT_R, COPPER_PENNANT_G, COPPER_PENNANT_B, 255,
                -0.015f, 0.700f,  0.280f,
                 0.015f, 0.880f,  0.500f);

        poseStack.popPose();
    }

    /** Directional per-face shading to give realistic 3D depth and highlights. */
    private static float computeShading(float nx, float ny, float nz) {
        float yFactor = ny > 0.0f ? 1.0f : 0.55f;
        float zFactor = nz > 0.0f ? 0.95f : 0.70f;
        float xFactor = 0.68f;
        float shade = Math.abs(ny) * yFactor + Math.abs(nz) * zFactor + Math.abs(nx) * xFactor;
        return Mth.clamp(shade, 0.45f, 1.0f);
    }

    /** Renders an axis-aligned box with explicit CCW quads for all 6 faces and directional shading. */
    private static void renderBox(PoseStack.Pose pose, VertexConsumer vc,
                                  TextureAtlasSprite sprite, int light, int overlay,
                                  int r, int g, int b, int a,
                                  float x0, float y0, float z0,
                                  float x1, float y1, float z1) {
        // North (-Z)
        quad(pose, vc, sprite, light, overlay, r, g, b, a, 0, 0, -1,
                x1, y0, z0,  x1, y1, z0,  x0, y1, z0,  x0, y0, z0);
        // South (+Z)
        quad(pose, vc, sprite, light, overlay, r, g, b, a, 0, 0, 1,
                x0, y0, z1,  x0, y1, z1,  x1, y1, z1,  x1, y0, z1);
        // West (-X)
        quad(pose, vc, sprite, light, overlay, r, g, b, a, -1, 0, 0,
                x0, y0, z0,  x0, y1, z0,  x0, y1, z1,  x0, y0, z1);
        // East (+X)
        quad(pose, vc, sprite, light, overlay, r, g, b, a, 1, 0, 0,
                x1, y0, z1,  x1, y1, z1,  x1, y1, z0,  x1, y0, z0);
        // Down (-Y)
        quad(pose, vc, sprite, light, overlay, r, g, b, a, 0, -1, 0,
                x0, y0, z0,  x0, y0, z1,  x1, y0, z1,  x1, y0, z0);
        // Up (+Y)
        quad(pose, vc, sprite, light, overlay, r, g, b, a, 0, 1, 0,
                x0, y1, z0,  x0, y1, z1,  x1, y1, z1,  x1, y1, z0);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer vc, TextureAtlasSprite sprite,
                             int light, int overlay, int r, int g, int b, int a,
                             float nx, float ny, float nz,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz) {
        float shade = computeShading(nx, ny, nz);
        int sr = Mth.clamp((int) (r * shade), 0, 255);
        int sg = Mth.clamp((int) (g * shade), 0, 255);
        int sb = Mth.clamp((int) (b * shade), 0, 255);

        vertex(pose, vc, sprite, light, overlay, sr, sg, sb, a, ax, ay, az, 1.0f, 1.0f, nx, ny, nz);
        vertex(pose, vc, sprite, light, overlay, sr, sg, sb, a, bx, by, bz, 1.0f, 0.0f, nx, ny, nz);
        vertex(pose, vc, sprite, light, overlay, sr, sg, sb, a, cx, cy, cz, 0.0f, 0.0f, nx, ny, nz);
        vertex(pose, vc, sprite, light, overlay, sr, sg, sb, a, dx, dy, dz, 0.0f, 1.0f, nx, ny, nz);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, TextureAtlasSprite sprite,
                               int light, int overlay, int r, int g, int b, int a,
                               float x, float y, float z,
                               float uFrac, float vFrac,
                               float nx, float ny, float nz) {
        float u = sprite.getU0() + (sprite.getU1() - sprite.getU0()) * uFrac;
        float v = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * vFrac;
        vc.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
