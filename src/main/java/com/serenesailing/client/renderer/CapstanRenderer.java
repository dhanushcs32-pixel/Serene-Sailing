package com.serenesailing.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.serenesailing.entity.CapstanBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Capstan block entity renderer: draws the deployed mooring chain as a column
 * of alternating crossed-link quads running from the underside of the capstan
 * drum down to the seabed, finished with an iron anchor head (shank + fluke
 * crossbar). The length animates smoothly via
 * {@code CapstanBlockEntity#getInterpolatedChainLength(partialTick)}.
 */
public class CapstanRenderer implements BlockEntityRenderer<CapstanBlockEntity> {

    /** Chain hangs from the underside of the drum (block-local Y). */
    private static final float CHAIN_START_Y = 0.2f;
    /** Half-width of the crossed-link column [blocks]. */
    private static final float LINK_HALF_W = 0.08f;
    /** Vertical extent of one chain link segment [blocks]. */
    private static final float LINK_SPAN = 0.25f;
    /** Anchor fluke half-width [blocks]. */
    private static final float FLUKE_HALF_W = 0.35f;
    /** Anchor bar thickness [blocks]. */
    private static final float BAR_T = 0.05f;

    public CapstanRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(CapstanBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int light, int overlay) {
        float length = be.getInterpolatedChainLength(partialTick);
        if (length <= 0.05f) {
            // Anchor weighed - nothing deployed to draw.
            return;
        }

        // Sprites pulled directly from the baked block atlas (this is what
        // makes the chain texture actually show up on the quads).
        TextureAtlas chainAtlas = Minecraft.getInstance().getModelManager()
                .getAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite chainSprite = chainAtlas.getSprite(
                ResourceLocation.parse("minecraft:block/chain"));
        TextureAtlasSprite ironSprite = chainAtlas.getSprite(
                ResourceLocation.parse("minecraft:block/iron_block"));

        int packedLight = be.getLevel() != null
                ? LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos())
                : LightTexture.FULL_BRIGHT;
        int packedOverlay = OverlayTexture.NO_OVERLAY;

        poseStack.pushPose();
        // Pivot at the center-bottom of the capstan drum.
        poseStack.translate(0.5, CHAIN_START_Y, 0.5);

        // Double-sided cutout on the block atlas: crossed-link quads are
        // single-sided ribbons, and both faces must render.
        VertexConsumer builder = bufferSource.getBuffer(
                RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));

        // ---- Chain: alternating crossed-link segments -----------------------
        int segments = Mth.ceil(length / LINK_SPAN);
        for (int i = 0; i < segments; i++) {
            float yTop = -(i * LINK_SPAN);
            float yBot = Math.max(yTop - LINK_SPAN, -length);
            if (yTop <= yBot) {
                break;
            }
            crossedQuads(poseStack.last(), builder, chainSprite, packedLight, packedOverlay,
                    yTop, yBot, LINK_HALF_W, i % 2 != 0);
        }

        // ---- Anchor head resting on the seabed top surface ------------------
        float anchorY = -length;
        // Central vertical iron shank running up the chain's tail.
        box(poseStack.last(), builder, ironSprite, packedLight, packedOverlay,
                -BAR_T, anchorY, -BAR_T, BAR_T, anchorY + 0.5f, BAR_T);
        // Horizontal fluke crossbar along X.
        box(poseStack.last(), builder, ironSprite, packedLight, packedOverlay,
                -FLUKE_HALF_W, anchorY - BAR_T, -BAR_T, FLUKE_HALF_W, anchorY, BAR_T);
        // Perpendicular fluke crossbar along Z.
        box(poseStack.last(), builder, ironSprite, packedLight, packedOverlay,
                -BAR_T, anchorY - BAR_T, -FLUKE_HALF_W, BAR_T, anchorY, FLUKE_HALF_W);

        poseStack.popPose();
    }

    /**
     * Two vertical diagonal quads crossing in an X (viewed from above),
     * rotated 90 degrees around Y when {@code flipped}.
     */
    private static void crossedQuads(PoseStack.Pose pose, VertexConsumer vc,
                                     TextureAtlasSprite sprite, int light, int overlay,
                                     float yTop, float yBot, float halfW, boolean flipped) {
        // Diagonal plane A: (-h, z=-h) -> (+h, z=+h)
        quad(pose, vc, sprite, light, overlay,
                -halfW, yBot, -halfW, halfW, yBot, halfW,
                halfW, yTop, halfW, -halfW, yTop, -halfW);
        // Diagonal plane B: (-h, z=+h) -> (+h, z=-h)
        quad(pose, vc, sprite, light, overlay,
                -halfW, yBot, halfW, halfW, yBot, -halfW,
                halfW, yTop, -halfW, -halfW, yTop, halfW);
    }

    /** Axis-aligned box with all six outside-facing CCW faces. */
    private static void box(PoseStack.Pose pose, VertexConsumer vc, TextureAtlasSprite sprite,
                            int light, int overlay,
                            float x0, float y0, float z0,
                            float x1, float y1, float z1) {
        // North (-Z)
        quad(pose, vc, sprite, light, overlay, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z0);
        // South (+Z)
        quad(pose, vc, sprite, light, overlay, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
        // West (-X)
        quad(pose, vc, sprite, light, overlay, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
        // East (+X)
        quad(pose, vc, sprite, light, overlay, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0);
        // Down (-Y)
        quad(pose, vc, sprite, light, overlay, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0);
        // Up (+Y)
        quad(pose, vc, sprite, light, overlay, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
    }

    /** Emits one texture-mapped, lit quad from four corners. */
    private static void quad(PoseStack.Pose pose, VertexConsumer vc, TextureAtlasSprite sprite,
                             int light, int overlay,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz) {
        vertex(pose, vc, sprite, light, overlay, ax, ay, az, 1.0f, 1.0f);
        vertex(pose, vc, sprite, light, overlay, bx, by, bz, 1.0f, 0.0f);
        vertex(pose, vc, sprite, light, overlay, cx, cy, cz, 0.0f, 0.0f);
        vertex(pose, vc, sprite, light, overlay, dx, dy, dz, 0.0f, 1.0f);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, TextureAtlasSprite sprite,
                               int light, int overlay,
                               float x, float y, float z, float uFrac, float vFrac) {
        float u = Mth.lerp(uFrac, sprite.getU0(), sprite.getU1());
        float v = Mth.lerp(vFrac, sprite.getV0(), sprite.getV1());
        vc.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, 0.0f, 1.0f, 0.0f);
    }
}
