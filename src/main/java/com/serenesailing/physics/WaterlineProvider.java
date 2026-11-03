package com.serenesailing.physics;

/**
 * Supplies the water surface height for the standalone engine.
 *
 * <p><b>Status: not wired into the game.</b> Used only by {@link ShipPhysicsEngine} and {@link SailingPhysicsEngine}.
 * Part of the standalone engine described in {@link SailingPhysicsEngine}; changes here do not
 * affect gameplay (the live physics is in {@link com.serenesailing.event.ServerPhysicsHandler}).
 */
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