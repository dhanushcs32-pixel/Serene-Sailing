package com.serenesailing.physics;

@FunctionalInterface
public interface WaterlineProvider {
    /**
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param time World time in seconds
     * @return Water level height at the given world coordinate
     */
    double getWaterHeight(double x, double z, double time);

    /** Default flat sea level fallback at Y=63.0 */
    WaterlineProvider FLAT_SEA = (x, z, time) -> 63.0;
}