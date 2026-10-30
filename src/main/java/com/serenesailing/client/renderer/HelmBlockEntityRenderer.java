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
 * Ornate 8-spoke Age-of-Sail captain's wheel renderer with realistic directional per-face shading.
 *
 * <p>Renders a dynamically rotating wheel mounted to the carved binnacle pedestal axle.
 * The wheel features:
 * <ul>
 *   <li>Central brass boss / hub with forward dome cap and specular highlight</li>
 *   <li>8 radiating turned spokes (4 cardinal + 4 diagonal) in lighter birch/spruce tone</li>
 *   <li>Inner concentric brass strengthening ring</li>
 *   <li>Octagonal segmented outer rich oak rim with chamfered bevels</li>
 *   <li>8 protruding turned wooden grip handles (belaying spindles)</li>
 *   <li>Distinct brass tip on the top King Spoke for visual rudder-centering</li>
 * </ul>
 * </p>
 */
public class HelmBlockEntityRenderer implements BlockEntityRenderer<HelmBlockEntity> {

    /** Atlas material for the wheel's wood texture. */
    private static final Material WHEEL_MATERIAL = new Material(
            TextureAtlas.LOCATION_BLOCKS,
            ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, "block/helm"));

    // ---- Wheel geometry constants (in block-local units) --------------------

    /** Outer ring radius from hub center (~6.1 px). */
    private static final float RING_RADIUS = 0.385f;
    /** Thickness of the ring torus cross-section. */
    private static final float RING_THICK = 0.032f;
    /** Depth (Z extent) of the ring. */
    private static final float RING_DEPTH = 0.035f;
    /** Number of straight segments approximating the ring circle. */
    private static final int RING_SEGMENTS = 32;

    /** Inner concentric brass strengthening ring radius (~3.5 px). */
    private static final float INNER_RING_RADIUS = 0.22f;
    private static final float INNER_RING_THICK = 0.014f;
    private static final float INNER_RING_DEPTH = 0.022f;

    /** Hub cylinder radius. */
    private static final float HUB_RADIUS = 0.082f;
    /** Hub cylinder half-depth (Z extent each side of center). */
    private static final float HUB_DEPTH = 0.038f;
    /** Hub center brass boss cap radius. */
    private static final float BOSS_RADIUS = 0.046f;
    /** Number of segments for the hub cylinder. */
    private static final int HUB_SEGMENTS = 16;

    /** Spoke cross-section half-width. */
    private static final float SPOKE_HALF_W = 0.018f;
    /** Spoke cross-section half-depth. */
    private static final float SPOKE_HALF_D = 0.022f;

    /** Handle peg radius. */
    private static final float HANDLE_RADIUS = 0.019f;
    /** Handle peg length extending 2.0px radially outward past the outer rim. */
    private static final float HANDLE_LENGTH = 0.125f;

    /** Number of spokes (evenly distributed: 4 cardinal + 4 diagonal). */
    private static final int NUM_SPOKES = 8;

    // 17th-century timber wheel palette: spruce outer rim, oak spokes, small brass hub
    private static final int RIM_BASE_R = 105, RIM_BASE_G = 76, RIM_BASE_B = 48;
    private static final int RIM_FRONT_R = 125, RIM_FRONT_G = 92, RIM_FRONT_B = 60;
    private static final int SPOKE_R = 175, SPOKE_G = 135, SPOKE_B = 85;
    private static final int BRASS_R = 215, BRASS_G = 175, BRASS_B = 65;
    private static final int BRASS_SPECULAR_R = 240, BRASS_SPECULAR_G = 205, BRASS_SPECULAR_B = 95;
    private static final int HANDLE_R = 150, HANDLE_G = 110, HANDLE_B = 68;

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
        // X = 0 (centered), Y = 13.0/16.0 (axle journal center), Z = -0.3625 (front face of pedestal axle)
        poseStack.translate(0.0, 13.0 / 16.0, -0.3625);

        // 4. Spin the wheel around its axle (local Z axis)
        float renderAngle = blockEntity.getCurrentRudderAngle() * 4.0f; // 4:1 visual ratio
        poseStack.mulPose(Axis.ZP.rotationDegrees(-renderAngle));

        // 5. Render wheel components (hub, boss, inner ring, outer ring, spokes, handles)
        renderWheelMesh(poseStack, bufferSource, light, overlay);

        poseStack.popPose();
    }

    private void renderWheelMesh(PoseStack poseStack, MultiBufferSource bufferSource, int combinedLight, int combinedOverlay) {
        VertexConsumer consumer = WHEEL_MATERIAL.buffer(bufferSource, RenderType::entityCutoutNoCull);
        TextureAtlasSprite sprite = WHEEL_MATERIAL.sprite();
        int packedLight = combinedLight;
        int packedOverlay = combinedOverlay != 0 ? combinedOverlay : OverlayTexture.NO_OVERLAY;

        PoseStack.Pose pose = poseStack.last();

        // --- 1. Central Hub Cylinder ---
        renderCylinder(pose, consumer, sprite, packedLight, packedOverlay,
                RIM_BASE_R, RIM_BASE_G, RIM_BASE_B,
                RIM_FRONT_R, RIM_FRONT_G, RIM_FRONT_B,
                HUB_RADIUS, -HUB_DEPTH, HUB_DEPTH, HUB_SEGMENTS);

        // --- 2. Central Brass Boss Dome Cap ---
        renderCylinder(pose, consumer, sprite, packedLight, packedOverlay,
                BRASS_R, BRASS_G, BRASS_B,
                BRASS_SPECULAR_R, BRASS_SPECULAR_G, BRASS_SPECULAR_B,
                BOSS_RADIUS, HUB_DEPTH, HUB_DEPTH + 0.024f, 12);

        // --- 3. Inner Concentric Brass Strengthening Ring ---
        renderRing(pose, consumer, sprite, packedLight, packedOverlay,
                BRASS_SPECULAR_R, BRASS_SPECULAR_G, BRASS_SPECULAR_B,
                BRASS_R, BRASS_G, BRASS_B,
                INNER_RING_RADIUS, INNER_RING_THICK, INNER_RING_DEPTH, 24);

        // --- 4. Octagonal Outer Wooden Rim ---
        renderRing(pose, consumer, sprite, packedLight, packedOverlay,
                RIM_FRONT_R, RIM_FRONT_G, RIM_FRONT_B,
                RIM_BASE_R, RIM_BASE_G, RIM_BASE_B,
                RING_RADIUS, RING_THICK, RING_DEPTH, RING_SEGMENTS);

        // --- 5. 8 Radiating Turned Spokes & Grip Handles ---
        for (int i = 0; i < NUM_SPOKES; i++) {
            float angleDeg = i * (360.0f / NUM_SPOKES);
            float angleRad = (float) Math.toRadians(angleDeg);
            float cos = Mth.cos(angleRad);
            float sin = Mth.sin(angleRad);

            // Turned wooden spoke from hub to outer rim
            renderSpoke(pose, consumer, sprite, packedLight, packedOverlay, cos, sin);

            // Handle peg extending 2.0px radially outward past the outer rim
            // Spoke index 0 is the King Spoke: points straight up when centered, tipped with brass!
            boolean isKingSpoke = (i == 0);
            renderHandle(pose, consumer, sprite, packedLight, packedOverlay, cos, sin, isKingSpoke);
        }
    }

    // ---- Directional Per-Face Shading Helper --------------------------------

    private static float computeShading(float nx, float ny, float nz) {
        float yFactor = ny > 0.0f ? 1.0f : 0.55f;
        float zFactor = nz > 0.0f ? 0.95f : 0.70f;
        float xFactor = 0.68f;
        float shade = Math.abs(ny) * yFactor + Math.abs(nz) * zFactor + Math.abs(nx) * xFactor;
        return Mth.clamp(shade, 0.45f, 1.0f);
    }

    // ---- Rings --------------------------------------------------------------

    private static void renderRing(PoseStack.Pose pose, VertexConsumer vc,
                                   TextureAtlasSprite sprite, int light, int overlay,
                                   int frontR, int frontG, int frontB,
                                   int rimR, int rimG, int rimB,
                                   float centerR, float thick, float halfD, int segments) {
        float innerR = centerR - thick;
        float outerR = centerR + thick;

        for (int i = 0; i < segments; i++) {
            float a0 = (float) (i * 2.0 * Math.PI / segments);
            float a1 = (float) ((i + 1) * 2.0 * Math.PI / segments);
            float cos0 = Mth.cos(a0), sin0 = Mth.sin(a0);
            float cos1 = Mth.cos(a1), sin1 = Mth.sin(a1);

            float ix0 = innerR * cos0, iy0 = innerR * sin0;
            float ix1 = innerR * cos1, iy1 = innerR * sin1;
            float ox0 = outerR * cos0, oy0 = outerR * sin0;
            float ox1 = outerR * cos1, oy1 = outerR * sin1;

            float midA = 0.5f * (a0 + a1);
            float cosM = Mth.cos(midA), sinM = Mth.sin(midA);

            // Front face (+Z)
            quad(pose, vc, sprite, light, overlay, frontR, frontG, frontB, 255,
                    0.0f, 0.0f, 1.0f,
                    ix0, iy0, halfD, ox0, oy0, halfD,
                    ox1, oy1, halfD, ix1, iy1, halfD);
            // Back face (-Z)
            quad(pose, vc, sprite, light, overlay, rimR, rimG, rimB, 255,
                    0.0f, 0.0f, -1.0f,
                    ix1, iy1, -halfD, ox1, oy1, -halfD,
                    ox0, oy0, -halfD, ix0, iy0, -halfD);
            // Outer rim (normal points radially outwards)
            quad(pose, vc, sprite, light, overlay, rimR, rimG, rimB, 255,
                    cosM, sinM, 0.0f,
                    ox0, oy0, halfD, ox0, oy0, -halfD,
                    ox1, oy1, -halfD, ox1, oy1, halfD);
            // Inner rim (normal points radially inwards)
            quad(pose, vc, sprite, light, overlay, rimR, rimG, rimB, 255,
                    -cosM, -sinM, 0.0f,
                    ix1, iy1, halfD, ix1, iy1, -halfD,
                    ix0, iy0, -halfD, ix0, iy0, halfD);
        }
    }

    // ---- Spokes -------------------------------------------------------------

    private static void renderSpoke(PoseStack.Pose pose, VertexConsumer vc,
                                    TextureAtlasSprite sprite, int light, int overlay,
                                    float cos, float sin) {
        float innerR = RING_RADIUS - RING_THICK;

        float hw = SPOKE_HALF_W;
        float hd = SPOKE_HALF_D;

        float r0 = HUB_RADIUS;
        float r1 = innerR;

        float px = -sin * hw, py = cos * hw;

        float x0c = cos * r0, y0c = sin * r0;
        float x1c = cos * r1, y1c = sin * r1;

        float n0x = x0c + px, n0y = y0c + py;
        float n1x = x0c - px, n1y = y0c - py;
        float f0x = x1c + px, f0y = y1c + py;
        float f1x = x1c - px, f1y = y1c - py;

        // Front face (+Z)
        quad(pose, vc, sprite, light, overlay, SPOKE_R, SPOKE_G, SPOKE_B, 255,
                0.0f, 0.0f, 1.0f,
                n0x, n0y, hd, f0x, f0y, hd,
                f1x, f1y, hd, n1x, n1y, hd);
        // Back face (-Z)
        quad(pose, vc, sprite, light, overlay, SPOKE_R, SPOKE_G, SPOKE_B, 255,
                0.0f, 0.0f, -1.0f,
                n1x, n1y, -hd, f1x, f1y, -hd,
                f0x, f0y, -hd, n0x, n0y, -hd);
        // Side face (positive perpendicular)
        quad(pose, vc, sprite, light, overlay, SPOKE_R, SPOKE_G, SPOKE_B, 255,
                -sin, cos, 0.0f,
                n0x, n0y, hd, n0x, n0y, -hd,
                f0x, f0y, -hd, f0x, f0y, hd);
        // Side face (negative perpendicular)
        quad(pose, vc, sprite, light, overlay, SPOKE_R, SPOKE_G, SPOKE_B, 255,
                sin, -cos, 0.0f,
                f1x, f1y, hd, f1x, f1y, -hd,
                n1x, n1y, -hd, n1x, n1y, hd);
    }

    // ---- Handle pegs (Belaying Spindles) -------------------------------------

    private static void renderHandle(PoseStack.Pose pose, VertexConsumer vc,
                                     TextureAtlasSprite sprite, int light, int overlay,
                                     float cos, float sin, boolean isKingSpoke) {
        float startR = RING_RADIUS + RING_THICK;
        float endR = startR + HANDLE_LENGTH;

        float hw = HANDLE_RADIUS;
        float hd = HANDLE_RADIUS;
        float px = -sin * hw, py = cos * hw;

        if (!isKingSpoke) {
            // Standard turned wooden handle
            renderHandleSegment(pose, vc, sprite, light, overlay,
                    HANDLE_R, HANDLE_G, HANDLE_B,
                    cos, sin, px, py, hd, startR, endR, true);
        } else {
            // King Spoke: Wooden handle base with ornate brass tip
            float tipSplitR = startR + HANDLE_LENGTH * 0.65f;
            renderHandleSegment(pose, vc, sprite, light, overlay,
                    HANDLE_R, HANDLE_G, HANDLE_B,
                    cos, sin, px, py, hd, startR, tipSplitR, false);
            renderHandleSegment(pose, vc, sprite, light, overlay,
                    BRASS_R, BRASS_G, BRASS_B,
                    cos, sin, px, py, hd * 1.15f, tipSplitR, endR, true);
        }
    }

    private static void renderHandleSegment(PoseStack.Pose pose, VertexConsumer vc,
                                            TextureAtlasSprite sprite, int light, int overlay,
                                            int r, int g, int b,
                                            float cos, float sin, float px, float py, float hd,
                                            float r0, float r1, boolean drawCap) {
        float bx = cos * r0, by = sin * r0;
        float tx = cos * r1, ty = sin * r1;

        float n0x = bx + px, n0y = by + py;
        float n1x = bx - px, n1y = by - py;
        float f0x = tx + px, f0y = ty + py;
        float f1x = tx - px, f1y = ty - py;

        // Front face (+Z)
        quad(pose, vc, sprite, light, overlay, r, g, b, 255,
                0.0f, 0.0f, 1.0f,
                n0x, n0y, hd, f0x, f0y, hd,
                f1x, f1y, hd, n1x, n1y, hd);
        // Back face (-Z)
        quad(pose, vc, sprite, light, overlay, r, g, b, 255,
                0.0f, 0.0f, -1.0f,
                n1x, n1y, -hd, f1x, f1y, -hd,
                f0x, f0y, -hd, n0x, n0y, -hd);
        // Side +
        quad(pose, vc, sprite, light, overlay, r, g, b, 255,
                -sin, cos, 0.0f,
                n0x, n0y, hd, n0x, n0y, -hd,
                f0x, f0y, -hd, f0x, f0y, hd);
        // Side -
        quad(pose, vc, sprite, light, overlay, r, g, b, 255,
                sin, -cos, 0.0f,
                f1x, f1y, hd, f1x, f1y, -hd,
                n1x, n1y, -hd, n1x, n1y, hd);

        if (drawCap) {
            // Tip cap (faces along radial handle direction)
            quad(pose, vc, sprite, light, overlay, r, g, b, 255,
                    cos, sin, 0.0f,
                    f0x, f0y, -hd, f1x, f1y, -hd,
                    f1x, f1y, hd, f0x, f0y, hd);
        }
    }

    // ---- Cylinder Helper ----------------------------------------------------

    private static void renderCylinder(PoseStack.Pose pose, VertexConsumer vc,
                                       TextureAtlasSprite sprite, int light, int overlay,
                                       int barrelR, int barrelG, int barrelB,
                                       int capR, int capG, int capB,
                                       float radius, float z0, float z1, int segments) {
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (i * 2.0 * Math.PI / segments);
            float a1 = (float) ((i + 1) * 2.0 * Math.PI / segments);
            float cos0 = Mth.cos(a0), sin0 = Mth.sin(a0);
            float cos1 = Mth.cos(a1), sin1 = Mth.sin(a1);

            float x0 = radius * cos0, y0 = radius * sin0;
            float x1 = radius * cos1, y1 = radius * sin1;

            float midA = 0.5f * (a0 + a1);
            float cosM = Mth.cos(midA), sinM = Mth.sin(midA);

            // Barrel face (radial normal)
            quad(pose, vc, sprite, light, overlay, barrelR, barrelG, barrelB, 255,
                    cosM, sinM, 0.0f,
                    x0, y0, z1, x0, y0, z0,
                    x1, y1, z0, x1, y1, z1);

            // Front cap (+Z normal)
            quad(pose, vc, sprite, light, overlay, capR, capG, capB, 255,
                    0.0f, 0.0f, 1.0f,
                    0, 0, z1, x0, y0, z1,
                    x1, y1, z1, 0, 0, z1);

            // Back cap (-Z normal)
            quad(pose, vc, sprite, light, overlay, barrelR, barrelG, barrelB, 255,
                    0.0f, 0.0f, -1.0f,
                    0, 0, z0, x1, y1, z0,
                    x0, y0, z0, 0, 0, z0);
        }
    }

    // ---- Primitive helpers ---------------------------------------------------

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

        vertex(pose, vc, sprite, light, overlay, sr, sg, sb, a, nx, ny, nz, ax, ay, az, 1.0f, 1.0f);
        vertex(pose, vc, sprite, light, overlay, sr, sg, sb, a, nx, ny, nz, bx, by, bz, 1.0f, 0.0f);
        vertex(pose, vc, sprite, light, overlay, sr, sg, sb, a, nx, ny, nz, cx, cy, cz, 0.0f, 0.0f);
        vertex(pose, vc, sprite, light, overlay, sr, sg, sb, a, nx, ny, nz, dx, dy, dz, 0.0f, 1.0f);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, TextureAtlasSprite sprite,
                               int light, int overlay, int r, int g, int b, int a,
                               float nx, float ny, float nz,
                               float x, float y, float z,
                               float uFrac, float vFrac) {
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
