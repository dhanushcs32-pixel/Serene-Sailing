package com.serenesailing.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.serenesailing.config.SereneSailingClientConfig;
import com.serenesailing.hold.HoldRegistry;
import com.serenesailing.hold.HoldScanner;
import com.serenesailing.hold.SableWaterOcclusion;
import com.serenesailing.physics.waves.GerstnerWaveEngine;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side visual representation of Gerstner ocean waves using an isolated,
 * self-contained {@link Tesselator} pass during
 * {@link RenderLevelStageEvent.Stage#AFTER_PARTICLES}.
 *
 * <p>
 * Key visual & architectural highlights:
 * <ul>
 * <li><b>Real Animated Water Texture:</b> Statically locked 1x1 block UVs match
 * vanilla Minecraft's
 * animated {@code block/water_still} sprite with zero stretching or
 * sliding.</li>
 * <li><b>Natural Beach-Only Crescent Surf Breakers:</b> Detached, high-contrast
 * white crescent surf lines
 * form exclusively along natural sloping beaches (ignoring wooden docks, piers,
 * and deep cutouts),
 * roll shoreward, and wash out cleanly on the sand slope.</li>
 * <li><b>Seamless Boundary Pinning:</b> Pinned to {@code 62.890f} at all
 * solid/dock edges with smooth biome
 * color and uniform alpha, eliminating hollow polygon gaps and jagged sawtooth
 * triangles.</li>
 * <li><b>Active Multi-Zone Wave Particles:</b> Responsive water splashes and
 * bubbles along
 * breaking beach surf, open-ocean swells, and underway ship hulls.</li>
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
    private static boolean[][] isNaturalBeachSeed = new boolean[0][0];
    private static boolean[][] isShallowWater = new boolean[0][0];
    private static float[][] cachedShoreDist = new float[0][0];
    private static int[][] cachedBiomeColor = new int[0][0];
    private static float[][] cachedBiomeMultiplier = new float[0][0];
    private static byte[][] cachedSkyLight = new byte[0][0];
    private static byte[][] cachedBlockLight = new byte[0][0];

    private static int cachedCenterX = Integer.MIN_VALUE;
    private static int cachedCenterZ = Integer.MIN_VALUE;
    private static long lastCacheUpdateTick = -1L;

    private static final Map<ClientSubLevel, Vector3d> lastShipPos = new ConcurrentHashMap<>();

    /**
     * Checks if a block state represents natural coastal terrain (sand, gravel,
     * dirt, grass, clay, stone)
     * rather than man-made docks, planks, contraptions, or air cutouts.
     */
    private static boolean isNaturalShoreBlock(BlockState state) {
        if (state.isAir() || state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS)
                || state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.DOORS)) {
            return false;
        }
        return state.is(BlockTags.SAND)
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.TERRACOTTA)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.SMOOTH_SANDSTONE);
    }

    /**
     * Resizes the cached grid if simulation distance changes, and scans water
     * surface.
     * Decouples airtight boundary pinning (all solid obstacles/docks) from natural
     * beach surf generation.
     */
    private static void updateWaterCache(Level level, double camX, double camZ, long gameTime) {
        int simChunks = Minecraft.getInstance().options.simulationDistance().get();
        int desiredRadius = Mth.clamp(simChunks * 16, 48, 96);

        if (desiredRadius != cachedRadius) {
            cachedRadius = desiredRadius;
            cachedSteps = cachedRadius / CELL_STEP;
            cachedGridDim = cachedSteps * 2 + 1;
            isWaterSurface = new boolean[cachedGridDim][cachedGridDim];
            isNaturalBeachSeed = new boolean[cachedGridDim][cachedGridDim];
            isShallowWater = new boolean[cachedGridDim][cachedGridDim];
            cachedShoreDist = new float[cachedGridDim][cachedGridDim];
            cachedBiomeColor = new int[cachedGridDim][cachedGridDim];
            cachedBiomeMultiplier = new float[cachedGridDim][cachedGridDim];
            cachedSkyLight = new byte[cachedGridDim][cachedGridDim];
            cachedBlockLight = new byte[cachedGridDim][cachedGridDim];
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
                            isNaturalBeachSeed[gx][gz] = false;
                            isShallowWater[gx][gz] = false;
                            cachedShoreDist[gx][gz] = 99.0f;
                            cachedBiomeMultiplier[gx][gz] = 1.0f;
                            cachedSkyLight[gx][gz] = 15;
                            cachedBlockLight[gx][gz] = 0;
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
                            isShallowWater[gx][gz] = false;
                            cachedShoreDist[gx][gz] = 99.0f;
                            cachedBiomeMultiplier[gx][gz] = 1.0f;
                            cachedSkyLight[gx][gz] = 15;
                            cachedBlockLight[gx][gz] = 0;

                            // Check if this non-water column at Y=62 is natural beach terrain
                            BlockState state62 = level.getBlockState(probe);
                            isNaturalBeachSeed[gx][gz] = isNaturalShoreBlock(state62);
                            continue;
                        }

                        probe.set(wx, 63, wz);
                        BlockState above = level.getBlockState(probe);
                        if (!above.isAir()) {
                            // Wooden dock plank, slab, stair, etc. at Y=63 covers the water surface,
                            // but is NOT a natural beach surf generator unless it's natural terrain
                            isWaterSurface[gx][gz] = false;
                            isShallowWater[gx][gz] = false;
                            isNaturalBeachSeed[gx][gz] = isNaturalShoreBlock(above);
                            cachedShoreDist[gx][gz] = 99.0f;
                            cachedBiomeMultiplier[gx][gz] = 1.0f;
                            cachedSkyLight[gx][gz] = 15;
                            cachedBlockLight[gx][gz] = 0;
                            continue;
                        }

                        isWaterSurface[gx][gz] = true;
                        isNaturalBeachSeed[gx][gz] = false;
                        cachedBiomeColor[gx][gz] = BiomeColors.getAverageWaterColor(level, probe);

                        // Sample ambient sky and block light factor at water surface (Y = 63)
                        cachedSkyLight[gx][gz] = (byte) level.getBrightness(LightLayer.SKY, probe);
                        cachedBlockLight[gx][gz] = (byte) level.getBrightness(LightLayer.BLOCK, probe);

                        // Ocean & Deep Ocean 1.25x swell boost (1.0x in coastal/rivers)
                        var biomeHolder = level.getBiome(probe);
                        boolean isOcean = biomeHolder.is(BiomeTags.IS_OCEAN) || biomeHolder.is(BiomeTags.IS_DEEP_OCEAN);
                        cachedBiomeMultiplier[gx][gz] = isOcean ? 1.25f : 1.0f;

                        // Check if this water column sits over a shallow shoaling seabed (Y=61, 60, or 59)
                        boolean shallow = false;
                        for (int depthY = 61; depthY >= 59; depthY--) {
                            probe.set(wx, depthY, wz);
                            BlockState bedState = level.getBlockState(probe);
                            if (!bedState.isAir() && !level.getFluidState(probe).is(FluidTags.WATER)) {
                                shallow = isNaturalShoreBlock(bedState);
                                break;
                            }
                        }
                        isShallowWater[gx][gz] = shallow;
                    }
                }
            }
        }

        // Compute Euclidean Distance-to-Natural-Beach field (Chamfer propagation +
        // smoothing)
        for (int gx = 0; gx < cachedGridDim; gx++) {
            for (int gz = 0; gz < cachedGridDim; gz++) {
                if (!isWaterSurface[gx][gz]) {
                    cachedShoreDist[gx][gz] = 99.0f;
                    continue;
                }
                float minDist = 99.0f;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0)
                            continue;
                        int nx = gx + dx;
                        int nz = gz + dz;
                        if (nx >= 0 && nx < cachedGridDim && nz >= 0 && nz < cachedGridDim) {
                            if (isNaturalBeachSeed[nx][nz]) {
                                float step = (dx != 0 && dz != 0) ? 1.4142f : 1.0f;
                                if (step < minDist) {
                                    minDist = step;
                                }
                            }
                        }
                    }
                }
                cachedShoreDist[gx][gz] = minDist;
            }
        }

        // Forward & Backward Chamfer Euclidean sweeps (eliminates 45/90-degree
        // Chebyshev square corners)
        for (int pass = 0; pass < 2; pass++) {
            for (int gx = 1; gx < cachedGridDim - 1; gx++) {
                for (int gz = 1; gz < cachedGridDim - 1; gz++) {
                    if (!isWaterSurface[gx][gz] || !isShallowWater[gx][gz])
                        continue;
                    float d = cachedShoreDist[gx][gz];
                    d = Math.min(d, cachedShoreDist[gx - 1][gz] + 1.0f);
                    d = Math.min(d, cachedShoreDist[gx][gz - 1] + 1.0f);
                    d = Math.min(d, cachedShoreDist[gx - 1][gz - 1] + 1.4142f);
                    d = Math.min(d, cachedShoreDist[gx - 1][gz + 1] + 1.4142f);
                    cachedShoreDist[gx][gz] = Math.min(d, 99.0f);
                }
            }
            for (int gx = cachedGridDim - 2; gx >= 1; gx--) {
                for (int gz = cachedGridDim - 2; gz >= 1; gz--) {
                    if (!isWaterSurface[gx][gz] || !isShallowWater[gx][gz])
                        continue;
                    float d = cachedShoreDist[gx][gz];
                    d = Math.min(d, cachedShoreDist[gx + 1][gz] + 1.0f);
                    d = Math.min(d, cachedShoreDist[gx][gz + 1] + 1.0f);
                    d = Math.min(d, cachedShoreDist[gx + 1][gz + 1] + 1.4142f);
                    d = Math.min(d, cachedShoreDist[gx + 1][gz - 1] + 1.4142f);
                    cachedShoreDist[gx][gz] = Math.min(d, 99.0f);
                }
            }
        }
    }

    /**
     * Evaluates the segmented, crisp white beach-surf foam intensity at any
     * fractional world coordinate (wx, wz)
     * and smoothed Euclidean shore distance (sDist).
     * Returns 0.0f outside active surf ribbons, or [0.0..1.0] inside a rolling
     * crescent breaker.
     */
    private static float evaluateBeachSurfRibbon(double wx, double wz, float sDist, double renderTime) {
        if (sDist < 0.9f || sDist > 4.0f) {
            return 0.0f;
        }

        // 1. Along-shore segmentation into natural crescent wave sets (4-10m patches
        // with clear water gaps)
        double setNoise = Math.sin(wx * 0.14 + wz * 0.11 + renderTime * 0.22)
                + Math.cos(wx * 0.09 - wz * 0.16 - renderTime * 0.17) * 0.85;
        float waveSetMask = Mth.clamp((float) ((setNoise - 0.15) / 0.95), 0.0f, 1.0f);
        if (waveSetMask <= 0.01f) {
            return 0.0f;
        }
        // Smoothstep the along-shore crescent tips so each ribbon tapers into a thin point
        waveSetMask = waveSetMask * waveSetMask * (3.0f - 2.0f * waveSetMask);

        // 2. Shoreward phase propagation (waves roll toward sDist -> 0.9)
        double curveWarp = Math.sin(wx * 0.21 + wz * 0.19) * 0.75;
        // Center of the crest travels from sDist = 3.9 down to sDist = 0.95
        double cycle = (renderTime * 0.38 + curveWarp * 0.15) % 1.0;
        if (cycle < 0.0)
            cycle += 1.0;
        float crestCenterDist = 3.9f - (float) cycle * 2.95f;

        // Asymmetric cross-shore ribbon profile: sharp leading shoreward edge, short frothy trail behind
        float distFromCrest = sDist - crestCenterDist;
        float ribbonHalfWidth = 0.34f * (0.55f + 0.45f * waveSetMask);

        float profile;
        if (distFromCrest < 0.0f) {
            // Leading edge (closer to beach): very crisp front
            float norm = -distFromCrest / (ribbonHalfWidth * 0.65f);
            if (norm > 1.0f)
                return 0.0f;
            profile = 1.0f - norm * norm;
        } else {
            // Trailing edge (seaward side): rapid frothy taper
            float norm = distFromCrest / (ribbonHalfWidth * 1.15f);
            if (norm > 1.0f)
                return 0.0f;
            profile = (1.0f - norm) * (1.0f - norm);
        }

        // 3. Distance envelope: fade in smoothly offshore (3.2..4.0) and wash out on sand (0.9..1.4)
        float distEnvelope = 1.0f;
        if (sDist < 1.4f) {
            distEnvelope = Mth.clamp((sDist - 0.9f) / 0.50f, 0.0f, 1.0f);
        } else if (sDist > 3.2f) {
            distEnvelope = Mth.clamp((4.0f - sDist) / 0.80f, 0.0f, 1.0f);
        }

        // Secondary staggered wave train so neighboring beach segments have offset timing
        float secondaryCycle = (float) ((renderTime * 0.38 + 0.5 + Math.cos(wx * 0.12 - wz * 0.14) * 0.18) % 1.0);
        if (secondaryCycle < 0.0f)
            secondaryCycle += 1.0f;
        float crest2Dist = 3.7f - secondaryCycle * 2.7f;
        float d2 = Math.abs(sDist - crest2Dist) / (ribbonHalfWidth * 0.85f);
        float profile2 = d2 < 1.0f ? (1.0f - d2 * d2) * (1.0f - waveSetMask * 0.5f) : 0.0f;

        float combined = Math.max(profile * waveSetMask, profile2 * waveSetMask * 0.85f) * distEnvelope;
        return Mth.clamp(combined, 0.0f, 1.0f);
    }

    /**
     * Renders connected 3D polygonal Gerstner wave quads using an isolated
     * Tesselator pass,
     * plus crisp detached white beach-surf ribbons along natural coastlines.
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        if (isHoldOcclusionStage(event.getStage())) {
            renderEnclosedHoldDepthOcclusion(
                    event.getPoseStack().last().pose(),
                    event.getCamera().getPosition(),
                    partialTick,
                    event.getStage()
            );
            return;
        }
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

        double renderTime = (gameTime + partialTick) * 0.05;

        // 3 High-Frequency visual chop harmonics (lambda ~ 11m, 5.8m, and cross-chop at 3.4m)
        double windAngle = Math.atan2(windDirZ, windDirX);
        double angle1 = windAngle + 0.30; // ~ +18 deg
        double angle2 = windAngle - 0.40; // ~ -23 deg
        double angle3 = windAngle + 0.733; // ~ +42 deg
        double dirX1 = Math.cos(angle1), dirZ1 = Math.sin(angle1);
        double dirX2 = Math.cos(angle2), dirZ2 = Math.sin(angle2);
        double dirX3 = Math.cos(angle3), dirZ3 = Math.sin(angle3);

        double activeWaveScale = GerstnerWaveEngine.getActiveWaveScale(stormIntensity);
        double stormScale = stormIntensity > 0.4 ? (1.0 + (stormIntensity - 0.4) * 0.8) : 1.0;
        double amp1 = (0.12 * stormIntensity + 0.035) * stormScale * activeWaveScale;
        double amp2 = (0.07 * stormIntensity + 0.020) * stormScale * activeWaveScale;
        double amp3 = (stormIntensity > 0.25 ? (0.055 * stormIntensity + 0.015) : 0.0) * stormScale * activeWaveScale;

        float elevationBoost = (0.18f + (float) (0.22 * stormIntensity)) * (float) activeWaveScale;
        float interiorBaseY = VANILLA_WATER_TOP_Y + elevationBoost;
        float safeFloor = VANILLA_WATER_TOP_Y + 0.015f;

        // --- Sable Sub-Level Hull Masking & Wave Attenuation ---
        List<MaskedCellInfo> maskedCells = new ArrayList<>();
        boolean[][] maskedByShipHull = new boolean[cachedGridDim][cachedGridDim];

        maskWaveGridByHoldEnvelopes(
                maskedByShipHull,
                startX,
                startZ,
                CELL_STEP,
                camPos,
                partialTick
        );

        for (int gx = 0; gx < cachedGridDim; gx++) {
            for (int gz = 0; gz < cachedGridDim; gz++) {
                if (maskedByShipHull[gx][gz]) {
                    maskedCells.add(new MaskedCellInfo(gx, gz, PINNED_WATER_Y + 0.05f));
                }
            }
        }

        int vertDim = cachedGridDim + 1;

        // Boundary Vertex Pinning: A corner vertex is interior to open ocean if all 4 adjacent
        // 1x1 cells are open water in the world terrain (excluding shorelines and docks).
        boolean[][] isWorldWaterInterior = new boolean[vertDim][vertDim];
        for (int vi = 1; vi < cachedGridDim; vi++) {
            for (int vj = 1; vj < cachedGridDim; vj++) {
                if (isWaterSurface[vi - 1][vj - 1]
                        && isWaterSurface[vi - 1][vj]
                        && isWaterSurface[vi][vj - 1]
                        && isWaterSurface[vi][vj]) {
                    isWorldWaterInterior[vi][vj] = true;
                }
            }
        }

        // Compute distance field to nearest contacting masked hull cell
        float[][] vertDistToMaskedHull = new float[vertDim][vertDim];
        float[][] vertEdgeWaterlineY = new float[vertDim][vertDim];
        float[][] rawVertHullFeather = new float[vertDim][vertDim];

        for (int vi = 0; vi < vertDim; vi++) {
            Arrays.fill(vertDistToMaskedHull[vi], 99.0f);
            Arrays.fill(vertEdgeWaterlineY[vi], PINNED_WATER_Y + 0.05f);
            Arrays.fill(rawVertHullFeather[vi], 1.0f);
        }

        if (!maskedCells.isEmpty()) {
            int minMaskedGX = Integer.MAX_VALUE, maxMaskedGX = Integer.MIN_VALUE;
            int minMaskedGZ = Integer.MAX_VALUE, maxMaskedGZ = Integer.MIN_VALUE;
            for (int m = 0; m < maskedCells.size(); m++) {
                MaskedCellInfo info = maskedCells.get(m);
                if (info.gx < minMaskedGX) minMaskedGX = info.gx;
                if (info.gx > maxMaskedGX) maxMaskedGX = info.gx;
                if (info.gz < minMaskedGZ) minMaskedGZ = info.gz;
                if (info.gz > maxMaskedGZ) maxMaskedGZ = info.gz;
            }

            int checkMinVI = Math.max(0, minMaskedGX - 5);
            int checkMaxVI = Math.min(vertDim - 1, maxMaskedGX + 6);
            int checkMinVJ = Math.max(0, minMaskedGZ - 5);
            int checkMaxVJ = Math.min(vertDim - 1, maxMaskedGZ + 6);

            double maxDistSq = 4.5 * 4.5 + 0.001;

            for (int vi = checkMinVI; vi <= checkMaxVI; vi++) {
                for (int vj = checkMinVJ; vj <= checkMaxVJ; vj++) {
                    double minDistSq = maxDistSq;
                    float nearestEdgeY = PINNED_WATER_Y + 0.05f;

                    for (int m = 0; m < maskedCells.size(); m++) {
                        MaskedCellInfo info = maskedCells.get(m);
                        int cx = info.gx;
                        int cz = info.gz;
                        double dx = (vi < cx) ? (cx - vi) : ((vi > cx + 1) ? (vi - (cx + 1)) : 0.0);
                        double dz = (vj < cz) ? (cz - vj) : ((vj > cz + 1) ? (vj - (cz + 1)) : 0.0);
                        double distSq = dx * dx + dz * dz;
                        if (distSq < minDistSq) {
                            minDistSq = distSq;
                            nearestEdgeY = info.edgeWaterlineY;
                        }
                    }

                    if (minDistSq < maxDistSq) {
                        float dist = (float) Math.sqrt(minDistSq);
                        vertDistToMaskedHull[vi][vj] = dist;
                        vertEdgeWaterlineY[vi][vj] = nearestEdgeY;
                        rawVertHullFeather[vi][vj] = Mth.clamp(dist / 4.5f, 0.0f, 1.0f);
                    }
                }
            }
        }

        // 3x3 spatial smoothing filter over vertHullFeather to eliminate mesh tears around ships
        float[][] vertHullFeather = new float[vertDim][vertDim];
        for (int vi = 0; vi < vertDim; vi++) {
            for (int vj = 0; vj < vertDim; vj++) {
                if (rawVertHullFeather[vi][vj] >= 0.999f) {
                    vertHullFeather[vi][vj] = 1.0f;
                    continue;
                }
                float sum = 0.0f;
                float weightSum = 0.0f;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int ni = Mth.clamp(vi + di, 0, vertDim - 1);
                        int nj = Mth.clamp(vj + dj, 0, vertDim - 1);
                        float w = (di == 0 && dj == 0) ? 2.0f : 1.0f;
                        sum += rawVertHullFeather[ni][nj] * w;
                        weightSum += w;
                    }
                }
                vertHullFeather[vi][vj] = weightSum > 0.0f ? (sum / weightSum) : rawVertHullFeather[vi][vj];
            }
        }

        // Sample ambient sky and block light at water surface (Y = 63) to match vanilla water lighting
        // Compute true client-side sky brightness (1.0f at noon, 0.20f at midnight)
        float clientSkyBrightness = (level instanceof net.minecraft.client.multiplayer.ClientLevel cl)
                ? cl.getSkyDarken(partialTick)
                : (1.0f - level.getRainLevel(partialTick) * (5.0f / 16.0f)) * (1.0f - level.getThunderLevel(partialTick) * (5.0f / 16.0f));

        // Vanilla water drops to ~0.12f - 0.16f at midnight
        float skySunFactor = 0.14f + 0.86f * Mth.clamp((clientSkyBrightness - 0.20f) / 0.80f, 0.0f, 1.0f);

        float[][] cellLightFactor = new float[cachedGridDim][cachedGridDim];
        for (int gx = 0; gx < cachedGridDim; gx++) {
            for (int gz = 0; gz < cachedGridDim; gz++) {
                int sky = cachedSkyLight[gx][gz] & 0xFF;
                int block = cachedBlockLight[gx][gz] & 0xFF;
                float blockFactor = LightTexture.getBrightness(level.dimensionType(), block);
                float cellSky = skySunFactor * (sky / 15.0f);
                float cellBright = Math.max(blockFactor, cellSky);
                cellLightFactor[gx][gz] = Mth.clamp(cellBright, 0.0f, 1.0f);
            }
        }

        // Smoothly interpolate vertex shore distance from adjacent water cells (2-pass
        // smoothed for organic curves)
        float[][] rawVertShoreDist = new float[vertDim][vertDim];
        for (int vi = 0; vi < vertDim; vi++) {
            for (int vj = 0; vj < vertDim; vj++) {
                float sum = 0.0f;
                int count = 0;
                boolean nearNaturalSeed = false;
                for (int di = -1; di <= 0; di++) {
                    for (int dj = -1; dj <= 0; dj++) {
                        int ci = vi + di;
                        int cj = vj + dj;
                        if (ci >= 0 && ci < cachedGridDim && cj >= 0 && cj < cachedGridDim) {
                            if (isNaturalBeachSeed[ci][cj]) {
                                nearNaturalSeed = true;
                            }
                            if (isWaterSurface[ci][cj] && cachedShoreDist[ci][cj] < 50.0f) {
                                sum += cachedShoreDist[ci][cj];
                                count++;
                            }
                        }
                    }
                }
                if (nearNaturalSeed) {
                    rawVertShoreDist[vi][vj] = 0.35f;
                } else if (count > 0) {
                    rawVertShoreDist[vi][vj] = sum / count;
                } else {
                    rawVertShoreDist[vi][vj] = 99.0f;
                }
            }
        }

        // 3x3 spatial box filter over vertex shore distance so surf ribbons curve
        // organically without grid stair-stepping
        float[][] vertShoreDist = new float[vertDim][vertDim];
        for (int vi = 0; vi < vertDim; vi++) {
            for (int vj = 0; vj < vertDim; vj++) {
                if (rawVertShoreDist[vi][vj] > 12.0f) {
                    vertShoreDist[vi][vj] = 99.0f;
                    continue;
                }
                float sum = 0.0f;
                float weightSum = 0.0f;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int ni = Mth.clamp(vi + di, 0, vertDim - 1);
                        int nj = Mth.clamp(vj + dj, 0, vertDim - 1);
                        float d = rawVertShoreDist[ni][nj];
                        if (d < 20.0f) {
                            float w = (di == 0 && dj == 0) ? 2.0f : 1.0f;
                            sum += d * w;
                            weightSum += w;
                        }
                    }
                }
                vertShoreDist[vi][vj] = weightSum > 0.0f ? (sum / weightSum) : 99.0f;
            }
        }

        float[][] waveOffset = new float[vertDim][vertDim];
        float[][] vertFinalY = new float[vertDim][vertDim];
        float[][] vertLight = new float[vertDim][vertDim];

        for (int vi = 0; vi < vertDim; vi++) {
            double vx = startX + vi * CELL_STEP;
            for (int vj = 0; vj < vertDim; vj++) {
                double vz = startZ + vj * CELL_STEP;

                // Vertex ambient light factor
                float l00 = (vi > 0 && vj > 0) ? cellLightFactor[vi - 1][vj - 1] : 1.0f;
                float l01 = (vi > 0 && vj < cachedGridDim) ? cellLightFactor[vi - 1][vj] : 1.0f;
                float l10 = (vi < cachedGridDim && vj > 0) ? cellLightFactor[vi][vj - 1] : 1.0f;
                float l11 = (vi < cachedGridDim && vj < cachedGridDim) ? cellLightFactor[vi][vj] : 1.0f;
                vertLight[vi][vj] = (l00 + l01 + l10 + l11) * 0.25f;

                // Un-clamped continuous Gerstner + Trochoidal wave displacement for EVERY vertex
                double baseWave = GerstnerWaveEngine.evaluateWaveOffset(
                        vx, vz, renderTime, stormIntensity, windDirX, windDirZ);
                double chop1 = Math.sin(0.5712 * (dirX1 * vx + dirZ1 * vz) - 2.367 * renderTime) * amp1;
                double chop2 = Math.sin(1.0833 * (dirX2 * vx + dirZ2 * vz) - 3.259 * renderTime) * amp2;
                double chop3 = Math.sin(1.8480 * (dirX3 * vx + dirZ3 * vz) - 4.150 * renderTime) * amp3;
                double h = baseWave + chop1 + chop2 + chop3;

                // Smoothly interpolate Ocean & Deep Ocean 1.25x swell boost from adjacent cells
                float b00 = (vi > 0 && vj > 0) ? cachedBiomeMultiplier[vi - 1][vj - 1] : 1.0f;
                float b01 = (vi > 0 && vj < cachedGridDim) ? cachedBiomeMultiplier[vi - 1][vj] : 1.0f;
                float b10 = (vi < cachedGridDim && vj > 0) ? cachedBiomeMultiplier[vi][vj - 1] : 1.0f;
                float b11 = (vi < cachedGridDim && vj < cachedGridDim) ? cachedBiomeMultiplier[vi][vj] : 1.0f;
                float biomeMult = (b00 + b01 + b10 + b11) * 0.25f;
                h *= biomeMult;

                // Subtle physical wave crest lift inside active beach breakers
                float sDist = vertShoreDist[vi][vj];
                if (sDist <= 4.4f) {
                    float surfRibbon = evaluateBeachSurfRibbon(vx, vz, sDist, renderTime);
                    h += 0.065f * surfRibbon;
                }

                // Raw un-clamped waveOffset populated for EVERY vertex for smooth tear-free slope normals
                waveOffset[vi][vj] = (float) h;

                if (!isWorldWaterInterior[vi][vj]) {
                    vertFinalY[vi][vj] = PINNED_WATER_Y;
                    continue; // Pinned boundary vertex on overworld shoreline/dock
                }

                // Smooth shoreline taper: tapers both elevation boost and wave displacement to 0 at shore
                float shoreTaper = Mth.clamp((sDist - 0.5f) / 5.5f, 0.0f, 1.0f);
                shoreTaper = shoreTaper * shoreTaper * (3.0f - 2.0f * shoreTaper); // smoothstep

                float taperedBoost = elevationBoost * shoreTaper;
                float taperedH = (float) h * shoreTaper;
                // Gently attenuate extreme wave crest spikes by at most 30% near vessels while maintaining natural base elevation
                float smoothHullFeather = vertHullFeather[vi][vj];
                if (smoothHullFeather < 1.0f) {
                    taperedH *= Mth.lerp(smoothHullFeather, 0.70f, 1.0f);
                }
                float openWaterY = Math.max(safeFloor, VANILLA_WATER_TOP_Y + taperedBoost + taperedH);

                vertFinalY[vi][vj] = openWaterY;
            }
        }

        // Texture sprite for animated native water - safe normalized interpolation with 1.5% margin
        // to eliminate texture-atlas edge bleeding
        TextureAtlasSprite waterSprite = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.withDefaultNamespace("block/water_still"));
        float rawU0 = waterSprite.getU0();
        float rawU1 = waterSprite.getU1();
        float rawV0 = waterSprite.getV0();
        float rawV1 = waterSprite.getV1();
        float uMargin = (rawU1 - rawU0) * 0.015f;
        float vMargin = (rawV1 - rawV0) * 0.015f;
        float u0 = rawU0 + uMargin;
        float u1 = rawU1 - uMargin;
        float v0 = rawV0 + vMargin;
        float v1 = rawV1 - vMargin;

        // Texture sprite for surf foam overlay - sample pure white from snow sprite so foam is 100% white
        TextureAtlasSprite snowSprite = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.withDefaultNamespace("block/snow"));
        float foamUMid = (snowSprite.getU0() + snowSprite.getU1()) * 0.5f;
        float foamVMid = (snowSprite.getV0() + snowSprite.getV1()) * 0.5f;

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        try {
            Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        // Directional light vector for wave slope shading
        float lightDirX = (float) windDirX * 0.5f - 0.25f;
        float lightDirY = 0.80f;
        float lightDirZ = (float) windDirZ * 0.5f - 0.25f;
        float lLen = Mth.sqrt(lightDirX * lightDirX + lightDirY * lightDirY + lightDirZ * lightDirZ);
        lightDirX /= lLen;
        lightDirY /= lLen;
        lightDirZ /= lLen;

        for (int gx = 0; gx < cachedGridDim; gx++) {
            for (int gz = 0; gz < cachedGridDim; gz++) {
                if (!isWaterSurface[gx][gz] || maskedByShipHull[gx][gz]) {
                    continue; // Skip dry land and interior/sublevel ship holds/rafts!
                }

                float h00 = waveOffset[gx][gz];
                float h01 = waveOffset[gx][gz + 1];
                float h11 = waveOffset[gx + 1][gz + 1];
                float h10 = waveOffset[gx + 1][gz];

                double wx0 = startX + gx * CELL_STEP;
                double wx1 = wx0 + CELL_STEP;
                double wz0 = startZ + gz * CELL_STEP;
                double wz1 = wz0 + CELL_STEP;

                boolean int00 = isWorldWaterInterior[gx][gz];
                boolean int01 = isWorldWaterInterior[gx][gz + 1];
                boolean int11 = isWorldWaterInterior[gx + 1][gz + 1];
                boolean int10 = isWorldWaterInterior[gx + 1][gz];

                float y00 = int00 ? vertFinalY[gx][gz] : PINNED_WATER_Y;
                float y01 = int01 ? vertFinalY[gx][gz + 1] : PINNED_WATER_Y;
                float y11 = int11 ? vertFinalY[gx + 1][gz + 1] : PINNED_WATER_Y;
                float y10 = int10 ? vertFinalY[gx + 1][gz] : PINNED_WATER_Y;

                int biomeColor = cachedBiomeColor[gx][gz];
                int br = (biomeColor >> 16) & 0xFF;
                int bg = (biomeColor >> 8) & 0xFF;
                int bb = biomeColor & 0xFF;

                // 1. Base Ocean Surface Quad (clean ocean water color with night light & hull feather)
                int c00 = computeBaseWaterColor(int00, h00, br, bg, bb,
                        lightDirX, lightDirY, lightDirZ, wx0, wz0, camPos.x, camPos.z, stormIntensity,
                        gx, gz, waveOffset, vertLight[gx][gz], vertHullFeather[gx][gz], clientSkyBrightness);
                int c01 = computeBaseWaterColor(int01, h01, br, bg, bb,
                        lightDirX, lightDirY, lightDirZ, wx0, wz1, camPos.x, camPos.z, stormIntensity,
                        gx, gz + 1, waveOffset, vertLight[gx][gz + 1], vertHullFeather[gx][gz + 1], clientSkyBrightness);
                int c11 = computeBaseWaterColor(int11, h11, br, bg, bb,
                        lightDirX, lightDirY, lightDirZ, wx1, wz1, camPos.x, camPos.z, stormIntensity,
                        gx + 1, gz + 1, waveOffset, vertLight[gx + 1][gz + 1], vertHullFeather[gx + 1][gz + 1], clientSkyBrightness);
                int c10 = computeBaseWaterColor(int10, h10, br, bg, bb,
                        lightDirX, lightDirY, lightDirZ, wx1, wz0, camPos.x, camPos.z, stormIntensity,
                        gx + 1, gz, waveOffset, vertLight[gx + 1][gz], vertHullFeather[gx + 1][gz], clientSkyBrightness);

                if (((c00 | c01 | c11 | c10) >>> 24) == 0) {
                    continue;
                }

                int a00 = (c00 >> 24) & 0xFF, r00 = (c00 >> 16) & 0xFF, g00 = (c00 >> 8) & 0xFF, b00 = c00 & 0xFF;
                int a01 = (c01 >> 24) & 0xFF, r01 = (c01 >> 16) & 0xFF, g01 = (c01 >> 8) & 0xFF, b01 = c01 & 0xFF;
                int a11 = (c11 >> 24) & 0xFF, r11 = (c11 >> 16) & 0xFF, g11 = (c11 >> 8) & 0xFF, b11 = c11 & 0xFF;
                int a10 = (c10 >> 24) & 0xFF, r10 = (c10 >> 16) & 0xFF, g10 = (c10 >> 8) & 0xFF, b10 = c10 & 0xFF;

                bufferBuilder
                        .addVertex(matrix, (float) (wx0 - camPos.x), (float) (y00 - camPos.y), (float) (wz0 - camPos.z))
                        .setUv(u0, v0).setColor(r00, g00, b00, a00);
                bufferBuilder
                        .addVertex(matrix, (float) (wx0 - camPos.x), (float) (y01 - camPos.y), (float) (wz1 - camPos.z))
                        .setUv(u0, v1).setColor(r01, g01, b01, a01);
                bufferBuilder
                        .addVertex(matrix, (float) (wx1 - camPos.x), (float) (y11 - camPos.y), (float) (wz1 - camPos.z))
                        .setUv(u1, v1).setColor(r11, g11, b11, a11);
                bufferBuilder
                        .addVertex(matrix, (float) (wx1 - camPos.x), (float) (y10 - camPos.y), (float) (wz0 - camPos.z))
                        .setUv(u1, v0).setColor(r10, g10, b10, a10);

                // 2. Detached High-Contrast Beach Surf Ribbon Overlay
                float sd00 = vertShoreDist[gx][gz];
                float sd01 = vertShoreDist[gx][gz + 1];
                float sd11 = vertShoreDist[gx + 1][gz + 1];
                float sd10 = vertShoreDist[gx + 1][gz];

                if (isShallowWater[gx][gz] && (sd00 <= 4.0f || sd01 <= 4.0f || sd11 <= 4.0f || sd10 <= 4.0f)) {
                    float distFromCam = (float) Math.hypot(wx0 - camPos.x, wz0 - camPos.z);
                    float distFade = (float) Mth.clamp((cachedRadius - distFromCam) / 16.0, 0.0, 1.0);
                    if (distFade > 0.02f) {
                        float l00 = vertLight[gx][gz];
                        float l01 = vertLight[gx][gz + 1];
                        float l11 = vertLight[gx + 1][gz + 1];
                        float l10 = vertLight[gx + 1][gz];

                        for (int sx = 0; sx < 2; sx++) {
                            float fx0 = sx * 0.5f;
                            float fx1 = (sx + 1) * 0.5f;
                            for (int sz = 0; sz < 2; sz++) {
                                float fz0 = sz * 0.5f;
                                float fz1 = (sz + 1) * 0.5f;

                                float f00 = evalSubVertexFoam(wx0, wz0, fx0, fz0, sd00, sd10, sd01, sd11, renderTime);
                                float f01 = evalSubVertexFoam(wx0, wz0, fx0, fz1, sd00, sd10, sd01, sd11, renderTime);
                                float f11 = evalSubVertexFoam(wx0, wz0, fx1, fz1, sd00, sd10, sd01, sd11, renderTime);
                                float f10 = evalSubVertexFoam(wx0, wz0, fx1, fz0, sd00, sd10, sd01, sd11, renderTime);

                                if (f00 <= 0.02f && f01 <= 0.02f && f11 <= 0.02f && f10 <= 0.02f) {
                                    continue;
                                }

                                float sy00 = bilerp(y00, y10, y01, y11, fx0, fz0) + 0.022f;
                                float sy01 = bilerp(y00, y10, y01, y11, fx0, fz1) + 0.022f;
                                float sy11 = bilerp(y00, y10, y01, y11, fx1, fz1) + 0.022f;
                                float sy10 = bilerp(y00, y10, y01, y11, fx1, fz0) + 0.022f;

                                double swx0 = wx0 + fx0;
                                double swx1 = wx0 + fx1;
                                double swz0 = wz0 + fz0;
                                double swz1 = wz0 + fz1;

                                float subLight00 = bilerp(l00, l10, l01, l11, fx0, fz0);
                                float subLight01 = bilerp(l00, l10, l01, l11, fx0, fz1);
                                float subLight11 = bilerp(l00, l10, l01, l11, fx1, fz1);
                                float subLight10 = bilerp(l00, l10, l01, l11, fx1, fz0);

                                float crisp00 = Mth.clamp((f00 - 0.12f) / 0.65f, 0.0f, 1.0f);
                                int fr00 = Mth.clamp((int) (248 * subLight00), 0, 255);
                                int fg00 = Mth.clamp((int) (252 * subLight00), 0, 255);
                                int fb00 = Mth.clamp((int) (255 * subLight00), 0, 255);
                                int fa00 = Mth.clamp((int) (crisp00 * 225.0f * distFade), 0, 255);

                                float crisp01 = Mth.clamp((f01 - 0.12f) / 0.65f, 0.0f, 1.0f);
                                int fr01 = Mth.clamp((int) (248 * subLight01), 0, 255);
                                int fg01 = Mth.clamp((int) (252 * subLight01), 0, 255);
                                int fb01 = Mth.clamp((int) (255 * subLight01), 0, 255);
                                int fa01 = Mth.clamp((int) (crisp01 * 225.0f * distFade), 0, 255);

                                float crisp11 = Mth.clamp((f11 - 0.12f) / 0.65f, 0.0f, 1.0f);
                                int fr11 = Mth.clamp((int) (248 * subLight11), 0, 255);
                                int fg11 = Mth.clamp((int) (252 * subLight11), 0, 255);
                                int fb11 = Mth.clamp((int) (255 * subLight11), 0, 255);
                                int fa11 = Mth.clamp((int) (crisp11 * 225.0f * distFade), 0, 255);

                                float crisp10 = Mth.clamp((f10 - 0.12f) / 0.65f, 0.0f, 1.0f);
                                int fr10 = Mth.clamp((int) (248 * subLight10), 0, 255);
                                int fg10 = Mth.clamp((int) (252 * subLight10), 0, 255);
                                int fb10 = Mth.clamp((int) (255 * subLight10), 0, 255);
                                int fa10 = Mth.clamp((int) (crisp10 * 225.0f * distFade), 0, 255);

                                bufferBuilder
                                        .addVertex(matrix, (float) (swx0 - camPos.x), (float) (sy00 - camPos.y),
                                                (float) (swz0 - camPos.z))
                                        .setUv(foamUMid, foamVMid).setColor(fr00, fg00, fb00, fa00);
                                bufferBuilder
                                        .addVertex(matrix, (float) (swx0 - camPos.x), (float) (sy01 - camPos.y),
                                                (float) (swz1 - camPos.z))
                                        .setUv(foamUMid, foamVMid).setColor(fr01, fg01, fb01, fa01);
                                bufferBuilder
                                        .addVertex(matrix, (float) (swx1 - camPos.x), (float) (sy11 - camPos.y),
                                                (float) (swz1 - camPos.z))
                                        .setUv(foamUMid, foamVMid).setColor(fr11, fg11, fb11, fa11);
                                bufferBuilder
                                        .addVertex(matrix, (float) (swx1 - camPos.x), (float) (sy10 - camPos.y),
                                                (float) (swz0 - camPos.z))
                                        .setUv(foamUMid, foamVMid).setColor(fr10, fg10, fb10, fa10);
                            }
                        }
                    }
                }
            }
        }

            MeshData meshData = bufferBuilder.build();
            if (meshData != null) {
                BufferUploader.drawWithShader(meshData);
            }
        } finally {
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            poseStack.popPose();
        }
    }

    private static float bilerp(float v00, float v10, float v01, float v11, float tx, float tz) {
        float vx0 = Mth.lerp(tx, v00, v10);
        float vx1 = Mth.lerp(tx, v01, v11);
        return Mth.lerp(tz, vx0, vx1);
    }

    private static float evalSubVertexFoam(double wx0, double wz0, float tx, float tz,
            float sd00, float sd10, float sd01, float sd11,
            double renderTime) {
        float sDist = bilerp(sd00, sd10, sd01, sd11, tx, tz);
        return evaluateBeachSurfRibbon(wx0 + tx, wz0 + tz, sDist, renderTime);
    }


    private static int computeBaseWaterColor(boolean isInterior, float h,
            int br, int bg, int bb,
            float lightDirX, float lightDirY, float lightDirZ,
            double wx, double wz, double camX, double camZ,
            double stormIntensity,
            int vi, int vj, float[][] waveOffset,
            float lightFactor, float hullFeather,
            float skyDarken) {
        float distFromCam = (float) Math.hypot(wx - camX, wz - camZ);
        float distFade = (float) Mth.clamp((cachedRadius - distFromCam) / 16.0, 0.0, 1.0);
        if (distFade <= 0.005f) {
            return 0;
        }

        if (!isInterior) {
            // Shoreline / Dock boundary vertex:
            // Biome water color multiplied by ambient lightFactor, smooth alpha (150 * distFade)
            int a = (int) (150.0f * distFade);
            int r = (int) Mth.clamp(br * lightFactor, 0, 255);
            int g = (int) Mth.clamp(bg * lightFactor, 0, 255);
            int b = (int) Mth.clamp(bb * lightFactor, 0, 255);
            return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        }

        // Compute local slope normal
        // FIX (Audit 106): Use (cachedGridDim + 1) - 1 = cachedGridDim as the max
        // vertex index for slope normal sampling. waveOffset is [vertDim][vertDim]
        // where vertDim = cachedGridDim + 1, so the last valid index is cachedGridDim.
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
        // At night or in darkness, directional slope highlight fades out so waves don't glow in pitch black
        float lightRatio = Mth.clamp(lightFactor * 1.5f, 0.0f, 1.0f);
        float rawSlope = Mth.clamp(0.85f + 0.30f * dot, 0.72f, 1.18f);
        float slopeFactor = Mth.lerp(lightRatio, 1.0f, rawSlope);

        // At night (skyDarken < 0.5f), reduce slopeFactor contrast to 1.0f (flat shading matching vanilla water)
        if (skyDarken < 0.5f) {
            float contrast = Mth.clamp((skyDarken - 0.20f) / 0.30f, 0.0f, 1.0f);
            slopeFactor = Mth.lerp(contrast, 1.0f, slopeFactor);
        }

        int r = (int) Mth.clamp(br * slopeFactor * lightFactor, 0, 255);
        int g = (int) Mth.clamp(bg * slopeFactor * lightFactor, 0, 255);
        int b = (int) Mth.clamp(bb * slopeFactor * lightFactor, 0, 255);

        // Alpha smoothly blends from 150 near hull boundaries (hullFeather -> 0) up to 185 in open ocean
        float baseAlpha = Mth.lerp(hullFeather, 150.0f, 185.0f);
        int a = (int) (baseAlpha * distFade);

        // Steep storm peak foam (only during active storms: stormIntensity > 0.5)
        if (stormIntensity > 0.5 && h > 0.36f) {
            float stormFoam = Mth.clamp((h - 0.36f) / 0.32f, 0.0f, 1.0f) * (float) ((stormIntensity - 0.5) / 0.5);
            r = (int) Mth.lerp(stormFoam, r, (int) (230 * lightFactor));
            g = (int) Mth.lerp(stormFoam, g, (int) (245 * lightFactor));
            b = (int) Mth.lerp(stormFoam, b, (int) (255 * lightFactor));
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
        if (stormIntensity < 0.10) {
            return;
        }

        double windDirX = waveState.smoothedWindDirX;
        double windDirZ = waveState.smoothedWindDirZ;
        RandomSource random = level.random;

        int gridStartX = cachedRadius > 0 ? (cachedCenterX - cachedSteps * CELL_STEP) : 0;
        int gridStartZ = cachedRadius > 0 ? (cachedCenterZ - cachedSteps * CELL_STEP) : 0;

        // --- Zone A & B: Shoreline Breaking Surf & Open-Ocean Whitecap Particles ---
        int sampleCount = stormIntensity > 0.5 ? 32 : 18;
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
            boolean shallowShoal = false;
            if (cachedGridDim > 0 && gx >= 0 && gx < cachedGridDim && gz >= 0 && gz < cachedGridDim
                    && isWaterSurface[gx][gz]) {
                sDist = cachedShoreDist[gx][gz];
                shallowShoal = isShallowWater[gx][gz];
            }

            // Zone A: Natural Beach Surf Particles (strictly on active crescent surf
            // ribbons, never around docks!)
            if (shallowShoal && sDist >= 0.8f && sDist <= 4.0f) {
                float surfStrength = evaluateBeachSurfRibbon(sampleX, sampleZ, sDist, timeSeconds);
                if (surfStrength > 0.65f && random.nextFloat() < 0.35f) {
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
                    double crestY = 62.93 + Math.max(0.0, baseWave + 0.06);

                    double vx = shoreDirX * 0.05 + (random.nextDouble() - 0.5) * 0.02;
                    double vy = 0.02 + random.nextDouble() * 0.03;
                    double vz = shoreDirZ * 0.05 + (random.nextDouble() - 0.5) * 0.02;

                    level.addParticle(ParticleTypes.SPLASH, sampleX, crestY, sampleZ, vx, vy, vz);

                    if (random.nextFloat() < 0.30f) {
                        level.addParticle(ParticleTypes.BUBBLE_POP, sampleX, crestY + 0.01, sampleZ,
                                vx * 0.4, 0.01, vz * 0.4);
                    }
                    continue;
                }
            }

            // Zone B: Open-Ocean Whitecap Crest Particles
            double waveOffset = GerstnerWaveEngine.evaluateWaveOffset(
                    sampleX, sampleZ, timeSeconds, stormIntensity, windDirX, windDirZ);
            double peakThreshold = stormIntensity > 0.5 ? 0.35 : 0.24;

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

        // --- Zone C: Ship Hull & Bow Wave Spray around moving ships or in wave crests
        // ---
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (!(container instanceof ClientSubLevelContainer clientContainer)) {
            return;
        }

        lastShipPos.keySet().removeIf(subLevel -> !clientContainer.getAllSubLevels().contains(subLevel));

        for (ClientSubLevel subLevel : clientContainer.getAllSubLevels()) {
            var bounds = subLevel.boundingBox();
            if (bounds == null) {
                continue;
            }

            double distSq = playerPos.distanceToSqr(
                    (bounds.minX() + bounds.maxX()) * 0.5,
                    (bounds.minY() + bounds.maxY()) * 0.5,
                    (bounds.minZ() + bounds.maxZ()) * 0.5);
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

                    double sprayVx = s * starboardWorld.x * spraySpeed + forwardWorld.x * (speed * 0.5)
                            + (random.nextDouble() - 0.5) * 0.04;
                    double sprayVy = 0.06 + Math.min(0.12, speed * 0.20) + random.nextDouble() * 0.08;
                    double sprayVz = s * starboardWorld.z * spraySpeed + forwardWorld.z * (speed * 0.5)
                            + (random.nextDouble() - 0.5) * 0.04;

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

    private record MaskedCellInfo(int gx, int gz, float edgeWaterlineY) {}

    // =========================================================================================
    //  TUNABLES
    // =========================================================================================

    /** Vanilla sea-level water surface is 62 + 8/9 = 62.8889; the barrier sits a hair above it. */
    private static final float PLANE_UPPER_Y = 62.895f;
    /** Barrier for the lower water layer; only emitted over columns whose floor lies below it. */
    private static final float PLANE_LOWER_Y = 61.895f;
    /**
     * Mirror plane just UNDER the vanilla surface. Vanilla draws the water underside too, and for a camera below
     * the waterline inside the hold (eye Y < 62.8889: standing on a low hold floor, crouching) that quad is nearer
     * than PLANE_UPPER_Y and passes the depth test. On: that is a normal situation in a brig. It is only emitted in
     * the last stage (before translucent water) so it cannot hide cutout blocks above the surface.
     */
    private static final boolean OCCLUDE_WATER_UNDERSIDE = true;
    private static final float PLANE_UNDERSIDE_Y = 62.8845f;
    /** Pulls the barrier a few depth steps towards the camera so it cannot z-fight vanilla water at range. */
    private static final boolean USE_POLYGON_OFFSET = true;

    /** Stages the barrier is emitted in. Drop the early ones if entities / chests below the plane vanish. */
    private static final Set<RenderLevelStageEvent.Stage> HOLD_OCCLUSION_STAGES = Set.of(
            RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS,
            RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS,
            RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES);

    // Hull classification, floors, walls and the scan itself live in com.serenesailing.hold.HoldScanner.
    // This class only draws the envelope the shared HoldRegistry applied.

    /** Stage that is the last one before vanilla draws translucent water; the only one the underside plane uses. */
    private static final RenderLevelStageEvent.Stage UNDERSIDE_STAGE = RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;

    /** Wave array sample position inside a cell: 0.5 = cell centre, 0.0 if the array is vertex-indexed. */
    private static final double WAVE_MASK_SAMPLE_OFFSET = 0.5;

    private static final int FLAG_UPPER = HoldScanner.FLAG_SEALED;
    private static final int FLAG_LOWER = HoldScanner.FLAG_LOWER;

    // =========================================================================================
    //  STATE (render thread only)
    // =========================================================================================

    @FunctionalInterface
    private interface RunSink {
        /** Parallelogram on the horizontal plane in world X/Z: O, O+U, O+U+V, O+V. */
        void accept(double ox, double oz, double ux, double uz, double vx, double vz);
    }

    /** Affine local(plot) -> world map of one sub-level for this frame, built from four pose transforms. */
    private static final class PoseBasis {
        int bx, by, bz;
        double ox, oy, oz;
        double exX, exY, exZ;
        double eyX, eyY, eyZ;
        double ezX, ezY, ezZ;

        /** @return false if the ship is tilted too far for a horizontal-plane approach to make sense. */
        boolean update(Pose3dc pose, int bx, int by, int bz, Vector3d tmp) {
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            Vector3d p = pose.transformPosition(tmp.set(bx, by, bz));
            ox = p.x;
            oy = p.y;
            oz = p.z;
            p = pose.transformPosition(tmp.set(bx + 1, by, bz));
            exX = p.x - ox;
            exY = p.y - oy;
            exZ = p.z - oz;
            p = pose.transformPosition(tmp.set(bx, by + 1, bz));
            eyX = p.x - ox;
            eyY = p.y - oy;
            eyZ = p.z - oz;
            p = pose.transformPosition(tmp.set(bx, by, bz + 1));
            ezX = p.x - ox;
            ezY = p.y - oy;
            ezZ = p.z - oz;
            final double upLen = Math.sqrt(eyX * eyX + eyY * eyY + eyZ * eyZ);
            return upLen > 1.0e-6 && eyY / upLen >= HoldScanner.MIN_UPRIGHT_COS;
        }

        double worldX(double lx, double ly, double lz) {
            return ox + (lx - bx) * exX + (ly - by) * eyX + (lz - bz) * ezX;
        }

        double worldY(double lx, double ly, double lz) {
            return oy + (lx - bx) * exY + (ly - by) * eyY + (lz - bz) * ezY;
        }

        double worldZ(double lx, double ly, double lz) {
            return oz + (lx - bx) * exZ + (ly - by) * eyZ + (lz - bz) * ezZ;
        }

        /** Local Y at which the column (lx, lz) crosses the given world height. */
        double localY(double worldY, double lx, double lz) {
            return by + (worldY - oy - (lx - bx) * exY - (lz - bz) * ezY) / eyY;
        }
    }

    /** One applied hold for this frame: the shared envelope plus this frame's render-pose basis. */
    private static final class RenderHold {
        final PoseBasis basis = new PoseBasis();
        HoldScanner.Envelope env;
        boolean sableCovers;
    }

    private static final Map<SubLevel, RenderHold> RENDER_HOLDS = new IdentityHashMap<>();
    private static final List<RenderHold> ACTIVE_HOLDS = new ArrayList<>();
    private static final Vector3d SCRATCH_VEC = new Vector3d();

    // =========================================================================================
    //  PUBLIC ENTRY POINTS
    // =========================================================================================

    public static boolean isHoldOcclusionStage(RenderLevelStageEvent.Stage stage) {
        return HOLD_OCCLUSION_STAGES.contains(stage);
    }

    /** Is this sub-level a registered (applied) sunken hold? */
    public static boolean isSunkenHold(ClientSubLevel subLevel) {
        final HoldScanner.Envelope env = HoldRegistry.activeEnvelope(subLevel);
        return env != null && env.isSunken();
    }

    /** Forget this renderer's per-frame state. Scans live in {@link HoldRegistry}, which resets itself on unload. */
    public static void invalidateHoldEnvelopes() {
        RENDER_HOLDS.clear();
        ACTIVE_HOLDS.clear();
    }

    /**
     * Emits the invisible depth-only barrier planes that keep vanilla chunk water out of sunken holds. Sable's own
     * water occlusion does the same job with a shader; these planes are the fallback and, in ALWAYS mode, a second
     * line of defence (see {@code holdDepthPlanes} in the client config).
     *
     * @param viewPose    matrix applied to the camera-relative vertices (same one your other draws use)
     * @param camPos      camera position, world space
     * @param partialTick partial tick handed to {@code ClientSubLevel#renderPose(float)}
     * @param stage       the render stage being drawn; the underside plane is only emitted in {@link #UNDERSIDE_STAGE}
     */
    public static void renderEnclosedHoldDepthOcclusion(Matrix4f viewPose, Vec3 camPos, float partialTick,
                                                        RenderLevelStageEvent.Stage stage) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        final SereneSailingClientConfig.HoldDepthPlaneMode mode = SereneSailingClientConfig.getHoldDepthPlaneMode();
        if (mode == SereneSailingClientConfig.HoldDepthPlaneMode.OFF) {
            return;
        }
        final List<RenderHold> holds = refreshActiveHolds(level, camPos, partialTick);
        if (holds.isEmpty()) {
            return;
        }
        final boolean underside = OCCLUDE_WATER_UNDERSIDE && stage == UNDERSIDE_STAGE;

        final BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        MeshData mesh = null;
        try {
            for (final RenderHold hold : holds) {
                if (mode == SereneSailingClientConfig.HoldDepthPlaneMode.FALLBACK_ONLY && hold.sableCovers) {
                    continue;
                }
                emitPlane(buffer, viewPose, camPos, hold, PLANE_UPPER_Y, FLAG_UPPER);
                emitPlane(buffer, viewPose, camPos, hold, PLANE_LOWER_Y, FLAG_LOWER);
                if (underside) {
                    emitPlane(buffer, viewPose, camPos, hold, PLANE_UNDERSIDE_Y, FLAG_UPPER);
                }
            }
        } finally {
            mesh = buffer.build(); // always finish the shared builder, null when nothing was emitted
        }
        if (mesh == null) {
            return;
        }

        try {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            if (USE_POLYGON_OFFSET) {
                RenderSystem.enablePolygonOffset();
                RenderSystem.polygonOffset(-1.0F, -10.0F);
            }
            BufferUploader.drawWithShader(mesh);
        } finally {
            if (USE_POLYGON_OFFSET) {
                RenderSystem.polygonOffset(0.0F, 0.0F);
                RenderSystem.disablePolygonOffset();
            }
            RenderSystem.enableCull();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
        }
    }

    /**
     * Marks every wave-grid cell inside a sunken hold's envelope so the Gerstner surface is not drawn there either
     * (Sable's shader patch does not cover this mesh, so this mask is always applied). Uses the same runs as the
     * depth barrier and the same envelope as Sable's region, so they can never disagree.
     * Grid cell (gx, gz) is sampled at origin + (index + 0.5) * cellSize (see WAVE_MASK_SAMPLE_OFFSET).
     */
    public static void maskWaveGridByHoldEnvelopes(boolean[][] maskedByShipHull, double gridOriginX,
                                                   double gridOriginZ, double cellSize, Vec3 camPos,
                                                   float partialTick) {
        if (maskedByShipHull == null || maskedByShipHull.length == 0 || cellSize <= 0.0) {
            return;
        }
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (final RenderHold hold : refreshActiveHolds(level, camPos, partialTick)) {
            forEachEligibleRun(hold, FLAG_UPPER, PLANE_UPPER_Y, (ox, oz, ux, uz, vx, vz) ->
                    rasterizeIntoMask(maskedByShipHull, gridOriginX, gridOriginZ, cellSize, ox, oz, ux, uz, vx, vz));
        }
    }

    // =========================================================================================
    //  ACTIVE HOLDS (read from the shared registry)
    // =========================================================================================

    private static List<RenderHold> refreshActiveHolds(ClientLevel level, Vec3 camPos, float partialTick) {
        ACTIVE_HOLDS.clear();
        final HoldRegistry registry = HoldRegistry.peek(level);
        if (registry == null || !registry.hasActiveHolds()) {
            RENDER_HOLDS.clear();
            return ACTIVE_HOLDS;
        }
        final boolean sableOn = SableWaterOcclusion.clientRendererEnabled();
        RENDER_HOLDS.keySet().removeIf(sub -> HoldRegistry.activeEnvelope(sub) == null);
        registry.forEachActive((sub, env) -> {
            if (!(sub instanceof ClientSubLevel client)) {
                return;
            }
            final RenderHold hold = RENDER_HOLDS.computeIfAbsent(sub, k -> new RenderHold());
            hold.env = env;
            hold.sableCovers = sableOn && registry.hasRegion(sub);
            final Pose3dc pose = client.renderPose(partialTick);
            if (!hold.basis.update(pose, env.minX(), env.minY(), env.minZ(), SCRATCH_VEC)) {
                return; // rolled over: draw nothing this frame
            }
            ACTIVE_HOLDS.add(hold);
        });
        return ACTIVE_HOLDS;
    }

    // =========================================================================================
    //  GEOMETRY
    // =========================================================================================

    /**
     * Walks every contiguous run of flagged cells in every valid row and reports it as a parallelogram on the
     * horizontal plane planeY. All runs share one reference height (local Y of the plane at the ship centre),
     * so shared edges are bit-identical and the barrier is watertight even when the ship pitches or rolls.
     * The plane stays horizontal in world space because the water it hides is.
     */
    private static void forEachEligibleRun(RenderHold hold, int flag, double planeY, RunSink sink) {
        final HoldScanner.Envelope env = hold.env;
        final PoseBasis b = hold.basis;
        final double ly = b.localY(planeY, env.centerX(), env.centerZ());
        for (int row = 0; row < env.depth(); row++) {
            final int port = env.portX(row);
            if (port < 0) {
                continue;
            }
            final int star = env.starboardX(row);
            final int lz = env.minZ() + row;
            int lx = port;
            while (lx <= star) {
                if ((env.flags(row, lx) & flag) == 0) {
                    lx++;
                    continue;
                }
                final int start = lx;
                while (lx <= star && (env.flags(row, lx) & flag) != 0) {
                    lx++;
                }
                // cells [start, lx) of row lz form one run
                final double ox = b.worldX(start, ly, lz);
                final double oz = b.worldZ(start, ly, lz);
                sink.accept(ox, oz,
                        b.worldX(lx, ly, lz) - ox, b.worldZ(lx, ly, lz) - oz,
                        b.worldX(start, ly, lz + 1) - ox, b.worldZ(start, ly, lz + 1) - oz);
            }
        }
    }

    private static void emitPlane(BufferBuilder buffer, Matrix4f viewPose, Vec3 cam, RenderHold hold,
                                  float planeY, int flag) {
        final float y = (float) (planeY - cam.y);
        forEachEligibleRun(hold, flag, planeY, (ox, oz, ux, uz, vx, vz) -> {
            addDepthVertex(buffer, viewPose, ox - cam.x, y, oz - cam.z);
            addDepthVertex(buffer, viewPose, ox + ux - cam.x, y, oz + uz - cam.z);
            addDepthVertex(buffer, viewPose, ox + ux + vx - cam.x, y, oz + uz + vz - cam.z);
            addDepthVertex(buffer, viewPose, ox + vx - cam.x, y, oz + vz - cam.z);
        });
    }

    /** Opaque white; the colour mask discards it, only depth is written. */
    private static void addDepthVertex(BufferBuilder buffer, Matrix4f viewPose, double x, float y, double z) {
        buffer.addVertex(viewPose, (float) x, y, (float) z).setColor(255, 255, 255, 255);
    }

    /** Marks grid cells whose sample point lies inside the parallelogram O + s*U + t*V, s,t in [0,1]. */
    private static void rasterizeIntoMask(boolean[][] mask, double originX, double originZ, double cell,
                                          double ox, double oz, double ux, double uz, double vx, double vz) {
        final double det = ux * vz - uz * vx;
        if (Math.abs(det) < 1.0e-9) {
            return;
        }
        final double minWX = ox + Math.min(0.0, ux) + Math.min(0.0, vx);
        final double maxWX = ox + Math.max(0.0, ux) + Math.max(0.0, vx);
        final double minWZ = oz + Math.min(0.0, uz) + Math.min(0.0, vz);
        final double maxWZ = oz + Math.max(0.0, uz) + Math.max(0.0, vz);

        final int gx0 = Math.max(0, (int) Math.floor((minWX - originX) / cell - WAVE_MASK_SAMPLE_OFFSET));
        final int gx1 = Math.min(mask.length - 1, (int) Math.ceil((maxWX - originX) / cell - WAVE_MASK_SAMPLE_OFFSET));
        for (int gx = gx0; gx <= gx1; gx++) {
            final boolean[] column = mask[gx];
            if (column == null || column.length == 0) {
                continue;
            }
            final int gz0 = Math.max(0, (int) Math.floor((minWZ - originZ) / cell - WAVE_MASK_SAMPLE_OFFSET));
            final int gz1 = Math.min(column.length - 1, (int) Math.ceil((maxWZ - originZ) / cell - WAVE_MASK_SAMPLE_OFFSET));
            final double dx = originX + (gx + WAVE_MASK_SAMPLE_OFFSET) * cell - ox;
            for (int gz = gz0; gz <= gz1; gz++) {
                final double dz = originZ + (gz + WAVE_MASK_SAMPLE_OFFSET) * cell - oz;
                final double s = (dx * vz - dz * vx) / det;
                final double t = (ux * dz - uz * dx) / det;
                if (s >= 0.0 && s <= 1.0 && t >= 0.0 && t <= 1.0) {
                    column[gz] = true;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onClientLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        lastShipPos.clear();
        invalidateHoldEnvelopes();
    }

    @SubscribeEvent
    public static void onClientLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            lastShipPos.clear();
            invalidateHoldEnvelopes();
        }
    }
}