package com.serenesailing.compat.weather2;

import com.serenesailing.SereneSailing;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;

/**
 * Safe integration with Weather2 storms and tornadoes.
 * Queries nearby storms (F0-F5 tornadoes, cyclones, severe gales)
 * and exposes storm intensity and kinematics to ServerPhysicsHandler.
 */
public class Weather2Compat {

    public record StormData(
            double stormX,
            double stormZ,
            double radius,
            int intensityStage, // 0 = none, 1 = F0/highwind, 2 = F1, 3 = F2, 4 = F3, 5 = F4, 6 = F5
            double distance,
            boolean isTornado,
            boolean isCyclone
    ) {}

    private static volatile StormData testStormOverride = null;

    public static void setTestStormOverride(StormData storm) {
        testStormOverride = storm;
    }

    public static void clearTestStormOverride() {
        testStormOverride = null;
    }

    /**
     * Finds the nearest active Weather2 storm or tornado within {@code maxDistance} blocks.
     */
    public static StormData getNearestStorm(Level level, double x, double z, double maxDistance) {
        // 1. Testing / manual override hook
        StormData override = testStormOverride;
        if (override != null) {
            double dx = override.stormX() - x;
            double dz = override.stormZ() - z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d <= maxDistance) {
                return new StormData(
                        override.stormX(), override.stormZ(), override.radius(),
                        override.intensityStage(), d, override.isTornado(), override.isCyclone()
                );
            }
        }

        if (level == null || level.isClientSide()) {
            return null;
        }

        try {
            if (level instanceof ServerLevel serverLevel) {
                WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(serverLevel);
                if (manager == null) {
                    return null;
                }

                Vec3 probe = new Vec3(x, 63.0, z);
                StormObject storm = manager.getClosestStormAny(probe, maxDistance);
                if (storm != null && !storm.isDead) {
                    Vec3 sPos = storm.posGround != null ? storm.posGround : storm.pos;
                    if (sPos == null) {
                        sPos = storm.calculateBaseFormationPos();
                    }
                    if (sPos == null) {
                        return null;
                    }

                    double dx = sPos.x - x;
                    double dz = sPos.z - z;
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist > maxDistance) {
                        return null;
                    }

                    double radius = storm.size > 0 ? (double) storm.size : (storm.scale > 0 ? (double) storm.scale * 12.0 : 45.0);
                    int rawStage = storm.levelCurIntensityStage;

                    // Map Weather2 stages to 0..6 (1=F0, 2=F1, 3=F2, 4=F3, 5=F4, 6=F5)
                    int mappedStage;
                    if (rawStage >= 9) { // STATE_STAGE5
                        mappedStage = 6;
                    } else if (rawStage == 8) { // STATE_STAGE4
                        mappedStage = 5;
                    } else if (rawStage == 7) { // STATE_STAGE3
                        mappedStage = 4;
                    } else if (rawStage == 6) { // STATE_STAGE2
                        mappedStage = 3;
                    } else if (rawStage == 5) { // STATE_STAGE1
                        mappedStage = 2;
                    } else if (rawStage == 4) { // STATE_FORMING / F0
                        mappedStage = 1;
                    } else if (rawStage >= 1 && rawStage <= 6) {
                        mappedStage = rawStage;
                    } else if (rawStage == 2 || rawStage == 3) { // STATE_HIGHWIND / STATE_HAIL
                        mappedStage = 1;
                    } else {
                        mappedStage = 0;
                    }

                    boolean isTornado = storm.isTornadoFormingOrGreater() || storm.isSpinning() || rawStage >= 4;
                    boolean isCyclone = storm.isTropicalCyclone() || storm.isCycloneFormingOrGreater();

                    return new StormData(sPos.x, sPos.z, radius, mappedStage, dist, isTornado, isCyclone);
                }
            }
        } catch (Throwable t) {
            // Safe fallback if Weather2 is missing or optional
            SereneSailing.LOGGER.debug("[Weather2Compat] Weather2 query skipped: {}", t.getMessage());
        }

        return null;
    }
}