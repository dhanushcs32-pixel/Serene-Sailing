package com.serenesailing.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.serenesailing.physics.waves.GerstnerWaveEngine;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side visual representation and 3D surface mesh rendering of the Gerstner Ocean Wave Field.
 *
 * <p>Produces synchronized visual feedback matching the server-side wave hydrodynamics:
 * <ul>
 *   <li>3D displaced rolling wave surface mesh rendered on top of vanilla ocean water plane.</li>
 *   <li>Dynamic wave normal lighting and foamy white crest shading on elevated peaks.</li>
 *   <li>Animated whitecap foam and sea spray along wave crests during storms (S >= 0.15).</li>
 *   <li>Bow wave spray and windward hull foam curls when sailing vessels cut through swells.</li>
 *   <li>Zero particle spam and virtually still water in clear/calm weather (S < 0.15).</li>
 * </ul>
 *
 * <p>Registered to {@code NeoForge.EVENT_BUS} dynamically during {@code FMLClientSetupEvent}
 * in {@link com.serenesailing.client.ClientSetup} to prevent early classloading hazards during mod discovery.</p>
 */
public class ClientWaveRenderer {

    private static final double SEA_LEVEL_WATER_SURFACE = 62.88;
    private static final double GRID_STEP = 2.0;
    private static final int GRID_RADIUS = 36;

    // Cached open water column origins (x, z) to avoid per-frame block lookups
    private static final List<BlockPos> CACHED_WATER_COLUMNS = new ArrayList<>();
    private static long lastCacheUpdateTick = -1L;
    private static double lastCacheCamX = 0.0;
    private static double lastCacheCamZ = 0.0;

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
            return; // Player is far above or below sea level
        }

        long gameTime = level.getGameTime();
        double timeSeconds = gameTime * 0.05;

        GerstnerWaveEngine.DimensionWaveState waveState = GerstnerWaveEngine.getDimensionState(level);
        waveState.update(level, gameTime);

        double stormIntensity = waveState.smoothedStormIntensity;
        double windDirX = waveState.smoothedWindDirX;
        double windDirZ = waveState.smoothedWindDirZ;

        // 1. Update water column cache once every 10 ticks (or when camera moves significantly)
        double camDistSq = (playerPos.x - lastCacheCamX) * (playerPos.x - lastCacheCamX)
                + (playerPos.z - lastCacheCamZ) * (playerPos.z - lastCacheCamZ);
        if (gameTime - lastCacheUpdateTick >= 10L || camDistSq > 36.0) {
            lastCacheUpdateTick = gameTime;
            lastCacheCamX = playerPos.x;
            lastCacheCamZ = playerPos.z;
            updateWaterCache(level, playerPos.x, playerPos.z);
        }

        // 2. Task 2: Remove Particle Spam in Calm Weather (When S < 0.15, spawn ZERO particles!)
        if (stormIntensity < 0.15) {
            return;
        }

        RandomSource random = level.random;

        // 3. Environmental Ocean Whitecaps & Crest Foam in Storms (S >= 0.15)
        int sampleCount = stormIntensity > 0.6 ? 40 : 20;
        for (int i = 0; i < sampleCount; i++) {
            double angle = random.nextDouble() * (2.0 * Math.PI);
            double radius = 4.0 + random.nextDouble() * 36.0;
            double sampleX = playerPos.x + Math.cos(angle) * radius;
            double sampleZ = playerPos.z + Math.sin(angle) * radius;

            BlockPos probePos = BlockPos.containing(sampleX, 62, sampleZ);
            if (!level.getFluidState(probePos).is(FluidTags.WATER)) {
                continue;
            }

            double waveOffset = GerstnerWaveEngine.evaluateWaveOffset(
                    sampleX, sampleZ, timeSeconds, stormIntensity, windDirX, windDirZ);

            // Crest threshold: only spawn whitecap foam along peaks
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

        // 4. Dynamic Ship Hull & Bow Wave Splashes in Waves
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

    /**
     * Renders the 3D displaced ocean wave surface mesh.
     *
     * <p>Generates a seamless, connected height-field grid where adjacent quads share
     * identical vertices and interpolated surface normals, rendering smooth solid-filled
     * water planes with zero line streaks or disjointed seam artifacts.</p>
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
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
        // In calm weather (S < 0.05), vanilla ocean water is already perfectly flat - zero mesh needed
        if (stormIntensity < 0.05) {
            return;
        }

        double windDirX = waveState.smoothedWindDirX;
        double windDirZ = waveState.smoothedWindDirZ;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double renderTime = (level.getGameTime() + partialTick) * 0.05;

        PoseStack poseStack = event.getPoseStack();
        PoseStack.Pose pose = poseStack.last();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.translucent());

        TextureAtlasSprite waterSprite = mc.getModelManager()
                .getAtlas(TextureAtlas.LOCATION_BLOCKS)
                .getSprite(ResourceLocation.withDefaultNamespace("block/water_still"));

        float u0 = waterSprite.getU0();
        float u1 = waterSprite.getU1();
        float v0 = waterSprite.getV0();
        float v1 = waterSprite.getV1();

        int steps = (int) (GRID_RADIUS * 2 / GRID_STEP);
        int vertexCount = steps + 1;
        double originX = Math.floor((camPos.x - GRID_RADIUS) / GRID_STEP) * GRID_STEP;
        double originZ = Math.floor((camPos.z - GRID_RADIUS) / GRID_STEP) * GRID_STEP;

        double[][] eta = new double[vertexCount][vertexCount];
        float[][] rx = new float[vertexCount][vertexCount];
        float[][] ry = new float[vertexCount][vertexCount];
        float[][] rz = new float[vertexCount][vertexCount];
        int[][] rCol = new int[vertexCount][vertexCount];
        int[][] gCol = new int[vertexCount][vertexCount];
        int[][] bCol = new int[vertexCount][vertexCount];
        int[][] aCol = new int[vertexCount][vertexCount];
        int[][] light = new int[vertexCount][vertexCount];
        float[][] nx = new float[vertexCount][vertexCount];
        float[][] ny = new float[vertexCount][vertexCount];
        float[][] nz = new float[vertexCount][vertexCount];
        boolean[][] hasWater = new boolean[vertexCount][vertexCount];

        double crestLift = Math.max(0.04, 0.40 * stormIntensity);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

        // Pass 1: Compute continuous heights, water presence, and lighting for all grid vertices
        for (int i = 0; i <= steps; i++) {
            double wx = originX + i * GRID_STEP;
            for (int j = 0; j <= steps; j++) {
                double wz = originZ + j * GRID_STEP;
                probe.set(Mth.floor(wx), 62, Mth.floor(wz));
                boolean water = level.getFluidState(probe).is(FluidTags.WATER);
                hasWater[i][j] = water;
                if (!water) {
                    continue;
                }

                double waveOffset = GerstnerWaveEngine.evaluateWaveOffset(
                        wx, wz, renderTime, stormIntensity, windDirX, windDirZ);
                eta[i][j] = waveOffset;

                double wy = SEA_LEVEL_WATER_SURFACE + Math.max(0.02, waveOffset + crestLift);
                rx[i][j] = (float) (wx - camPos.x);
                ry[i][j] = (float) (wy - camPos.y);
                rz[i][j] = (float) (wz - camPos.z);

                double distFromCam = Math.hypot(wx - camPos.x, wz - camPos.z);
                float distFade = (float) Mth.clamp((GRID_RADIUS - distFromCam) / 8.0, 0.0, 1.0);

                int waterColor = BiomeColors.getAverageWaterColor(level, probe);
                int baseR = (waterColor >> 16) & 0xFF;
                int baseG = (waterColor >> 8) & 0xFF;
                int baseB = waterColor & 0xFF;

                float crestFactor = (float) Mth.clamp((waveOffset - 0.05) / (0.70 * stormIntensity + 0.15), 0.0, 1.0);
                rCol[i][j] = (int) Mth.lerp(crestFactor, baseR, 240);
                gCol[i][j] = (int) Mth.lerp(crestFactor, baseG, 248);
                bCol[i][j] = (int) Mth.lerp(crestFactor, baseB, 255);
                aCol[i][j] = (int) (Mth.lerp(crestFactor, 160, 230) * distFade);

                light[i][j] = LevelRenderer.getLightColor(level, probe.above());
            }
        }

        // Pass 2: Calculate shared smooth vertex normals
        for (int i = 0; i <= steps; i++) {
            for (int j = 0; j <= steps; j++) {
                if (!hasWater[i][j]) continue;
                int prevI = Math.max(0, i - 1);
                int nextI = Math.min(steps, i + 1);
                int prevJ = Math.max(0, j - 1);
                int nextJ = Math.min(steps, j + 1);

                float dh_dx = (float) ((eta[nextI][j] - eta[prevI][j]) / ((nextI - prevI) * GRID_STEP));
                float dh_dz = (float) ((eta[i][nextJ] - eta[i][prevJ]) / ((nextJ - prevJ) * GRID_STEP));
                float len = Mth.sqrt(dh_dx * dh_dx + 1.0f + dh_dz * dh_dz);
                nx[i][j] = -dh_dx / len;
                ny[i][j] = 1.0f / len;
                nz[i][j] = -dh_dz / len;
            }
        }

        // Pass 3: Emit solid, continuous quads with strict Counter-Clockwise (CCW) front-facing winding
        for (int i = 0; i < steps; i++) {
            for (int j = 0; j < steps; j++) {
                // Only render quads where all 4 vertices are over water
                if (!hasWater[i][j] || !hasWater[i][j + 1] || !hasWater[i + 1][j + 1] || !hasWater[i + 1][j]) {
                    continue;
                }
                if (aCol[i][j] <= 5 && aCol[i][j + 1] <= 5 && aCol[i + 1][j + 1] <= 5 && aCol[i + 1][j] <= 5) {
                    continue;
                }

                // CCW winding:
                // Vertex 0: (i, j)     [minX, minZ]
                // Vertex 1: (i, j+1)   [minX, maxZ]
                // Vertex 2: (i+1, j+1) [maxX, maxZ]
                // Vertex 3: (i+1, j)   [maxX, minZ]
                emitVertex(pose, vc, rx[i][j], ry[i][j], rz[i][j],
                        rCol[i][j], gCol[i][j], bCol[i][j], aCol[i][j],
                        u0, v0, light[i][j], nx[i][j], ny[i][j], nz[i][j]);

                emitVertex(pose, vc, rx[i][j + 1], ry[i][j + 1], rz[i][j + 1],
                        rCol[i][j + 1], gCol[i][j + 1], bCol[i][j + 1], aCol[i][j + 1],
                        u0, v1, light[i][j + 1], nx[i][j + 1], ny[i][j + 1], nz[i][j + 1]);

                emitVertex(pose, vc, rx[i + 1][j + 1], ry[i + 1][j + 1], rz[i + 1][j + 1],
                        rCol[i + 1][j + 1], gCol[i + 1][j + 1], bCol[i + 1][j + 1], aCol[i + 1][j + 1],
                        u1, v1, light[i + 1][j + 1], nx[i + 1][j + 1], ny[i + 1][j + 1], nz[i + 1][j + 1]);

                emitVertex(pose, vc, rx[i + 1][j], ry[i + 1][j], rz[i + 1][j],
                        rCol[i + 1][j], gCol[i + 1][j], bCol[i + 1][j], aCol[i + 1][j],
                        u1, v0, light[i + 1][j], nx[i + 1][j], ny[i + 1][j], nz[i + 1][j]);
            }
        }

        bufferSource.endBatch(RenderType.translucent());
    }

    private static void emitVertex(PoseStack.Pose pose, VertexConsumer vc,
                                   float x, float y, float z,
                                   int r, int g, int b, int a,
                                   float u, float v, int light,
                                   float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }

    private static void updateWaterCache(Level level, double camX, double camZ) {
        int startX = (int) (Math.floor((camX - GRID_RADIUS) / GRID_STEP) * GRID_STEP);
        int startZ = (int) (Math.floor((camZ - GRID_RADIUS) / GRID_STEP) * GRID_STEP);
        int endX = (int) (Math.ceil((camX + GRID_RADIUS) / GRID_STEP) * GRID_STEP);
        int endZ = (int) (Math.ceil((camZ + GRID_RADIUS) / GRID_STEP) * GRID_STEP);

        List<BlockPos> newColumns = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = startX; x <= endX; x += (int) GRID_STEP) {
            for (int z = startZ; z <= endZ; z += (int) GRID_STEP) {
                pos.set(x, 62, z);
                if (level.getFluidState(pos).is(FluidTags.WATER) && level.getBlockState(pos.above()).isAir()) {
                    newColumns.add(new BlockPos(x, 62, z));
                }
            }
        }

        synchronized (CACHED_WATER_COLUMNS) {
            CACHED_WATER_COLUMNS.clear();
            CACHED_WATER_COLUMNS.addAll(newColumns);
        }
    }
}
