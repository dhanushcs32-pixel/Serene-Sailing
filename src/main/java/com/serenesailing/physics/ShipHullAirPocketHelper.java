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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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

        // 1. Solid floor / keel below entity (search downward up to 4.5 blocks)
        boolean hasFloor = false;
        for (double dy = 0.0; dy <= 4.5; dy += 0.5) {
            worldProbe.set(wx, wy - dy, wz);
            pose.transformPositionInverse(worldProbe, localProbe);
            subProbePos.set(Mth.floor(localProbe.x), Mth.floor(localProbe.y), Mth.floor(localProbe.z));
            if (subWorld.hasChunk(subProbePos.getX() >> 4, subProbePos.getZ() >> 4)) {
                BlockState state = subWorld.getBlockState(subProbePos);
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    hasFloor = true;
                    break;
                }
            }
        }
        if (!hasFloor) return false;

        // 2. Solid ceiling / deck above entity reaching at least Y = 62.5
        boolean hasCeiling = false;
        for (double testY = Math.max(wy + 1.2, 62.2); testY <= 68.0; testY += 0.6) {
            worldProbe.set(wx, testY, wz);
            pose.transformPositionInverse(worldProbe, localProbe);
            subProbePos.set(Mth.floor(localProbe.x), Mth.floor(localProbe.y), Mth.floor(localProbe.z));
            if (subWorld.hasChunk(subProbePos.getX() >> 4, subProbePos.getZ() >> 4)) {
                BlockState state = subWorld.getBlockState(subProbePos);
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    hasCeiling = true;
                    break;
                }
            }
        }
        if (!hasCeiling) return false;

        // 3. Surrounded on all 4 horizontal sides (±X, ±Z) by hull blocks at entity's Y level
        worldProbe.set(wx, wy + 0.5, wz);
        pose.transformPositionInverse(worldProbe, localProbe);
        int lx = Mth.floor(localProbe.x);
        int ly = Mth.floor(localProbe.y);
        int lz = Mth.floor(localProbe.z);

        boolean wallNegX = false, wallPosX = false, wallNegZ = false, wallPosZ = false;
        for (int d = 1; d <= 8; d++) {
            if (!wallNegX && isSolid(subWorld, subProbePos.set(lx - d, ly, lz))) wallNegX = true;
            if (!wallPosX && isSolid(subWorld, subProbePos.set(lx + d, ly, lz))) wallPosX = true;
            if (!wallNegZ && isSolid(subWorld, subProbePos.set(lx, ly, lz - d))) wallNegZ = true;
            if (!wallPosZ && isSolid(subWorld, subProbePos.set(lx, ly, lz + d))) wallPosZ = true;
        }

        return wallNegX && wallPosX && wallNegZ && wallPosZ;
    }

    private static boolean isSolid(Level level, BlockPos pos) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    @SubscribeEvent
    public static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        applyDryHoldState(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerTickPost(PlayerTickEvent.Post event) {
        applyDryHoldState(event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityTickPre(EntityTickEvent.Pre event) {
        applyDryHoldState(event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        applyDryHoldState(event.getEntity());
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
