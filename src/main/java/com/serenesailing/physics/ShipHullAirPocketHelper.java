package com.serenesailing.physics;

import com.serenesailing.SereneSailing;
import com.serenesailing.hold.HoldBlocks;
import com.serenesailing.hold.HoldRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;

/**
 * Dry-hold helpers. The hold geometry itself now lives in {@link com.serenesailing.hold.HoldScanner} /
 * {@link HoldRegistry}; this class keeps the generic bilge-purge helpers, the unified solid predicate for
 * level-based callers, and the splash / swim sound suppression inside holds.
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID)
public class ShipHullAirPocketHelper {

    /**
     * True if the world position lies inside the sealed volume of a registered sunken hold. Backed by the scanned
     * hull volume ({@link HoldRegistry}), not by per-call block probing.
     */
    public static boolean isEntityInDryShipHold(Level level, Vec3 worldPos) {
        return level != null && worldPos != null && HoldRegistry.isOccluded(level, worldPos);
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
                        return HoldBlocks.isHull(state);
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
        return HoldBlocks.isHull(level.getBlockState(pos));
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
}
