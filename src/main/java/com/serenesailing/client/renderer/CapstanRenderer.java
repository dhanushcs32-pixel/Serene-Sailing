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
    /** Vertical extent (height) of one chain link quad [blocks]. */
    private static final float LINK_HEIGHT = 0.35f;
    /** Vertical step between consecutive chain link origins [blocks] (~0.13f overlap). */
    private static final float LINK_STEP = 0.22f;
    /** Anchor fluke half-width [blocks]. */
    private static final float FLUKE_HALF_W = 0.35f;
    /** Anchor bar thickness [blocks]. */
    private static final float BAR_T = 0.05f;
    /** Height of the anchor's vertical iron shank [blocks]. */
    private static final float SHANK_HEIGHT = 0.50f;

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
        // Continuous fractional float loop; LINK_STEP < LINK_HEIGHT ensures
        // consecutive links overlap and interlock tightly (~0.13f overlap).
        // Stop the chain at the top of the anchor shank so links connect
        // cleanly to the shank without poking through the crossbars.
        float chainEnd = Math.max(0.0f, length - SHANK_HEIGHT);
        int linkIndex = 0;
        for (float y = 0.15f; y < chainEnd; y += LINK_STEP) {
            poseStack.pushPose();
            // Translate downward ONLY on Y — X/Z are already centered by the
            // outer translate(0.5, ..., 0.5) above.
            poseStack.translate(0.0, -y, 0.0);
            boolean flipped = (linkIndex & 1) != 0;
            renderLink(poseStack, builder, chainSprite, packedLight, packedOverlay, flipped, LINK_HEIGHT);
            poseStack.popPose();
            linkIndex++;
        }

        // ---- Anchor head resting at bottom of the chain ---------------------
        // Rendered unconditionally whenever enough chain is deployed, regardless
        // of whether the state is MOORED, DEPLOYING, or WEIGHING.
        if (length > 0.25f) {
            renderAnchorFluke(poseStack, builder, ironSprite, packedLight, packedOverlay, length);
        }

        poseStack.popPose();
    }

    /**
     * Renders the anchor fluke (shank and crossbars).
     * The anchor mesh geometry extends BAR_T (0.05f) below the fluke origin.
     * Translating by {@code -length + BAR_T} ensures the bottom face of the crossbar
     * sits exactly flush on the top surface of the seabed block instead of clipping into it.
     */
    private static void renderAnchorFluke(PoseStack poseStack, VertexConsumer builder, TextureAtlasSprite ironSprite,
                                          int packedLight, int packedOverlay, float length) {
        poseStack.pushPose();
        // Anchor tip at the lowest paid-out link — offset by +BAR_T so crossbars sit flush on seabed surface.
        poseStack.translate(0.0, -length + BAR_T, 0.0);
        // Central vertical iron shank running up the chain's tail.
        box(poseStack.last(), builder, ironSprite, packedLight, packedOverlay,
                -BAR_T, 0.0f, -BAR_T, BAR_T, 0.5f, BAR_T);
        // Horizontal fluke crossbar along X.
        box(poseStack.last(), builder, ironSprite, packedLight, packedOverlay,
                -FLUKE_HALF_W, -BAR_T, -BAR_T, FLUKE_HALF_W, 0.0f, BAR_T);
        // Perpendicular fluke crossbar along Z.
        box(poseStack.last(), builder, ironSprite, packedLight, packedOverlay,
                -BAR_T, -BAR_T, -FLUKE_HALF_W, BAR_T, 0.0f, FLUKE_HALF_W);
        poseStack.popPose();
    }

    private static void renderLink(PoseStack poseStack, VertexConsumer buffer, TextureAtlasSprite sprite,
                                   int packedLight, int packedOverlay, boolean flipped, float linkHeight) {
        crossedQuads(poseStack.last(), buffer, sprite, packedLight, packedOverlay,
                0.0f, -linkHeight, LINK_HALF_W, flipped);
    }

    /**
     * Two vertical diagonal quads crossing in an X (viewed from above),
     * rotated 90 degrees around Y when {@code flipped}.
     */
    private static void crossedQuads(PoseStack.Pose pose, VertexConsumer vc,
                                     TextureAtlasSprite sprite, int light, int overlay,
                                     float yTop, float yBot, float halfW, boolean flipped) {
        if (!flipped) {
            // Diagonal plane A: (-h, z=-h) -> (+h, z=+h)
            quad(pose, vc, sprite, light, overlay,
                    -halfW, yBot, -halfW, halfW, yBot, halfW,
                    halfW, yTop, halfW, -halfW, yTop, -halfW);
            // Diagonal plane B: (-h, z=+h) -> (+h, z=-h)
            quad(pose, vc, sprite, light, overlay,
                    -halfW, yBot, halfW, halfW, yBot, -halfW,
                    halfW, yTop, -halfW, -halfW, yTop, halfW);
        } else {
            // Axis-aligned plane A: along X (z=0)
            quad(pose, vc, sprite, light, overlay,
                    -halfW, yBot, 0.0f, halfW, yBot, 0.0f,
                    halfW, yTop, 0.0f, -halfW, yTop, 0.0f);
            // Axis-aligned plane B: along Z (x=0)
            quad(pose, vc, sprite, light, overlay,
                    0.0f, yBot, -halfW, 0.0f, yBot, halfW,
                    0.0f, yTop, halfW, 0.0f, yTop, -halfW);
        }
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
