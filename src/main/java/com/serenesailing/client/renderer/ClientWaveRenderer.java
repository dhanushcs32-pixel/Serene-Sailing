package com.serenesailing.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.serenesailing.physics.waves.GerstnerWaveEngine;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Client-side visual representation of Gerstner ocean waves using an isolated,
 * self-contained {@link Tesselator} pass during {@link RenderLevelStageEvent.Stage#AFTER_PARTICLES}.
 *
 * <p>Key architectural guarantees:
 * <ul>
 *   <li>Completely independent Tesselator draw call: never touches MultiBufferSource,
 *       preserving 100% stable chunk loading and compatibility with Veil and Flywheel.</li>
 *   <li>Safe water surface cache with strict chunk-presence checks (hasChunkAt) preventing
 *       unloaded chunk loading.</li>
 *   <li>Seamless 3D polygonal mesh: adjacent quads share exact corner vertex heights,
 *       with backface culling disabled so waves never render as thin streak lines.</li>
 *   <li>Dynamic color shading blending from deep ocean troughs (A=140) to foamy crest highlights (A=200).</li>
 * </ul>
 */
public class ClientWaveRenderer {

    private static final double SEA_LEVEL_WATER_SURFACE = 62.88;
    private static final float BASE_WATER_Y = 62.90f;
    private static final int GRID_RADIUS = 32;
    private static final int GRID_SIZE = 65; // 2 * GRID_RADIUS + 1

    private static final boolean[][] IS_WATER_SURFACE = new boolean[GRID_SIZE][GRID_SIZE];
    private static int cachedCenterX = Integer.MIN_VALUE;
    private static int cachedCenterZ = Integer.MIN_VALUE;
    private static long lastCacheUpdateTick = -1L;

    /**
     * Updates the local water column presence cache without ever touching unloaded chunks.
     */
    private static void updateWaterCache(Level level, double camX, double camZ, long gameTime) {
        int camBlockX = Mth.floor(camX);
        int camBlockZ = Mth.floor(camZ);

        if (gameTime - lastCacheUpdateTick < 10L
                && Math.abs(camBlockX - cachedCenterX) < 4
                && Math.abs(camBlockZ - cachedCenterZ) < 4) {
            return;
        }

        lastCacheUpdateTick = gameTime;
        cachedCenterX = camBlockX;
        cachedCenterZ = camBlockZ;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int gx = 0; gx < GRID_SIZE; gx++) {
            int wx = camBlockX - GRID_RADIUS + gx;
            for (int gz = 0; gz < GRID_SIZE; gz++) {
                int wz = camBlockZ - GRID_RADIUS + gz;
                probe.set(wx, 62, wz);
                if (!level.hasChunkAt(probe)) {
                    IS_WATER_SURFACE[gx][gz] = false;
                    continue;
                }
                if (!level.getFluidState(probe).is(FluidTags.WATER)) {
                    IS_WATER_SURFACE[gx][gz] = false;
                    continue;
                }
                probe.set(wx, 63, wz);
                BlockState aboveState = level.getBlockState(probe);
                boolean isAirOrSurface = aboveState.isAir() || !level.getFluidState(probe).is(FluidTags.WATER);
                IS_WATER_SURFACE[gx][gz] = isAirOrSurface;
            }
        }
    }

    /**
     * Calculates smooth ARGB vertex color based on wave elevation h and distance from camera.
     */
    private static int computeWaveColor(float h, double worldX, double worldZ, double camX, double camZ) {
        double distFromCam = Math.hypot(worldX - camX, worldZ - camZ);
        float distFade = (float) Mth.clamp((GRID_RADIUS - distFromCam) / 6.0, 0.0, 1.0);
        if (distFade <= 0.0f) {
            return 0;
        }

        // Troughs: deep ocean blue-teal (R=22, G=85, B=145, A=140)
        // Mid wave slope: rich ocean cyan-blue (R=45, G=125, B=185, A=165)
        // High wave crests (h > 0.35f): foamy white-blue crest highlight (R=215, G=235, B=250, A=200)
        int r, g, b, a;
        if (h < 0.20f) {
            float t = Mth.clamp((h + 0.15f) / 0.35f, 0.0f, 1.0f);
            r = (int) Mth.lerp(t, 22, 45);
            g = (int) Mth.lerp(t, 85, 125);
            b = (int) Mth.lerp(t, 145, 185);
            a = (int) (Mth.lerp(t, 140, 165) * distFade);
        } else {
            float t = Mth.clamp((h - 0.20f) / 0.30f, 0.0f, 1.0f);
            r = (int) Mth.lerp(t, 45, 215);
            g = (int) Mth.lerp(t, 125, 235);
            b = (int) Mth.lerp(t, 185, 250);
            a = (int) (Mth.lerp(t, 165, 200) * distFade);
        }
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /**
     * Renders connected 3D polygonal Gerstner wave quads using an isolated Tesselator pass.
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        Level level = mc.level;
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        if (camPos.y < 45.0 || camPos.y > 110.0) {
            return;
        }

        GerstnerWaveEngine.DimensionWaveState waveState = GerstnerWaveEngine.getDimensionState(level);
        double stormIntensity = waveState.smoothedStormIntensity;
        if (stormIntensity < 0.04) {
            return;
        }

        long gameTime = level.getGameTime();
        updateWaterCache(level, camPos.x, camPos.z, gameTime);

        int startX = cachedCenterX - GRID_RADIUS;
        int startZ = cachedCenterZ - GRID_RADIUS;

        double windDirX = waveState.smoothedWindDirX;
        double windDirZ = waveState.smoothedWindDirZ;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double renderTime = (gameTime + partialTick) * 0.05;

        // Precompute exact shared vertex heights across the grid (seamless polygonal mesh)
        float[][] waveOffset = new float[GRID_SIZE + 1][GRID_SIZE + 1];
        for (int vi = 0; vi <= GRID_SIZE; vi++) {
            double vx = startX + vi;
            for (int vj = 0; vj <= GRID_SIZE; vj++) {
                double vz = startZ + vj;
                waveOffset[vi][vj] = (float) GerstnerWaveEngine.evaluateWaveOffset(
                        vx, vz, renderTime, stormIntensity, windDirX, windDirZ);
            }
        }

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull(); // Prevents backface culling from turning waves into thin streak lines
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (int gx = 0; gx < GRID_SIZE; gx++) {
            for (int gz = 0; gz < GRID_SIZE; gz++) {
                if (!IS_WATER_SURFACE[gx][gz]) {
                    continue;
                }

                float h00 = waveOffset[gx][gz];
                float h01 = waveOffset[gx][gz + 1];
                float h11 = waveOffset[gx + 1][gz + 1];
                float h10 = waveOffset[gx + 1][gz];

                double wx0 = startX + gx;
                double wx1 = wx0 + 1.0;
                double wz0 = startZ + gz;
                double wz1 = wz0 + 1.0;

                float y00 = BASE_WATER_Y + h00;
                float y01 = BASE_WATER_Y + h01;
                float y11 = BASE_WATER_Y + h11;
                float y10 = BASE_WATER_Y + h10;

                int c00 = computeWaveColor(h00, wx0, wz0, camPos.x, camPos.z);
                int c01 = computeWaveColor(h01, wx0, wz1, camPos.x, camPos.z);
                int c11 = computeWaveColor(h11, wx1, wz1, camPos.x, camPos.z);
                int c10 = computeWaveColor(h10, wx1, wz0, camPos.x, camPos.z);

                if (((c00 | c01 | c11 | c10) >>> 24) == 0) {
                    continue;
                }

                bufferBuilder.addVertex(matrix, (float)(wx0 - camPos.x), (float)(y00 - camPos.y), (float)(wz0 - camPos.z)).setColor(c00);
                bufferBuilder.addVertex(matrix, (float)(wx0 - camPos.x), (float)(y01 - camPos.y), (float)(wz1 - camPos.z)).setColor(c01);
                bufferBuilder.addVertex(matrix, (float)(wx1 - camPos.x), (float)(y11 - camPos.y), (float)(wz1 - camPos.z)).setColor(c11);
                bufferBuilder.addVertex(matrix, (float)(wx1 - camPos.x), (float)(y10 - camPos.y), (float)(wz0 - camPos.z)).setColor(c10);
            }
        }

        MeshData meshData = bufferBuilder.build();
        if (meshData != null) {
            BufferUploader.drawWithShader(meshData);
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused() || mc.level == null || mc.player == null) {
            return;
        }

        Level level = mc.level;
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }

        Vec3 playerPos = mc.player.position();
        if (playerPos.y < 45.0 || playerPos.y > 95.0) {
            return;
        }

        long gameTime = level.getGameTime();
        double timeSeconds = gameTime * 0.05;

        GerstnerWaveEngine.DimensionWaveState waveState = GerstnerWaveEngine.getDimensionState(level);
        waveState.update(level, gameTime);

        double stormIntensity = waveState.smoothedStormIntensity;
        // In calm/mild weather (stormIntensity <= 0.35), spawn zero particles and do no extra work
        if (stormIntensity <= 0.35) {
            return;
        }

        double windDirX = waveState.smoothedWindDirX;
        double windDirZ = waveState.smoothedWindDirZ;
        RandomSource random = level.random;

        // 1. Environmental Ocean Whitecaps & Crest Foam in Active Storms (stormIntensity > 0.35)
        int sampleCount = stormIntensity > 0.6 ? 30 : 15;
        for (int i = 0; i < sampleCount; i++) {
            double angle = random.nextDouble() * (2.0 * Math.PI);
            double radius = 4.0 + random.nextDouble() * 32.0;
            double sampleX = playerPos.x + Math.cos(angle) * radius;
            double sampleZ = playerPos.z + Math.sin(angle) * radius;

            BlockPos probePos = BlockPos.containing(sampleX, 62, sampleZ);
            if (!level.hasChunkAt(probePos) || !level.getFluidState(probePos).is(FluidTags.WATER)) {
                continue;
            }

            double waveOffset = GerstnerWaveEngine.evaluateWaveOffset(
                    sampleX, sampleZ, timeSeconds, stormIntensity, windDirX, windDirZ);

            if (waveOffset > 0.35) {
                double crestY = SEA_LEVEL_WATER_SURFACE + waveOffset;
                double driftSpeed = 0.08 + stormIntensity * 0.15;
                double vx = windDirX * driftSpeed + (random.nextDouble() - 0.5) * 0.06;
                double vy = 0.04 + random.nextDouble() * 0.06;
                double vz = windDirZ * driftSpeed + (random.nextDouble() - 0.5) * 0.06;

                level.addParticle(ParticleTypes.SPLASH, sampleX, crestY, sampleZ, vx, vy, vz);

                if (random.nextFloat() < 0.35f) {
                    level.addParticle(ParticleTypes.BUBBLE_POP, sampleX, crestY + 0.02, sampleZ,
                            vx * 0.5, 0.02, vz * 0.5);
                }

                if (stormIntensity > 0.6 && random.nextFloat() < 0.2f) {
                    level.addParticle(ParticleTypes.CLOUD, sampleX, crestY + 0.1, sampleZ,
                            windDirX * 0.25, 0.08, windDirZ * 0.25);
                }
            }
        }

        // 2. Dynamic Ship Hull & Bow Wave Splashes in Storm Waves
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container instanceof ClientSubLevelContainer clientContainer)) {
            return;
        }

        for (ClientSubLevel subLevel : clientContainer.getAllSubLevels()) {
            var bounds = subLevel.boundingBox();
            if (bounds == null) {
                continue;
            }

            double distSq = playerPos.distanceToSqr(
                    (bounds.minX() + bounds.maxX()) * 0.5,
                    (bounds.minY() + bounds.maxY()) * 0.5,
                    (bounds.minZ() + bounds.maxZ()) * 0.5
            );
            if (distSq > 50.0 * 50.0) {
                continue;
            }

            Pose3dc pose = subLevel.logicalPose();
            Vector3dc shipPos = pose.position();
            Quaterniondc orientation = pose.orientation();

            Vector3d forwardWorld = new Vector3d(0.0, 0.0, -1.0).rotate(orientation);
            forwardWorld.y = 0.0;
            if (forwardWorld.lengthSquared() < 1.0e-4) {
                continue;
            }
            forwardWorld.normalize();

            Vector3d starboardWorld = new Vector3d(forwardWorld).cross(0.0, 1.0, 0.0).normalize();

            double hullLength = bounds.maxX() - bounds.minX();
            double hullBeam = bounds.maxZ() - bounds.minZ();
            double halfLength = Math.max(hullLength, hullBeam) * 0.45;
            double halfBeam = Math.min(hullLength, hullBeam) * 0.35;

            Vector3d bowPos = new Vector3d(shipPos.x(), SEA_LEVEL_WATER_SURFACE, shipPos.z())
                    .add(new Vector3d(forwardWorld).mul(halfLength));

            double bowWaveOffset = GerstnerWaveEngine.evaluateWaveOffset(
                    bowPos.x, bowPos.z, timeSeconds, stormIntensity, windDirX, windDirZ);

            if (bowWaveOffset > 0.25 || stormIntensity > 0.45) {
                double sprayY = SEA_LEVEL_WATER_SURFACE + Math.max(0.0, bowWaveOffset);

                for (int s = -1; s <= 1; s += 2) {
                    Vector3d shoulder = new Vector3d(bowPos)
                            .add(new Vector3d(starboardWorld).mul(s * halfBeam * 0.6))
                            .sub(new Vector3d(forwardWorld).mul(1.5));

                    double sprayVx = s * starboardWorld.x * 0.10 + forwardWorld.x * 0.04 + (random.nextDouble() - 0.5) * 0.04;
                    double sprayVy = 0.08 + random.nextDouble() * 0.10;
                    double sprayVz = s * starboardWorld.z * 0.10 + forwardWorld.z * 0.04 + (random.nextDouble() - 0.5) * 0.04;

                    level.addParticle(ParticleTypes.SPLASH, shoulder.x, sprayY, shoulder.z,
                            sprayVx, sprayVy, sprayVz);
                    if (stormIntensity > 0.6 && random.nextFloat() < 0.25f) {
                        level.addParticle(ParticleTypes.CLOUD, shoulder.x, sprayY + 0.1, shoulder.z,
                                sprayVx * 1.5, sprayVy * 0.8, sprayVz * 1.5);
                    }
                }
            }
        }
    }
}
