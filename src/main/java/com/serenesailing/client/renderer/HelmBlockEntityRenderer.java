package com.serenesailing.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.serenesailing.SereneSailing;
import com.serenesailing.block.HelmBlock;
import com.serenesailing.entity.HelmBlockEntity;
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
import net.minecraft.util.Mth;

/**
 * Dynamic 8-spoke ship's wheel renderer. Renders a static pedestal base
 * (from the block's JSON model) and a dynamically rotating wheel composed
 * of a central hub cylinder, an outer ring, 8 evenly spaced spokes, and
 * 8 handle pegs at the spoke tips.
 *
 * <p>The wheel rotates along its axle (Z+ axis in local space, which faces
 * the helmsman) based on {@link HelmBlockEntity#getVisualWheelAngle()}.</p>
 */
public class HelmBlockEntityRenderer implements BlockEntityRenderer<HelmBlockEntity> {

    /** Atlas material for the wheel's wood texture. */
    private static final Material WHEEL_MATERIAL = new Material(
            TextureAtlas.LOCATION_BLOCKS,
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "block/helm"));

    // ---- Wheel geometry constants (in block-local units) --------------------

    /** Outer ring radius from hub center. */
    private static final float RING_RADIUS = 0.38f;
    /** Thickness of the ring torus cross-section (square profile). */
    private static final float RING_THICK = 0.03f;
    /** Depth (Z extent) of the ring. */
    private static final float RING_DEPTH = 0.04f;
    /** Number of straight segments approximating the ring circle. */
    private static final int RING_SEGMENTS = 32;

    /** Hub cylinder radius. */
    private static final float HUB_RADIUS = 0.06f;
    /** Hub cylinder half-depth (Z extent each side of center). */
    private static final float HUB_DEPTH = 0.04f;
    /** Number of segments for the hub cylinder. */
    private static final int HUB_SEGMENTS = 12;

    /** Spoke cross-section half-width. */
    private static final float SPOKE_HALF_W = 0.02f;
    /** Spoke cross-section half-depth. */
    private static final float SPOKE_HALF_D = 0.02f;

    /** Handle peg radius. */
    private static final float HANDLE_RADIUS = 0.025f;
    /** Handle peg length (extending outward past the ring). */
    private static final float HANDLE_LENGTH = 0.08f;

    /** Number of spokes (evenly distributed). */
    private static final int NUM_SPOKES = 8;

    public HelmBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(HelmBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int light, int overlay) {

        poseStack.pushPose();

        // 1. Move to block center pivot
        poseStack.translate(0.5, 0.0, 0.5);

        // 2. Rotate to match block facing direction
        Direction facing = blockEntity.getBlockState().hasProperty(HelmBlock.FACING)
                ? blockEntity.getBlockState().getValue(HelmBlock.FACING)
                : Direction.NORTH;
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));

        // 3. Move along the aligned local axes to the axle front face:
        // X = 0 (centered), Y = 0.72 (axle height), Z = -0.46 (protruding slightly from front axle housing)
        poseStack.translate(0.0, 0.72, -0.46);

        // 4. Spin the wheel around its axle (local Z axis)
        float renderAngle = blockEntity.getCurrentRudderAngle() * 4.0f; // 4:1 visual ratio
        poseStack.mulPose(Axis.ZP.rotationDegrees(-renderAngle));

        // 5. Render wheel components (hub, spokes, ring, handles) centered at (0, 0, 0)
        renderWheelMesh(poseStack, bufferSource, light, overlay);

        poseStack.popPose();
    }

    private void renderWheelMesh(PoseStack poseStack, MultiBufferSource bufferSource, int combinedLight, int combinedOverlay) {
        // --- Resolve texture and lighting ---
        VertexConsumer consumer = WHEEL_MATERIAL.buffer(bufferSource, RenderType::entityCutoutNoCull);
        TextureAtlasSprite sprite = WHEEL_MATERIAL.sprite();
        int packedLight = combinedLight;
        int packedOverlay = combinedOverlay != 0 ? combinedOverlay : OverlayTexture.NO_OVERLAY;

        PoseStack.Pose pose = poseStack.last();

        // --- Render hub cylinder ---
        renderCylinder(pose, consumer, sprite, packedLight, packedOverlay,
                HUB_RADIUS, -HUB_DEPTH, HUB_DEPTH, HUB_SEGMENTS);

        // --- Render outer ring (torus approximation with square cross-section) ---
        renderRing(pose, consumer, sprite, packedLight, packedOverlay);

        // --- Render 8 spokes + handle pegs ---
        for (int i = 0; i < NUM_SPOKES; i++) {
            float angleDeg = i * (360.0f / NUM_SPOKES);
            float angleRad = (float) Math.toRadians(angleDeg);
            float cos = Mth.cos(angleRad);
            float sin = Mth.sin(angleRad);

            renderSpoke(pose, consumer, sprite, packedLight, packedOverlay, cos, sin);
            renderHandle(pose, consumer, sprite, packedLight, packedOverlay, cos, sin, angleDeg);
        }
    }

    // ---- Ring ---------------------------------------------------------------

    /**
     * Renders the outer ring as a series of box-profile segments around a circle.
     */
    private static void renderRing(PoseStack.Pose pose, VertexConsumer vc,
                                   TextureAtlasSprite sprite, int light, int overlay) {
        float innerR = RING_RADIUS - RING_THICK;
        float outerR = RING_RADIUS + RING_THICK;
        float halfD = RING_DEPTH;

        for (int i = 0; i < RING_SEGMENTS; i++) {
            float a0 = (float) (i * 2.0 * Math.PI / RING_SEGMENTS);
            float a1 = (float) ((i + 1) * 2.0 * Math.PI / RING_SEGMENTS);
            float cos0 = Mth.cos(a0), sin0 = Mth.sin(a0);
            float cos1 = Mth.cos(a1), sin1 = Mth.sin(a1);

            // Inner edge coords
            float ix0 = innerR * cos0, iy0 = innerR * sin0;
            float ix1 = innerR * cos1, iy1 = innerR * sin1;
            // Outer edge coords
            float ox0 = outerR * cos0, oy0 = outerR * sin0;
            float ox1 = outerR * cos1, oy1 = outerR * sin1;

            // Front face (+Z)
            quad(pose, vc, sprite, light, overlay,
                    ix0, iy0, halfD, ox0, oy0, halfD,
                    ox1, oy1, halfD, ix1, iy1, halfD);
            // Back face (-Z)
            quad(pose, vc, sprite, light, overlay,
                    ix1, iy1, -halfD, ox1, oy1, -halfD,
                    ox0, oy0, -halfD, ix0, iy0, -halfD);
            // Outer rim
            quad(pose, vc, sprite, light, overlay,
                    ox0, oy0, halfD, ox0, oy0, -halfD,
                    ox1, oy1, -halfD, ox1, oy1, halfD);
            // Inner rim
            quad(pose, vc, sprite, light, overlay,
                    ix1, iy1, halfD, ix1, iy1, -halfD,
                    ix0, iy0, -halfD, ix0, iy0, halfD);
        }
    }

    // ---- Spokes -------------------------------------------------------------

    /**
     * Renders a single spoke from the hub to the inner edge of the ring.
     */
    private static void renderSpoke(PoseStack.Pose pose, VertexConsumer vc,
                                    TextureAtlasSprite sprite, int light, int overlay,
                                    float cos, float sin) {
        float innerR = RING_RADIUS - RING_THICK;

        // Spoke runs from hub edge to ring inner edge along the radial direction.
        // We build a box aligned along the spoke's radial direction.
        // The spoke's local X axis is (cos, sin) and Y axis is (-sin, cos).
        float hw = SPOKE_HALF_W; // half-width perpendicular to spoke
        float hd = SPOKE_HALF_D; // half-depth along Z

        // Start and end radii
        float r0 = HUB_RADIUS;
        float r1 = innerR;

        // 4 corners at near end (r0) and 4 at far end (r1)
        // Perpendicular offset: (-sin * hw, cos * hw)
        float px = -sin * hw, py = cos * hw;

        float x0c = cos * r0, y0c = sin * r0; // center near
        float x1c = cos * r1, y1c = sin * r1; // center far

        // Near quad corners
        float n0x = x0c + px, n0y = y0c + py;
        float n1x = x0c - px, n1y = y0c - py;
        // Far quad corners
        float f0x = x1c + px, f0y = y1c + py;
        float f1x = x1c - px, f1y = y1c - py;

        // Top face (+Z)
        quad(pose, vc, sprite, light, overlay,
                n0x, n0y, hd, f0x, f0y, hd,
                f1x, f1y, hd, n1x, n1y, hd);
        // Bottom face (-Z)
        quad(pose, vc, sprite, light, overlay,
                n1x, n1y, -hd, f1x, f1y, -hd,
                f0x, f0y, -hd, n0x, n0y, -hd);
        // Side face (positive perpendicular)
        quad(pose, vc, sprite, light, overlay,
                n0x, n0y, hd, n0x, n0y, -hd,
                f0x, f0y, -hd, f0x, f0y, hd);
        // Side face (negative perpendicular)
        quad(pose, vc, sprite, light, overlay,
                f1x, f1y, hd, f1x, f1y, -hd,
                n1x, n1y, -hd, n1x, n1y, hd);
        // End caps
        quad(pose, vc, sprite, light, overlay,
                n0x, n0y, hd, n1x, n1y, hd,
                n1x, n1y, -hd, n0x, n0y, -hd);
        quad(pose, vc, sprite, light, overlay,
                f0x, f0y, -hd, f1x, f1y, -hd,
                f1x, f1y, hd, f0x, f0y, hd);
    }

    // ---- Handle pegs --------------------------------------------------------

    /**
     * Renders a handle peg at the tip of a spoke, extending outward past the ring.
     */
    private static void renderHandle(PoseStack.Pose pose, VertexConsumer vc,
                                     TextureAtlasSprite sprite, int light, int overlay,
                                     float cos, float sin, float angleDeg) {
        float startR = RING_RADIUS + RING_THICK;
        float endR = startR + HANDLE_LENGTH;

        // Center of the handle's base and tip
        float bx = cos * startR, by = sin * startR;
        float tx = cos * endR, ty = sin * endR;

        // Build a small cylinder along the radial axis using HANDLE_SEGMENTS
        float halfD = RING_DEPTH + 0.01f; // slightly deeper than ring

        // Simplified as a box for clean geometry
        float hw = HANDLE_RADIUS;
        float hd = HANDLE_RADIUS;
        float px = -sin * hw, py = cos * hw;

        // Near corners (at ring surface)
        float n0x = bx + px, n0y = by + py;
        float n1x = bx - px, n1y = by - py;
        // Far corners (peg tip)
        float f0x = tx + px, f0y = ty + py;
        float f1x = tx - px, f1y = ty - py;

        // Front face
        quad(pose, vc, sprite, light, overlay,
                n0x, n0y, hd, f0x, f0y, hd,
                f1x, f1y, hd, n1x, n1y, hd);
        // Back face
        quad(pose, vc, sprite, light, overlay,
                n1x, n1y, -hd, f1x, f1y, -hd,
                f0x, f0y, -hd, n0x, n0y, -hd);
        // Side +
        quad(pose, vc, sprite, light, overlay,
                n0x, n0y, hd, n0x, n0y, -hd,
                f0x, f0y, -hd, f0x, f0y, hd);
        // Side -
        quad(pose, vc, sprite, light, overlay,
                f1x, f1y, hd, f1x, f1y, -hd,
                n1x, n1y, -hd, n1x, n1y, hd);
        // Tip cap
        quad(pose, vc, sprite, light, overlay,
                f0x, f0y, -hd, f1x, f1y, -hd,
                f1x, f1y, hd, f0x, f0y, hd);
    }

    // ---- Hub cylinder -------------------------------------------------------

    /**
     * Renders a flat cylinder (hub) centered at the origin.
     */
    private static void renderCylinder(PoseStack.Pose pose, VertexConsumer vc,
                                       TextureAtlasSprite sprite, int light, int overlay,
                                       float radius, float z0, float z1, int segments) {
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (i * 2.0 * Math.PI / segments);
            float a1 = (float) ((i + 1) * 2.0 * Math.PI / segments);
            float cos0 = Mth.cos(a0), sin0 = Mth.sin(a0);
            float cos1 = Mth.cos(a1), sin1 = Mth.sin(a1);

            float x0 = radius * cos0, y0 = radius * sin0;
            float x1 = radius * cos1, y1 = radius * sin1;

            // Barrel face
            quad(pose, vc, sprite, light, overlay,
                    x0, y0, z1, x0, y0, z0,
                    x1, y1, z0, x1, y1, z1);

            // Front cap triangle (as degenerate quad with center)
            quad(pose, vc, sprite, light, overlay,
                    0, 0, z1, x0, y0, z1,
                    x1, y1, z1, 0, 0, z1);

            // Back cap triangle
            quad(pose, vc, sprite, light, overlay,
                    0, 0, z0, x1, y1, z0,
                    x0, y0, z0, 0, 0, z0);
        }
    }

    // ---- Primitive helpers ---------------------------------------------------

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
                               float x, float y, float z,
                               float uFrac, float vFrac) {
        float u = sprite.getU0() + (sprite.getU1() - sprite.getU0()) * uFrac;
        float v = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * vFrac;
        vc.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
    }
}
