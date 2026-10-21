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
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side visual representation of Gerstner ocean waves using an isolated,
 * self-contained {@link Tesselator} pass during {@link RenderLevelStageEvent.Stage#AFTER_PARTICLES}.
 *
 * <p>Key visual & architectural highlights:
 * <ul>
 *   <li><b>Real Animated Water Texture:</b> Statically locked 1x1 block UVs match vanilla Minecraft's
 *       animated {@code block/water_still} sprite with zero stretching or sliding.</li>
 *   <li><b>Rolling Offshore Surf Breakers:</b> Detached, bright white crescent surf lines form 2 to 6 blocks
 *       offshore, roll toward the beach, and dissolve before reaching the sand.</li>
 *   <li><b>Seamless Boundary Pinning:</b> Pinned to {@code 62.890f} at block edges with smooth biome color
 *       and uniform alpha, eliminating hollow polygon gaps and jagged sawtooth triangles.</li>
 *   <li><b>Active Multi-Zone Wave Particles:</b> Responsive water splashes, bubbles, and foam ripples along
 *       breaking beach surf, open-ocean swells, and underway ship hulls.</li>
 * </ul>
 */
public class ClientWaveRenderer {

    private static final double SEA_LEVEL_WATER_SURFACE = 62.88;
    private static final float VANILLA_WATER_TOP_Y = 62.890f;
    private static final float PINNED_WATER_Y = 62.890f;
    private static final int CELL_STEP = 1; // 1x1 block grid matching vanilla Minecraft block resolution

    // Dynamic grid dimensions based on simulation distance
    private static int cachedRadius = -1;
    private static int cachedSteps = -1;
    private static int cachedGridDim = -1;
    private static boolean[][] isWaterSurface = new boolean[0][0];
    private static float[][] cachedShoreDist = new float[0][0];
    private static int[][] cachedBiomeColor = new int[0][0];

    private static int cachedCenterX = Integer.MIN_VALUE;
    private static int cachedCenterZ = Integer.MIN_VALUE;
    private static long lastCacheUpdateTick = -1L;

    private static final Map<ClientSubLevel, Vector3d> lastShipPos = new ConcurrentHashMap<>();

    /**
     * Resizes the cached grid if simulation distance changes, and scans water surface.
     * Stored at exact 1x1 block resolution with Chebyshev distance-to-shore field.
     */
    private static void updateWaterCache(Level level, double camX, double camZ, long gameTime) {
        int simChunks = Minecraft.getInstance().options.simulationDistance().get();
        int desiredRadius = Mth.clamp(simChunks * 16, 48, 96);

        if (desiredRadius != cachedRadius) {
            cachedRadius = desiredRadius;
            cachedSteps = cachedRadius / CELL_STEP;
            cachedGridDim = cachedSteps * 2 + 1;
            isWaterSurface = new boolean[cachedGridDim][cachedGridDim];
            cachedShoreDist = new float[cachedGridDim][cachedGridDim];
            cachedBiomeColor = new int[cachedGridDim][cachedGridDim];
            cachedCenterX = Integer.MIN_VALUE;
            cachedCenterZ = Integer.MIN_VALUE;
            lastCacheUpdateTick = -1L;
        }

        int camBlockX = Mth.floor(camX);
        int camBlockZ = Mth.floor(camZ);

        if (gameTime - lastCacheUpdateTick < 15L
                && Math.abs(camBlockX - cachedCenterX) < 4
                && Math.abs(camBlockZ - cachedCenterZ) < 4) {
            return;
        }

        lastCacheUpdateTick = gameTime;
        cachedCenterX = camBlockX;
        cachedCenterZ = camBlockZ;

        int startX = camBlockX - (cachedSteps * CELL_STEP);
        int startZ = camBlockZ - (cachedSteps * CELL_STEP);

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

        int minChunkX = startX >> 4;
        int maxChunkX = (startX + cachedGridDim * CELL_STEP) >> 4;
        int minChunkZ = startZ >> 4;
        int maxChunkZ = (startZ + cachedGridDim * CELL_STEP) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                boolean chunkLoaded = level.getChunkSource().hasChunk(cx, cz);

                int cellMinX = Math.max(0, ((cx << 4) - startX) / CELL_STEP);
                int cellMaxX = Math.min(cachedGridDim - 1, (((cx << 4) + 15) - startX) / CELL_STEP);
                int cellMinZ = Math.max(0, ((cz << 4) - startZ) / CELL_STEP);
                int cellMaxZ = Math.min(cachedGridDim - 1, (((cz << 4) + 15) - startZ) / CELL_STEP);

                if (!chunkLoaded) {
                    for (int gx = cellMinX; gx <= cellMaxX; gx++) {
                        for (int gz = cellMinZ; gz <= cellMaxZ; gz++) {
                            isWaterSurface[gx][gz] = false;
                            cachedShoreDist[gx][gz] = 0.0f;
                        }
                    }
                    continue;
                }

                for (int gx = cellMinX; gx <= cellMaxX; gx++) {
                    int wx = startX + gx * CELL_STEP;
                    for (int gz = cellMinZ; gz <= cellMaxZ; gz++) {
                        int wz = startZ + gz * CELL_STEP;

                        probe.set(wx, 62, wz);
                        FluidState fluid = level.getFluidState(probe);
                        if (!fluid.is(FluidTags.WATER)) {
                            isWaterSurface[gx][gz] = false;
                            cachedShoreDist[gx][gz] = 0.0f;
                            continue;
                        }

                        probe.set(wx, 63, wz);
                        BlockState above = level.getBlockState(probe);
                        if (!above.isAir()) {
                            // Any non-air block at Y=63 (wooden dock plank, slab, stair, lily pad, etc.)
                            // means this column is covered or solid!
                            isWaterSurface[gx][gz] = false;
                            cachedShoreDist[gx][gz] = 0.0f;
                            continue;
                        }

                        isWaterSurface[gx][gz] = true;
                        cachedBiomeColor[gx][gz] = BiomeColors.getAverageWaterColor(level, probe);
                    }
                }
            }
        }

        // Compute Distance-to-Shore field (Chebyshev distance in [1..8] blocks, or 99 in deep water)
        int maxCells = cachedGridDim * cachedGridDim;
        int[] queue = new int[maxCells];
        int head = 0, tail = 0;

        for (int gx = 0; gx < cachedGridDim; gx++) {
            for (int gz = 0; gz < cachedGridDim; gz++) {
                if (!isWaterSurface[gx][gz]) {
                    cachedShoreDist[gx][gz] = 0.0f;
                    continue;
                }
                boolean adjacentToNonWater = false;
                for (int dx = -1; dx <= 1 && !adjacentToNonWater; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        int nx = gx + dx;
                        int nz = gz + dz;
                        if (nx < 0 || nx >= cachedGridDim || nz < 0 || nz >= cachedGridDim || !isWaterSurface[nx][nz]) {
                            adjacentToNonWater = true;
                            break;
                        }
                    }
                }
                if (adjacentToNonWater) {
                    cachedShoreDist[gx][gz] = 1.0f;
                    queue[tail++] = (gx << 16) | gz;
                } else {
                    cachedShoreDist[gx][gz] = 99.0f;
                }
            }
        }

        while (head < tail) {
            int entry = queue[head++];
            int gx = entry >> 16;
            int gz = entry & 0xFFFF;
            float d = cachedShoreDist[gx][gz];
            if (d >= 8.0f) {
                continue;
            }
            float nextD = d + 1.0f;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = gx + dx;
                    int nz = gz + dz;
                    if (nx >= 0 && nx < cachedGridDim && nz >= 0 && nz < cachedGridDim) {
                        if (isWaterSurface[nx][nz] && cachedShoreDist[nx][nz] > nextD) {
                            cachedShoreDist[nx][nz] = nextD;
                            queue[tail++] = (nx << 16) | nz;
                        }
                    }
                }
            }
        }
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
        if (camPos.y < 45.0 || camPos.y > 115.0) {
            return;
        }

        GerstnerWaveEngine.DimensionWaveState waveState = GerstnerWaveEngine.getDimensionState(level);
        double stormIntensity = waveState.smoothedStormIntensity;
        if (stormIntensity < 0.04) {
            return;
        }

        long gameTime = level.getGameTime();
        updateWaterCache(level, camPos.x, camPos.z, gameTime);
        if (cachedRadius <= 0 || cachedGridDim <= 0) {
            return;
        }

        int startX = cachedCenterX - (cachedSteps * CELL_STEP);
        int startZ = cachedCenterZ - (cachedSteps * CELL_STEP);

        double windDirX = waveState.smoothedWindDirX;
        double windDirZ = waveState.smoothedWindDirZ;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double renderTime = (gameTime + partialTick) * 0.05;

        // 2 High-Frequency visual chop harmonics (lambda ~ 11m and 5.8m)
        double windAngle = Math.atan2(windDirZ, windDirX);
        double angle1 = windAngle + 0.30;
        double angle2 = windAngle - 0.40;
        double dirX1 = Math.cos(angle1), dirZ1 = Math.sin(angle1);
        double dirX2 = Math.cos(angle2), dirZ2 = Math.sin(angle2);
        double amp1 = 0.12 * stormIntensity + 0.035;
        double amp2 = 0.07 * stormIntensity + 0.020;

        int vertDim = cachedGridDim + 1;

        // Boundary Vertex Pinning: A corner vertex is interior ONLY if all 4 adjacent 1x1 cells are open water!
        boolean[][] isInterior = new boolean[vertDim][vertDim];
        for (int vi = 1; vi < cachedGridDim; vi++) {
            for (int vj = 1; vj < cachedGridDim; vj++) {
                if (isWaterSurface[vi - 1][vj - 1]
                        && isWaterSurface[vi - 1][vj]
                        && isWaterSurface[vi][vj - 1]
                        && isWaterSurface[vi][vj]) {
                    isInterior[vi][vj] = true;
                }
            }
        }

        // Smoothly interpolate vertex shore distance from adjacent water cells
        float[][] vertShoreDist = new float[vertDim][vertDim];
        for (int vi = 0; vi < vertDim; vi++) {
            for (int vj = 0; vj < vertDim; vj++) {
                if (!isInterior[vi][vj]) {
                    vertShoreDist[vi][vj] = 0.5f; // Pinned boundary vertex
                    continue;
                }
                float sum = 0.0f;
                int count = 0;
                for (int di = -1; di <= 0; di++) {
                    for (int dj = -1; dj <= 0; dj++) {
                        int ci = vi + di;
                        int cj = vj + dj;
                        if (ci >= 0 && ci < cachedGridDim && cj >= 0 && cj < cachedGridDim && isWaterSurface[ci][cj]) {
                            sum += cachedShoreDist[ci][cj];
                            count++;
                        }
                    }
                }
                vertShoreDist[vi][vj] = count > 0 ? (sum / count) : 1.0f;
            }
        }

        float[][] waveOffset = new float[vertDim][vertDim];
        float[][] breakerFactor = new float[vertDim][vertDim];

        for (int vi = 0; vi < vertDim; vi++) {
            double vx = startX + vi * CELL_STEP;
            for (int vj = 0; vj < vertDim; vj++) {
                if (!isInterior[vi][vj]) {
                    continue; // Pinned boundary vertex, height stays 62.890f
                }
                double vz = startZ + vj * CELL_STEP;
                double baseWave = GerstnerWaveEngine.evaluateWaveOffset(
                        vx, vz, renderTime, stormIntensity, windDirX, windDirZ);
                double chop1 = Math.sin(0.5712 * (dirX1 * vx + dirZ1 * vz) - 2.367 * renderTime) * amp1;
                double chop2 = Math.sin(1.0833 * (dirX2 * vx + dirZ2 * vz) - 3.259 * renderTime) * amp2;
                double h = baseWave + chop1 + chop2;

                // Wave Shoaling & Breaking Physics in the Surf Zone (1.2 <= sDist <= 6.5 blocks from shore)
                float sDist = vertShoreDist[vi][vj];
                if (sDist >= 1.2f && sDist <= 6.5f) {
                    double spatialWarp = Math.sin(vx * 0.18 + vz * 0.14) * 1.6;
                    double phase = sDist * 1.15 - renderTime * 2.6 + spatialWarp;
                    double crestFactor = Math.sin(phase);

                    if (crestFactor > 0.55 && sDist <= 5.5f) {
                        float strength = (float) Mth.clamp((crestFactor - 0.55) / 0.45, 0.0, 1.0);
                        float zoneEnvelope = 1.0f;
                        if (sDist < 2.0f) {
                            zoneEnvelope = (sDist - 1.2f) / 0.8f;
                        } else if (sDist > 4.5f) {
                            zoneEnvelope = (5.5f - sDist) / 1.0f;
                        }
                        strength *= Mth.clamp(zoneEnvelope, 0.0f, 1.0f);

                        // Sharpen wave height slightly so breaker visibly curls near shore
                        h += 0.12f * strength;
                        breakerFactor[vi][vj] = strength;
                    }
                }

                waveOffset[vi][vj] = (float) h;
            }
        }

        // Texture sprite for animated native water - statically locked to 1x1 block coordinates
        TextureAtlasSprite waterSprite = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.withDefaultNamespace("block/water_still"));
        float u0 = waterSprite.getU0();
        float u1 = waterSprite.getU1();
        float v0 = waterSprite.getV0();
        float v1 = waterSprite.getV1();

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull(); // Prevents backface culling from turning waves into thin streak lines
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        // Directional light vector for wave slope shading
        float lightDirX = (float) windDirX * 0.5f - 0.25f;
        float lightDirY = 0.80f;
        float lightDirZ = (float) windDirZ * 0.5f - 0.25f;
        float lLen = Mth.sqrt(lightDirX * lightDirX + lightDirY * lightDirY + lightDirZ * lightDirZ);
        lightDirX /= lLen; lightDirY /= lLen; lightDirZ /= lLen;

        float swellBaseline = (float) (0.08 * stormIntensity + 0.04);

        for (int gx = 0; gx < cachedGridDim; gx++) {
            for (int gz = 0; gz < cachedGridDim; gz++) {
                if (!isWaterSurface[gx][gz]) {
                    continue;
                }

                float h00 = waveOffset[gx][gz];
                float h01 = waveOffset[gx][gz + 1];
                float h11 = waveOffset[gx + 1][gz + 1];
                float h10 = waveOffset[gx + 1][gz];

                double wx0 = startX + gx * CELL_STEP;
                double wx1 = wx0 + CELL_STEP;
                double wz0 = startZ + gz * CELL_STEP;
                double wz1 = wz0 + CELL_STEP;

                boolean int00 = isInterior[gx][gz];
                boolean int01 = isInterior[gx][gz + 1];
                boolean int11 = isInterior[gx + 1][gz + 1];
                boolean int10 = isInterior[gx + 1][gz];

                // Interior water vertices undulate smoothly; boundary vertices pin to 62.890f
                float y00 = int00 ? (VANILLA_WATER_TOP_Y + Math.max(0.0f, h00 + swellBaseline)) : PINNED_WATER_Y;
                float y01 = int01 ? (VANILLA_WATER_TOP_Y + Math.max(0.0f, h01 + swellBaseline)) : PINNED_WATER_Y;
                float y11 = int11 ? (VANILLA_WATER_TOP_Y + Math.max(0.0f, h11 + swellBaseline)) : PINNED_WATER_Y;
                float y10 = int10 ? (VANILLA_WATER_TOP_Y + Math.max(0.0f, h10 + swellBaseline)) : PINNED_WATER_Y;

                int biomeColor = cachedBiomeColor[gx][gz];
                int br = (biomeColor >> 16) & 0xFF;
                int bg = (biomeColor >> 8) & 0xFF;
                int bb = biomeColor & 0xFF;

                int c00 = computeVertexColor(int00, h00, breakerFactor[gx][gz], br, bg, bb,
                        lightDirX, lightDirY, lightDirZ, wx0, wz0, camPos.x, camPos.z, stormIntensity,
                        gx, gz, waveOffset);
                int c01 = computeVertexColor(int01, h01, breakerFactor[gx][gz + 1], br, bg, bb,
                        lightDirX, lightDirY, lightDirZ, wx0, wz1, camPos.x, camPos.z, stormIntensity,
                        gx, gz + 1, waveOffset);
                int c11 = computeVertexColor(int11, h11, breakerFactor[gx + 1][gz + 1], br, bg, bb,
                        lightDirX, lightDirY, lightDirZ, wx1, wz1, camPos.x, camPos.z, stormIntensity,
                        gx + 1, gz + 1, waveOffset);
                int c10 = computeVertexColor(int10, h10, breakerFactor[gx + 1][gz], br, bg, bb,
                        lightDirX, lightDirY, lightDirZ, wx1, wz0, camPos.x, camPos.z, stormIntensity,
                        gx + 1, gz, waveOffset);

                if (((c00 | c01 | c11 | c10) >>> 24) == 0) {
                    continue;
                }

                // Strictly static world-locked 1x1 block UVs matching vanilla Minecraft water sprite
                bufferBuilder.addVertex(matrix, (float)(wx0 - camPos.x), (float)(y00 - camPos.y), (float)(wz0 - camPos.z))
                        .setUv(u0, v0).setColor(c00);
                bufferBuilder.addVertex(matrix, (float)(wx0 - camPos.x), (float)(y01 - camPos.y), (float)(wz1 - camPos.z))
                        .setUv(u0, v1).setColor(c01);
                bufferBuilder.addVertex(matrix, (float)(wx1 - camPos.x), (float)(y11 - camPos.y), (float)(wz1 - camPos.z))
                        .setUv(u1, v1).setColor(c11);
                bufferBuilder.addVertex(matrix, (float)(wx1 - camPos.x), (float)(y10 - camPos.y), (float)(wz0 - camPos.z))
                        .setUv(u1, v0).setColor(c10);
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

    private static int computeVertexColor(boolean isInterior, float h, float breaker,
                                          int br, int bg, int bb,
                                          float lightDirX, float lightDirY, float lightDirZ,
                                          double wx, double wz, double camX, double camZ,
                                          double stormIntensity,
                                          int vi, int vj, float[][] waveOffset) {
        float distFromCam = (float) Math.hypot(wx - camX, wz - camZ);
        float distFade = (float) Mth.clamp((cachedRadius - distFromCam) / 16.0, 0.0, 1.0);
        if (distFade <= 0.005f) {
            return 0;
        }

        if (!isInterior) {
            // Shoreline / Dock boundary vertex:
            // Pure biome water color (NO permanent white tint!), smooth alpha (160 * distFade)
            // Meets the shore seamlessly without jagged 1x1 sawtooth triangle teeth!
            int a = (int) (160.0f * distFade);
            return ((a & 0xFF) << 24) | ((br & 0xFF) << 16) | ((bg & 0xFF) << 8) | (bb & 0xFF);
        }

        // Compute local slope normal
        int prevI = Math.max(0, vi - 1);
        int nextI = Math.min(cachedGridDim, vi + 1);
        int prevJ = Math.max(0, vj - 1);
        int nextJ = Math.min(cachedGridDim, vj + 1);

        float dh_dx = (waveOffset[nextI][vj] - waveOffset[prevI][vj]) / ((float) (nextI - prevI) * CELL_STEP);
        float dh_dz = (waveOffset[vi][nextJ] - waveOffset[vi][prevJ]) / ((float) (nextJ - prevJ) * CELL_STEP);
        float normLen = Mth.sqrt(dh_dx * dh_dx + 1.0f + dh_dz * dh_dz);
        float nx = -dh_dx / normLen;
        float ny = 1.0f / normLen;
        float nz = -dh_dz / normLen;

        float dot = nx * lightDirX + ny * lightDirY + nz * lightDirZ;
        float slopeFactor = Mth.clamp(0.85f + 0.30f * dot, 0.78f, 1.18f);

        int r = (int) Mth.clamp(br * slopeFactor, 0, 255);
        int g = (int) Mth.clamp(bg * slopeFactor, 0, 255);
        int b = (int) Mth.clamp(bb * slopeFactor, 0, 255);

        // Cohesive alpha across continuous ocean surface
        int a = (int) (185.0f * distFade);

        // 1. Dynamic Rolling Surf Breaker White Foam Ribbon (2 to 6 blocks offshore)
        if (breaker > 0.0f) {
            r = (int) Mth.lerp(breaker, r, 245);
            g = (int) Mth.lerp(breaker, g, 250);
            b = (int) Mth.lerp(breaker, b, 255);
            a = (int) Math.min(235, a + breaker * 45.0f);
        }

        // 2. Steep storm peak foam (only during active storms: stormIntensity > 0.5)
        if (stormIntensity > 0.5 && h > 0.36f) {
            float stormFoam = Mth.clamp((h - 0.36f) / 0.32f, 0.0f, 1.0f) * (float) ((stormIntensity - 0.5) / 0.5);
            r = (int) Mth.lerp(stormFoam, r, 230);
            g = (int) Mth.lerp(stormFoam, g, 245);
            b = (int) Mth.lerp(stormFoam, b, 255);
            a = (int) Math.min(235, a + stormFoam * 40.0f);
        }

        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
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
        // Active wave and surf particles enabled in all active sailing/weather conditions (stormIntensity >= 0.10)
        if (stormIntensity < 0.10) {
            return;
        }

        double windDirX = waveState.smoothedWindDirX;
        double windDirZ = waveState.smoothedWindDirZ;
        RandomSource random = level.random;

        int gridStartX = cachedRadius > 0 ? (cachedCenterX - cachedSteps * CELL_STEP) : 0;
        int gridStartZ = cachedRadius > 0 ? (cachedCenterZ - cachedSteps * CELL_STEP) : 0;

        // --- Zone A & B: Shoreline Breaking Surf & Open-Ocean Whitecap Particles ---
        int sampleCount = stormIntensity > 0.5 ? 40 : 25;
        for (int i = 0; i < sampleCount; i++) {
            double angle = random.nextDouble() * (2.0 * Math.PI);
            double radius = 3.0 + random.nextDouble() * 38.0;
            double sampleX = playerPos.x + Math.cos(angle) * radius;
            double sampleZ = playerPos.z + Math.sin(angle) * radius;

            BlockPos probePos = BlockPos.containing(sampleX, 62, sampleZ);
            if (!level.hasChunkAt(probePos) || !level.getFluidState(probePos).is(FluidTags.WATER)) {
                continue;
            }

            int gx = Mth.floor(sampleX - gridStartX);
            int gz = Mth.floor(sampleZ - gridStartZ);

            float sDist = 99.0f;
            if (cachedGridDim > 0 && gx >= 0 && gx < cachedGridDim && gz >= 0 && gz < cachedGridDim && isWaterSurface[gx][gz]) {
                sDist = cachedShoreDist[gx][gz];
            }

            // Zone A: Shoreline Breaking Surf Particles (active on breaking surf crests near beach)
            if (sDist >= 1.5f && sDist <= 5.0f) {
                double spatialWarp = Math.sin(sampleX * 0.18 + sampleZ * 0.14) * 1.6;
                double phase = sDist * 1.15 - timeSeconds * 2.6 + spatialWarp;
                double crestFactor = Math.sin(phase);

                if (crestFactor > 0.65) {
                    // Shoreward direction calculated from cachedShoreDist gradient
                    int prevGX = Math.max(0, gx - 1);
                    int nextGX = Math.min(cachedGridDim - 1, gx + 1);
                    int prevGZ = Math.max(0, gz - 1);
                    int nextGZ = Math.min(cachedGridDim - 1, gz + 1);
                    double dDist_dx = cachedShoreDist[nextGX][gz] - cachedShoreDist[prevGX][gz];
                    double dDist_dz = cachedShoreDist[gx][nextGZ] - cachedShoreDist[gx][prevGZ];
                    double shoreDirX = -dDist_dx;
                    double shoreDirZ = -dDist_dz;
                    double dirLen = Math.sqrt(shoreDirX * shoreDirX + shoreDirZ * shoreDirZ);
                    if (dirLen > 1.0e-4) {
                        shoreDirX /= dirLen;
                        shoreDirZ /= dirLen;
                    } else {
                        shoreDirX = windDirX;
                        shoreDirZ = windDirZ;
                    }

                    double baseWave = GerstnerWaveEngine.evaluateWaveOffset(
                            sampleX, sampleZ, timeSeconds, stormIntensity, windDirX, windDirZ);
                    double crestY = 62.95 + Math.max(0.0, baseWave + 0.10);

                    double vx = shoreDirX * 0.08 + (random.nextDouble() - 0.5) * 0.04;
                    double vy = 0.05 + random.nextDouble() * 0.06;
                    double vz = shoreDirZ * 0.08 + (random.nextDouble() - 0.5) * 0.04;

                    level.addParticle(ParticleTypes.SPLASH, sampleX, crestY, sampleZ, vx, vy, vz);

                    if (random.nextFloat() < 0.45f) {
                        level.addParticle(ParticleTypes.BUBBLE_POP, sampleX, crestY + 0.02, sampleZ,
                                vx * 0.5, 0.02, vz * 0.5);
                    }

                    if (random.nextFloat() < 0.20f) {
                        level.addParticle(ParticleTypes.CLOUD, sampleX, crestY + 0.03, sampleZ,
                                shoreDirX * 0.03, 0.01, shoreDirZ * 0.03);
                    } else if (random.nextFloat() < 0.25f) {
                        level.addParticle(ParticleTypes.FISHING, sampleX, crestY + 0.02, sampleZ,
                                shoreDirX * 0.02, 0.01, shoreDirZ * 0.02);
                    }
                    continue;
                }
            }

            // Zone B: Open-Ocean Whitecap Crest Particles
            double waveOffset = GerstnerWaveEngine.evaluateWaveOffset(
                    sampleX, sampleZ, timeSeconds, stormIntensity, windDirX, windDirZ);
            double peakThreshold = stormIntensity > 0.5 ? 0.35 : 0.22;

            if (waveOffset > peakThreshold) {
                double crestY = SEA_LEVEL_WATER_SURFACE + waveOffset;
                double driftSpeed = 0.06 + stormIntensity * 0.12;
                double vx = windDirX * driftSpeed + (random.nextDouble() - 0.5) * 0.05;
                double vy = 0.04 + random.nextDouble() * 0.05;
                double vz = windDirZ * driftSpeed + (random.nextDouble() - 0.5) * 0.05;

                level.addParticle(ParticleTypes.SPLASH, sampleX, crestY, sampleZ, vx, vy, vz);

                if (random.nextFloat() < 0.35f) {
                    level.addParticle(ParticleTypes.BUBBLE_POP, sampleX, crestY + 0.02, sampleZ,
                            vx * 0.5, 0.02, vz * 0.5);
                }

                if (stormIntensity > 0.5 && random.nextFloat() < 0.20f) {
                    level.addParticle(ParticleTypes.CLOUD, sampleX, crestY + 0.1, sampleZ,
                            windDirX * 0.20, 0.06, windDirZ * 0.20);
                }
            }
        }

        // --- Zone C: Ship Hull & Bow Wave Spray around moving ships or in wave crests ---
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container instanceof ClientSubLevelContainer clientContainer)) {
            return;
        }

        if (lastShipPos.size() > 50) {
            lastShipPos.clear();
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
            if (distSq > 64.0 * 64.0) {
                continue;
            }

            Pose3dc pose = subLevel.logicalPose();
            Vector3dc shipPos = pose.position();
            Quaterniondc orientation = pose.orientation();

            Vector3d currentPos = new Vector3d(shipPos.x(), shipPos.y(), shipPos.z());
            Vector3d prevPos = lastShipPos.put(subLevel, currentPos);
            double speed = prevPos != null ? prevPos.distance(currentPos) : 0.0;

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

            boolean isMoving = speed > 0.05;
            boolean hasWaveAgainstHull = bowWaveOffset > 0.15 || stormIntensity > 0.35;

            if (isMoving || hasWaveAgainstHull) {
                double sprayY = SEA_LEVEL_WATER_SURFACE + Math.max(0.0, bowWaveOffset);
                double spraySpeed = Math.max(speed * 0.8, 0.08);

                for (int s = -1; s <= 1; s += 2) {
                    Vector3d shoulder = new Vector3d(bowPos)
                            .add(new Vector3d(starboardWorld).mul(s * halfBeam * 0.6))
                            .sub(new Vector3d(forwardWorld).mul(1.5));

                    double sprayVx = s * starboardWorld.x * spraySpeed + forwardWorld.x * (speed * 0.5) + (random.nextDouble() - 0.5) * 0.04;
                    double sprayVy = 0.06 + Math.min(0.12, speed * 0.20) + random.nextDouble() * 0.08;
                    double sprayVz = s * starboardWorld.z * spraySpeed + forwardWorld.z * (speed * 0.5) + (random.nextDouble() - 0.5) * 0.04;

                    level.addParticle(ParticleTypes.SPLASH, shoulder.x, sprayY, shoulder.z,
                            sprayVx, sprayVy, sprayVz);

                    if (random.nextFloat() < 0.35f) {
                        level.addParticle(ParticleTypes.BUBBLE_POP, shoulder.x, sprayY + 0.02, shoulder.z,
                                sprayVx * 0.5, 0.02, sprayVz * 0.5);
                    }

                    if ((isMoving && speed > 0.15) || stormIntensity > 0.5) {
                        if (random.nextFloat() < 0.20f) {
                            level.addParticle(ParticleTypes.CLOUD, shoulder.x, sprayY + 0.05, shoulder.z,
                                    sprayVx * 0.8, sprayVy * 0.5, sprayVz * 0.8);
                        }
                    }
                }
            }
        }
    }
}
