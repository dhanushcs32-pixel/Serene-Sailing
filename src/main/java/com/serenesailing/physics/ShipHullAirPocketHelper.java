package com.serenesailing.physics;

import com.serenesailing.SereneSailing;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3d;

import java.lang.reflect.Field;

/**
 * Prevents players and living entities from swimming, drowning, or experiencing
 * water drag when standing inside dry ship holds that extend below the ocean waterline (Y = 62.88).
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID)
public class ShipHullAirPocketHelper {

    private static final Field WAS_TOUCHING_WATER_FIELD;
    private static final Field WAS_EYE_IN_WATER_FIELD;

    static {
        Field fTouch = null;
        Field fEye = null;
        try {
            fTouch = Entity.class.getDeclaredField("wasTouchingWater");
            fTouch.setAccessible(true);
        } catch (Exception ignored) {
        }
        try {
            fEye = Entity.class.getDeclaredField("wasEyeInWater");
            fEye.setAccessible(true);
        } catch (Exception ignored) {
        }
        WAS_TOUCHING_WATER_FIELD = fTouch;
        WAS_EYE_IN_WATER_FIELD = fEye;
    }

    /**
     * Checks if a world position is strictly inside an assembled Sable ship's enclosed hold below ocean sea level.
     */
    public static boolean isEntityInDryShipHold(Level level, Vec3 worldPos) {
        if (level == null || worldPos == null) return false;
        if (worldPos.y > 63.2 || worldPos.y < 45.0) return false;

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return false;

        Vector3d worldProbe = new Vector3d();
        Vector3d localProbe = new Vector3d();
        BlockPos.MutableBlockPos subProbePos = new BlockPos.MutableBlockPos();

        double wx = worldPos.x;
        double wy = worldPos.y;
        double wz = worldPos.z;

        if (container instanceof ServerSubLevelContainer serverContainer) {
            for (ServerSubLevel subLevel : serverContainer.getAllSubLevels()) {
                if (checkSubLevelHold(subLevel, wx, wy, wz, worldProbe, localProbe, subProbePos)) {
                    return true;
                }
            }
        } else if (container instanceof ClientSubLevelContainer clientContainer) {
            for (ClientSubLevel subLevel : clientContainer.getAllSubLevels()) {
                if (checkSubLevelHold(subLevel, wx, wy, wz, worldProbe, localProbe, subProbePos)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean checkSubLevelHold(SubLevel subLevel, double wx, double wy, double wz,
                                             Vector3d worldProbe, Vector3d localProbe,
                                             BlockPos.MutableBlockPos subProbePos) {
        var bounds = subLevel.boundingBox();
        if (bounds == null) return false;

        if (wx < bounds.minX() - 1.0 || wx > bounds.maxX() + 1.0
                || wz < bounds.minZ() - 1.0 || wz > bounds.maxZ() + 1.0
                || wy < bounds.minY() - 1.0 || wy > bounds.maxY() + 1.0) {
            return false;
        }

        Pose3dc pose = subLevel.logicalPose();
        if (pose == null) return false;

        Level subWorld = subLevel.getLevel();
        if (subWorld == null) return false;

        // 1. Solid floor / keel below entity (search downward up to 7.0 blocks down to Y ~ 54.0)
        boolean hasFloor = false;
        for (double dy = 0.0; dy <= 7.0; dy += 0.5) {
            worldProbe.set(wx, wy - dy, wz);
            pose.transformPositionInverse(worldProbe, localProbe);
            subProbePos.set(Mth.floor(localProbe.x), Mth.floor(localProbe.y), Mth.floor(localProbe.z));
            if (isSolid(subWorld, subProbePos)) {
                hasFloor = true;
                break;
            }
        }
        if (!hasFloor) return false;

        // 2. Surrounded on 4 horizontal sides (Â±X, Â±Z) by hull blocks at entity's Y level / waterline
        worldProbe.set(wx, Math.max(wy + 0.5, 62.5), wz);
        pose.transformPositionInverse(worldProbe, localProbe);
        int lx = Mth.floor(localProbe.x);
        int ly = Mth.floor(localProbe.y);
        int lz = Mth.floor(localProbe.z);

        boolean wallNegX = false, wallPosX = false, wallNegZ = false, wallPosZ = false;
        for (int d = 1; d <= 16; d++) {
            if (!wallNegX && (isSolid(subWorld, subProbePos.set(lx - d, ly, lz))
                    || isSolid(subWorld, subProbePos.set(lx - d, ly + 1, lz)))) wallNegX = true;
            if (!wallPosX && (isSolid(subWorld, subProbePos.set(lx + d, ly, lz))
                    || isSolid(subWorld, subProbePos.set(lx + d, ly + 1, lz)))) wallPosX = true;
        }
        for (int d = 1; d <= 28; d++) {
            if (!wallNegZ && (isSolid(subWorld, subProbePos.set(lx, ly, lz - d))
                    || isSolid(subWorld, subProbePos.set(lx, ly + 1, lz - d)))) wallNegZ = true;
            if (!wallPosZ && (isSolid(subWorld, subProbePos.set(lx, ly, lz + d))
                    || isSolid(subWorld, subProbePos.set(lx, ly + 1, lz + d)))) wallPosZ = true;
        }

        return wallNegX && wallPosX && wallNegZ && wallPosZ;
    }

        /**
     * Checks if a local block position inside a sub-level is enclosed within a solid hull
     * using an arbitrary solid-block predicate.
     */
    public static boolean isInsideHullSpace(java.util.function.Predicate<BlockPos> solidChecker, BlockPos pos) {
        if (pos == null || solidChecker == null) return false;
        int lx = pos.getX();
        int ly = pos.getY();
        int lz = pos.getZ();

        // 1. Solid floor / keel below pos (searching downward up to 12 blocks)
        boolean hasFloor = false;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= 12; dy++) {
            probe.set(lx, ly - dy, lz);
            if (solidChecker.test(probe)) {
                hasFloor = true;
                break;
            }
        }
        if (!hasFloor) return false;

        // 2. Lateral hull walls within ±16 blocks in X
        boolean wallNegX = false, wallPosX = false;
        for (int d = 1; d <= 16; d++) {
            if (!wallNegX && (solidChecker.test(probe.set(lx - d, ly, lz))
                    || solidChecker.test(probe.set(lx - d, ly + 1, lz)))) wallNegX = true;
            if (!wallPosX && (solidChecker.test(probe.set(lx + d, ly, lz))
                    || solidChecker.test(probe.set(lx + d, ly + 1, lz)))) wallPosX = true;
            if (wallNegX && wallPosX) break;
        }
        if (!wallNegX || !wallPosX) return false;

        // 3. Longitudinal hull walls within ±28 blocks in Z
        boolean wallNegZ = false, wallPosZ = false;
        for (int d = 1; d <= 28; d++) {
            if (!wallNegZ && (solidChecker.test(probe.set(lx, ly, lz - d))
                    || solidChecker.test(probe.set(lx, ly + 1, lz - d)))) wallNegZ = true;
            if (!wallPosZ && (solidChecker.test(probe.set(lx, ly, lz + d))
                    || solidChecker.test(probe.set(lx, ly + 1, lz + d)))) wallPosZ = true;
            if (wallNegZ && wallPosZ) break;
        }

        return wallNegZ && wallPosZ;
    }

    /**
     * Checks if a local block position inside a sub-level is enclosed within a solid hull
     * (solid floor below and lateral hull walls within ±16 in X and ±28 in Z).
     */
    public static boolean isInsideHullSpace(Level subWorld, BlockPos pos) {
        if (subWorld == null) return false;
        return isInsideHullSpace(p -> isSolid(subWorld, p), pos);
    }

    public interface BilgeBlockAccessor<T> {
        boolean isSolid(T block);
        boolean isWater(T block);
        boolean isWaterlogged(T block);
        T createAir();
        T setUnwaterlogged(T block);
    }

    /**
     * Generic hold water purge across arbitrary block containers/representations.
     */
    public static <T> int purgeHoldWater(
            java.util.function.Function<BlockPos, T> getter,
            java.util.function.BiConsumer<BlockPos, T> setter,
            BilgeBlockAccessor<T> accessor,
            Iterable<BlockPos> positions
    ) {
        if (getter == null || setter == null || accessor == null || positions == null) return 0;
        int purged = 0;
        for (BlockPos pos : positions) {
            T block = getter.apply(pos);
            if (block == null) continue;
            boolean isWater = accessor.isWater(block);
            boolean isWaterlogged = accessor.isWaterlogged(block);
            if (isWater || isWaterlogged) {
                if (isInsideHullSpace(p -> {
                    T b = getter.apply(p);
                    return b != null && accessor.isSolid(b);
                }, pos)) {
                    if (isWater) {
                        setter.accept(pos, accessor.createAir());
                        purged++;
                    } else {
                        setter.accept(pos, accessor.setUnwaterlogged(block));
                        purged++;
                    }
                }
            }
        }
        return purged;
    }

    /**
     * Purges water and removes waterlogged state from blocks enclosed within a ship hold.
     */
    public static int purgeHoldWater(Level subWorld, Iterable<BlockPos> positions) {
        if (subWorld == null || positions == null) return 0;
        return purgeHoldWater(
                p -> subWorld.hasChunk(p.getX() >> 4, p.getZ() >> 4) ? subWorld.getBlockState(p) : Blocks.AIR.defaultBlockState(),
                (p, s) -> subWorld.setBlock(p, s, 3),
                new BilgeBlockAccessor<BlockState>() {
                    @Override
                    public boolean isSolid(BlockState state) {
                        return !state.isAir() && state.getFluidState().isEmpty();
                    }
                    @Override
                    public boolean isWater(BlockState state) {
                        return state.is(Blocks.WATER) || state.is(Blocks.BUBBLE_COLUMN);
                    }
                    @Override
                    public boolean isWaterlogged(BlockState state) {
                        return state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
                    }
                    @Override
                    public BlockState createAir() {
                        return Blocks.AIR.defaultBlockState();
                    }
                    @Override
                    public BlockState setUnwaterlogged(BlockState state) {
                        return state.setValue(BlockStateProperties.WATERLOGGED, false);
                    }
                },
                positions
        );
    }

    public static boolean isSolid(Level level, BlockPos pos) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    @SubscribeEvent
    public static void onPlayerTickPost(PlayerTickEvent.Post event) {
        applyDryHoldState(event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        applyDryHoldState(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlaySoundAtEntity(PlayLevelSoundEvent.AtEntity event) {
        Entity entity = event.getEntity();
        if (entity == null) return;
        if (isEntityInDryShipHold(event.getLevel(), entity.position())) {
            Holder<SoundEvent> sound = event.getSound();
            if (sound != null && sound.isBound()) {
                SoundEvent se = sound.value();
                if (se == SoundEvents.PLAYER_SPLASH
                        || se == SoundEvents.PLAYER_SPLASH_HIGH_SPEED
                        || se == SoundEvents.GENERIC_SPLASH
                        || se == SoundEvents.PLAYER_SWIM
                        || se == SoundEvents.GENERIC_SWIM) {
                    event.setCanceled(true);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlaySoundAtPosition(PlayLevelSoundEvent.AtPosition event) {
        Vec3 pos = event.getPosition();
        if (pos != null && isEntityInDryShipHold(event.getLevel(), pos)) {
            Holder<SoundEvent> sound = event.getSound();
            if (sound != null && sound.isBound()) {
                SoundEvent se = sound.value();
                if (se == SoundEvents.PLAYER_SPLASH
                        || se == SoundEvents.PLAYER_SPLASH_HIGH_SPEED
                        || se == SoundEvents.GENERIC_SPLASH
                        || se == SoundEvents.PLAYER_SWIM
                        || se == SoundEvents.GENERIC_SWIM) {
                    event.setCanceled(true);
                }
            }
        }
    }

    private static void applyDryHoldState(Entity entity) {
        if (entity == null || entity.isRemoved()) return;
        Level level = entity.level();
        if (level == null || level.dimension() != Level.OVERWORLD) return;
        Vec3 pos = entity.position();
        if (pos.y > 63.2 || pos.y < 45.0) return;

        if (isEntityInDryShipHold(level, pos)) {
            if (WAS_TOUCHING_WATER_FIELD != null) {
                try {
                    WAS_TOUCHING_WATER_FIELD.setBoolean(entity, false);
                } catch (Exception ignored) {
                }
            }
            if (WAS_EYE_IN_WATER_FIELD != null) {
                try {
                    WAS_EYE_IN_WATER_FIELD.setBoolean(entity, false);
                } catch (Exception ignored) {
                }
            }
            entity.resetFallDistance();
            if (entity instanceof LivingEntity living) {
                living.setSwimming(false);
                living.setAirSupply(living.getMaxAirSupply());
            }
        }
    }
}
