package com.serenesailing.physics.waves;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.concurrent.ConcurrentHashMap;

/**
 * THE single source of truth for the height of the ocean surface.
 *
 * <p>{@code ClientWaveRenderer} (what you see), {@code ServerPhysicsHandler} (Sable hulls),
 * {@code WaveBuoyancyHandler} (entities) and the vanilla-fluid mixins (boats, swimmers, items) all
 * call into this class, on both logical sides, so that the mesh a player sees, the surface a boat floats
 * on and the water a swimmer is "in" are one and the same function of {@code (x, z, gameTime)}.
 *
 * <h3>Definition</h3>
 * <pre>
 *   surfaceY(x, z, t) = MEAN_WATER_Y + shape( (gerstner + chop)(x, z, t) * oceanMult(x, z) * shoreTaper(x, z) )
 * </pre>
 * where {@code t = level.getGameTime() + partialTick} (ticks) and {@code shape} is a smooth floor, see
 * {@link #shape(double)}.
 *
 * <h3>Why the floor</h3>
 * Vanilla still draws its own flat water top face at {@code 62.8889} and we cannot remove it without
 * leaving holes beyond the wave grid (chunk meshes are cached, the wave grid follows the camera). A true
 * trough would therefore be hidden behind the vanilla plane while a boat floated in the (invisible) trough.
 * Flooring the surface at the vanilla plane makes visuals, physics and the vanilla water blocks agree
 * everywhere: waves are crests rising out of the vanilla sea, never holes in it.
 */
public final class WaveSurface {

    /** Y of the water block row that forms the sea surface in the Overworld. */
    public static final int SEA_LEVEL_BLOCK_Y = 62;

    /**
     * Mean water plane = vanilla's water top (62 + 8/9 = 62.8889) plus a 0.016 m anti-z-fight bias. Depth-buffer
     * precision at 100+ blocks is a few centimetres, so a mesh sitting a millimetre above the vanilla face flickers
     * against it; the old renderer used the same 62.905 as its floor. Physics uses the identical datum, so the
     * 16 mm is simply part of "where the water is".
     */
    public static final double MEAN_WATER_Y = 62.905;

    /** Entities are only coupled to the waves within this Y band around sea level. */
    public static final double ZONE_BELOW = 6.0;
    public static final double ZONE_ABOVE = 8.0;

    /** Below this smoothed storm intensity the renderer draws no waves (and neither does physics). */
    public static final double MIN_STORM_INTENSITY = 0.04;

    private static final double SHAPE_K = 0.10;
    private static final double SHAPE_K2 = SHAPE_K * SHAPE_K;
    private static final int SHORE_PROBE_RANGE = 6;

    /** Shore taper depends only on world blocks, so it is cached per column (shared by the client and server). */
    private record TaperEntry(double taper, long expiresAtTick) {}
    private static final ConcurrentHashMap<Long, TaperEntry> TAPER_CACHE = new ConcurrentHashMap<>();
    private static final int TAPER_CACHE_TTL_TICKS = 40;
    private static final int TAPER_CACHE_MAX = 8192;

    private WaveSurface() {}

    // ------------------------------------------------------------------------------------------------
    // Pure math
    // ------------------------------------------------------------------------------------------------

    /**
     * Smooth, C1-continuous one-sided floor: ~0 for strongly negative {@code h}, ~{@code h} for positive.
     * Returns a value in {@code [~0, inf)} that is never below the vanilla plane.
     */
    public static double shape(double h) {
        return 0.5 * (h + Math.sqrt(h * h + SHAPE_K2));
    }

    /** Shore taper in [0,1] from a Euclidean distance (blocks) to the nearest natural beach. */
    public static double taperFromShoreDistance(double distance) {
        double t = Mth.clamp((distance - 0.5) / 5.5, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /** 1.25 in ocean biomes (bigger swell), 1.0 elsewhere. Identical on both logical sides. */
    public static float oceanMultiplier(Level level, BlockPos pos) {
        var biome = level.getBiome(pos);
        return (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)) ? 1.25f : 1.0f;
    }

    /** Natural coastal terrain (sand, dirt, stone, ...), as opposed to docks, planks and air cutouts. */
    public static boolean isNaturalShoreBlock(BlockState state) {
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

    // ------------------------------------------------------------------------------------------------
    // World queries (identical on client and server; unloaded chunks are never loaded)
    // ------------------------------------------------------------------------------------------------

    /**
     * Is this block column open sea surface? Mirrors the renderer's {@code isWaterSurface} test:
     * water in the sea-level row with air directly above. Docks/decks at Y=63 and land are excluded, which is
     * also where the renderer pins the mesh to the vanilla plane.
     */
    public static boolean isWaveColumn(Level level, int bx, int bz) {
        if (level == null || level.dimension() != Level.OVERWORLD) {
            return false;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(bx, SEA_LEVEL_BLOCK_Y, bz);
        if (!level.hasChunkAt(p) || !level.getFluidState(p).is(FluidTags.WATER)) {
            return false;
        }
        p.setY(SEA_LEVEL_BLOCK_Y + 1);
        return level.getBlockState(p).isAir();
    }

    /**
     * Approximates the renderer's cached shore-distance taper with 8 bounded ray marches (<= 48 lookups),
     * so physics can taper waves near natural beaches the way the mesh does without the client-only grid.
     */
    public static double shoreTaper(Level level, int bx, int bz) {
        long now = level.getGameTime();
        long key = BlockPos.asLong(bx, 0, bz);
        TaperEntry cached = TAPER_CACHE.get(key);
        if (cached != null && now < cached.expiresAtTick() && now >= cached.expiresAtTick() - TAPER_CACHE_TTL_TICKS - 5) {
            return cached.taper();
        }
        double taper = computeShoreTaper(level, bx, bz);
        if (TAPER_CACHE.size() > TAPER_CACHE_MAX) {
            TAPER_CACHE.clear();
        }
        TAPER_CACHE.put(key, new TaperEntry(taper, now + TAPER_CACHE_TTL_TICKS));
        return taper;
    }

    private static double computeShoreTaper(Level level, int bx, int bz) {
        double best = 99.0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                double step = (dx != 0 && dz != 0) ? 1.4142 : 1.0;
                for (int k = 1; k <= SHORE_PROBE_RANGE; k++) {
                    if (k * step >= best) {
                        break;
                    }
                    p.set(bx + dx * k, SEA_LEVEL_BLOCK_Y, bz + dz * k);
                    if (!level.hasChunkAt(p)) {
                        break;
                    }
                    BlockState bs = level.getBlockState(p);
                    if (!bs.getFluidState().is(FluidTags.WATER) && isNaturalShoreBlock(bs)) {
                        best = k * step;
                        break;
                    }
                }
            }
        }
        return taperFromShoreDistance(best);
    }

    // ------------------------------------------------------------------------------------------------
    // The shared surface function
    // ------------------------------------------------------------------------------------------------

    /** Unfloored, un-tapered displacement of the open sea (Gerstner + chop), meters. Pure function of its inputs. */
    public static double rawOffset(double x, double z, double timeTicks,
                                   double stormIntensity, double windDirX, double windDirZ) {
        double ts = timeTicks * 0.05;
        double base = GerstnerWaveEngine.evaluateWaveOffset(x, z, ts, stormIntensity, windDirX, windDirZ);
        double chop = new GerstnerWaveEngine.ChopField(stormIntensity, windDirX, windDirZ).offset(x, z, ts);
        return base + chop;
    }

    /**
     * Wave displacement above the mean plane [m] at {@code (x, z)} for tick-time {@code timeTicks}
     * (use {@code level.getGameTime() + partialTick}). Zero when the column is not open sea or waves are off.
     *
     * <p>Signature matches {@code ServerPhysicsHandler.WaveHeightHook}, so it is the default hook.
     */
    public static double offset(Level level, double x, double z, double timeTicks) {
        if (level == null || level.dimension() != Level.OVERWORLD) {
            return 0.0;
        }
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (!isWaveColumn(level, bx, bz)) {
            return 0.0;
        }
        GerstnerWaveEngine.DimensionWaveState state = GerstnerWaveEngine.getDimensionState(level);
        state.update(level, level.getGameTime()); // idempotent per tick
        double storm = state.smoothedStormIntensity;
        if (storm < MIN_STORM_INTENSITY) {
            return 0.0;
        }
        double h = rawOffset(x, z, timeTicks, storm, state.smoothedWindDirX, state.smoothedWindDirZ);
        h *= oceanMultiplier(level, new BlockPos(bx, SEA_LEVEL_BLOCK_Y + 1, bz));
        h *= shoreTaper(level, bx, bz);
        return shape(h);
    }

    /** Exact surface Y at {@code (x, z)}; {@code partialTick} in [0,1]. */
    public static double surfaceY(Level level, double x, double z, float partialTick) {
        return MEAN_WATER_Y + offset(level, x, z, level.getGameTime() + (double) partialTick);
    }

    /**
     * Surface normal at {@code (x, z)} (unit vector, +Y up) by central differences of the very same
     * function, so it is exact for chop, biome scaling, taper and floor alike.
     */
    public static Vector3d normal(Level level, double x, double z, float partialTick, Vector3d out) {
        final double e = 0.5;
        double t = level.getGameTime() + (double) partialTick;
        double dhdx = (offset(level, x + e, z, t) - offset(level, x - e, z, t)) / (2.0 * e);
        double dhdz = (offset(level, x, z + e, t) - offset(level, x, z - e, t)) / (2.0 * e);
        out.set(-dhdx, 1.0, -dhdz);
        return out.normalize();
    }

    // ------------------------------------------------------------------------------------------------
    // Entity coupling
    // ------------------------------------------------------------------------------------------------

    /**
     * Should wave buoyancy apply to this entity right now? Overworld, within the sea-level Y band,
     * over an open-sea column, and waves actually active. (Callers that must not turn an entity standing on a
     * Sable deck into a swimmer additionally check {@code onGround()}.)
     */
    public static boolean isEntityInWaveZone(Entity entity) {
        Level level = entity.level();
        if (level == null || level.dimension() != Level.OVERWORLD) {
            return false;
        }
        double y = entity.getY();
        if (y < SEA_LEVEL_BLOCK_Y - ZONE_BELOW || y > SEA_LEVEL_BLOCK_Y + ZONE_ABOVE) {
            return false;
        }
        if (GerstnerWaveEngine.getDimensionState(level).smoothedStormIntensity < MIN_STORM_INTENSITY) {
            return false;
        }
        return isWaveColumn(level, Mth.floor(entity.getX()), Mth.floor(entity.getZ()));
    }
}

