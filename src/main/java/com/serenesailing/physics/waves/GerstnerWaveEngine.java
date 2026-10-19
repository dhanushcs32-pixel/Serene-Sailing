package com.serenesailing.physics.waves;

import com.serenesailing.event.ServerPhysicsHandler;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production-grade, phase-continuous Gerstner & Trochoidal Ocean Wave Engine.
 *
 * <p>Key Design Principles:
 * <ul>
 *   <li><b>Phase Continuity:</b> Wave propagation directions and wavenumbers (k)
 *       are strictly fixed constants. Wind changes modulate only directional spectrum
 *       energy weights (W_i) and Beaufort storm intensity (S). At any coordinate
 *       (even x, z > 5000), phase k · x never jumps when wind angle changes.</li>
 *   <li><b>Trochoidal Crest Sharpening:</b> 2nd-order Stokes/Trochoidal expansion
 *       (cos(phi) + q*cos(2phi)) produces tall, sharp crests and wide, flat troughs.</li>
 *   <li><b>Zero-Allocation Math:</b> Hot evaluation loops operate entirely on primitive
 *       doubles using trigonometric identities (cos(2x) = 2cos^2(x) - 1).</li>
 *   <li><b>Weather2 & Vanilla Storm Coupling:</b> Smooth exponential smoothing (alpha = 0.02)
 *       interpolates wind speed, direction, and Beaufort storm intensity factor S in [0.0, 1.0].</li>
 * </ul>
 */
public final class GerstnerWaveEngine {

    public static final int NUM_COMPONENTS = 8;
    public static final double GRAVITY = 9.81;

    // 8 Fixed Wave Component Wavelengths [m] for long-period ocean swells [24m to 96m]
    private static final double[] WAVELENGTHS = new double[] {
        24.0, 32.0, 40.0, 48.0, 56.0, 68.0, 80.0, 96.0
    };

    // 8 Fixed Direction Angles [rad] around compass with golden-ratio offsets
    private static final double[] COMPONENT_ANGLES = new double[] {
        0.0000, // East (0 deg)
        0.8654, // ~49.6 deg
        1.4908, // ~85.4 deg
        2.4362, // ~139.6 deg
        3.0616, // ~175.4 deg
        4.0070, // ~229.6 deg
        4.6324, // ~265.4 deg
        5.5778  // ~319.6 deg
    };

    // Base Amplitudes [m] for each component at S = 1.0 (balanced so sum along any heading produces 1.8m - 2.8m storm crests)
    private static final double[] BASE_AMPLITUDES = new double[] {
        0.75, 0.68, 0.62, 0.55, 0.72, 0.65, 0.58, 0.50
    };

    // Precomputed directional unit vectors, wavenumbers k = 2pi/lambda, and dispersion frequencies omega = sqrt(g*k) * 0.45
    public static final double[] DIR_X = new double[NUM_COMPONENTS];
    public static final double[] DIR_Z = new double[NUM_COMPONENTS];
    public static final double[] K = new double[NUM_COMPONENTS];
    public static final double[] OMEGA = new double[NUM_COMPONENTS];

    static {
        for (int i = 0; i < NUM_COMPONENTS; i++) {
            DIR_X[i] = Math.cos(COMPONENT_ANGLES[i]);
            DIR_Z[i] = Math.sin(COMPONENT_ANGLES[i]);
            K[i] = (2.0 * Math.PI) / WAVELENGTHS[i];
            // Multiply dispersion frequency by 0.45 for long 7 to 14 second ocean swell periods
            OMEGA[i] = Math.sqrt(GRAVITY * K[i]) * 0.45;
        }
    }

    /**
     * Smoothed wave and storm state tracked per dimension.
     */
    public static class DimensionWaveState {
        public double smoothedWindSpeed = 1.0;
        public double smoothedWindDirX = 0.0;
        public double smoothedWindDirZ = -1.0;
        public double smoothedStormIntensity = 0.02;
        public long lastUpdateTick = -1L;

        public synchronized void update(Level level, long gameTime) {
            if (gameTime == lastUpdateTick && lastUpdateTick >= 0) {
                return;
            }
            lastUpdateTick = gameTime;

            float rawSpeed = 1.0f;
            float rawAngle = 0.0f;
            if (level != null) {
                ServerPhysicsHandler.CachedWind wind = ServerPhysicsHandler.getDimensionWind(level.dimension());
                rawSpeed = wind.speed();
                rawAngle = wind.angle();
            }

            boolean raining = level != null && level.isRaining();
            boolean thundering = level != null && level.isThundering();

            // Beaufort / Storm Intensity Factor S in [0.0, 1.0] calibrated for Weather2 baseline wind
            double targetStorm;
            if (!raining && !thundering) {
                // Clear weather: Weather2 baseline wind (rawSpeed <= 1.15) is normal calm sunny weather!
                if (rawSpeed <= 1.15f) {
                    targetStorm = 0.02; // Virtually still water (max wave height <= 0.04m, no bobbing at dock!)
                } else {
                    // High wind event on a clear day: ramp gently from 0.02 to 0.25
                    double frac = Math.min(1.0, (rawSpeed - 1.15) / 0.85);
                    targetStorm = 0.02 + frac * 0.23;
                }
            } else if (thundering || rawSpeed >= 1.8f) {
                // Full Storm / Gale: S in [0.75, 1.0]
                double frac = Math.min(1.0, Math.max(0.0, (rawSpeed - 1.2) / 0.8));
                targetStorm = 0.75 + frac * 0.25;
            } else if (raining) {
                // Rain squall: S in [0.35, 0.55]
                double frac = Math.min(1.0, Math.max(0.0, (rawSpeed - 0.5) / 1.0));
                targetStorm = 0.35 + frac * 0.20;
            } else {
                targetStorm = 0.02;
            }

            double rad = Math.toRadians(rawAngle);
            double targetDirX = -Math.sin(rad);
            double targetDirZ = Math.cos(rad);
            if (targetDirX * targetDirX + targetDirZ * targetDirZ < 1.0e-4) {
                targetDirX = 0.0;
                targetDirZ = -1.0;
            }

            double alpha = 0.02; // Smooth ramp up over ~5-10 seconds
            smoothedWindSpeed += (rawSpeed - smoothedWindSpeed) * alpha;
            smoothedStormIntensity += (targetStorm - smoothedStormIntensity) * alpha;
            smoothedStormIntensity = Mth.clamp(smoothedStormIntensity, 0.01, 1.0);

            smoothedWindDirX += (targetDirX - smoothedWindDirX) * alpha;
            smoothedWindDirZ += (targetDirZ - smoothedWindDirZ) * alpha;
            double dirLen = Math.sqrt(smoothedWindDirX * smoothedWindDirX + smoothedWindDirZ * smoothedWindDirZ);
            if (dirLen > 1.0e-6) {
                smoothedWindDirX /= dirLen;
                smoothedWindDirZ /= dirLen;
            } else {
                smoothedWindDirX = 0.0;
                smoothedWindDirZ = -1.0;
            }
        }

        public synchronized void setImmediate(double windSpeed, double dirX, double dirZ, double stormIntensity) {
            this.smoothedWindSpeed = windSpeed;
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len > 1.0e-6) {
                this.smoothedWindDirX = dirX / len;
                this.smoothedWindDirZ = dirZ / len;
            } else {
                this.smoothedWindDirX = 0.0;
                this.smoothedWindDirZ = -1.0;
            }
            this.smoothedStormIntensity = Mth.clamp(stormIntensity, 0.0, 1.0);
        }
    }

    private static final Map<ResourceKey<Level>, DimensionWaveState> DIMENSION_STATES = new ConcurrentHashMap<>();
    private static final DimensionWaveState DEFAULT_STATE = new DimensionWaveState();

    public static DimensionWaveState getDimensionState(Level level) {
        if (level == null) {
            return DEFAULT_STATE;
        }
        return DIMENSION_STATES.computeIfAbsent(level.dimension(), dim -> new DimensionWaveState());
    }

    public static double getStormIntensity(Level level) {
        if (level == null) {
            return 0.10;
        }
        return getDimensionState(level).smoothedStormIntensity;
    }

    /**
     * Primary wave height hook: evaluates the phase-continuous Gerstner wave displacement
     * in the Overworld at the specified coordinates and time.
     *
     * @param level current world level
     * @param x world X
     * @param z world Z
     * @param time game time in ticks
     * @return vertical wave surface displacement [meters] relative to static sea level
     */
    public static double getWaveHeightOffset(Level level, double x, double z, double time) {
        if (level == null) {
            return 0.0;
        }
        // Only evaluate waves in overworld (or worlds with water)
        if (level.dimension() != Level.OVERWORLD) {
            return 0.0;
        }

        DimensionWaveState state = getDimensionState(level);
        long gameTime = (long) time;
        state.update(level, gameTime);

        double timeSeconds = time * 0.05; // 20 ticks = 1 second
        return evaluateWaveOffset(x, z, timeSeconds, state.smoothedStormIntensity,
                state.smoothedWindDirX, state.smoothedWindDirZ);
    }

    /**
     * Fast, zero-allocation primitive evaluation of Gerstner & Trochoidal vertical wave displacement.
     *
     * @param x world X [m]
     * @param z world Z [m]
     * @param timeSeconds elapsed time [s]
     * @param stormIntensity Beaufort / storm intensity factor in [0.0, 1.0]
     * @param windDirX normalized wind propagation direction X
     * @param windDirZ normalized wind propagation direction Z
     * @return vertical wave surface elevation eta(x, z, t) [m]
     */
    public static double evaluateWaveOffset(double x, double z, double timeSeconds,
                                            double stormIntensity, double windDirX, double windDirZ) {
        double steepnessQ = Mth.clamp(0.25 * stormIntensity, 0.05, 0.28);
        double totalOffset = 0.0;

        for (int i = 0; i < NUM_COMPONENTS; i++) {
            // Directional alignment weight: W_i = 0.20 + 0.80 * max(0.0, d_i · w)^2
            double dot = DIR_X[i] * windDirX + DIR_Z[i] * windDirZ;
            double alignment = dot > 0.0 ? dot : 0.0;
            double weight = 0.20 + 0.80 * (alignment * alignment);

            double amp = BASE_AMPLITUDES[i] * stormIntensity * weight;
            double phase = K[i] * (DIR_X[i] * x + DIR_Z[i] * z) - OMEGA[i] * timeSeconds;

            double cosPhi = Math.cos(phase);
            // 2nd-order Trochoidal crest sharpening: cos(2phi) = 2*cos^2(phi) - 1
            double cos2Phi = 2.0 * cosPhi * cosPhi - 1.0;

            totalOffset += amp * (cosPhi + steepnessQ * cos2Phi);
        }

        return totalOffset;
    }

    /**
     * Analytical partial derivatives and horizontal Stokes mass drift evaluation.
     *
     * @param x world X [m]
     * @param z world Z [m]
     * @param timeSeconds elapsed time [s]
     * @param stormIntensity Beaufort / storm intensity factor in [0.0, 1.0]
     * @param windDirX normalized wind propagation direction X
     * @param windDirZ normalized wind propagation direction Z
     * @param out Vector3d receiving: x = dEta/dx (slope X), y = Stokes drift magnitude [m/s], z = dEta/dz (slope Z)
     * @return the populated out Vector3d
     */
    public static Vector3d evaluateWaveSlopeAndDrift(double x, double z, double timeSeconds,
                                                    double stormIntensity, double windDirX, double windDirZ,
                                                    Vector3d out) {
        double steepnessQ = Mth.clamp(0.25 * stormIntensity, 0.05, 0.28);
        double slopeX = 0.0;
        double slopeZ = 0.0;
        double stokesDriftMag = 0.0;

        for (int i = 0; i < NUM_COMPONENTS; i++) {
            double dot = DIR_X[i] * windDirX + DIR_Z[i] * windDirZ;
            double alignment = dot > 0.0 ? dot : 0.0;
            double weight = 0.20 + 0.80 * (alignment * alignment);

            double amp = BASE_AMPLITUDES[i] * stormIntensity * weight;
            double phase = K[i] * (DIR_X[i] * x + DIR_Z[i] * z) - OMEGA[i] * timeSeconds;

            double sinPhi = Math.sin(phase);
            double cosPhi = Math.cos(phase);
            double sin2Phi = 2.0 * sinPhi * cosPhi; // sin(2phi) = 2*sin(phi)*cos(phi)

            // dEta/dPhi = -amp * (sin(phi) + 2*q*sin(2phi))
            double dEtaDPhi = -amp * (sinPhi + 2.0 * steepnessQ * sin2Phi);
            slopeX += dEtaDPhi * K[i] * DIR_X[i];
            slopeZ += dEtaDPhi * K[i] * DIR_Z[i];

            // Deep-water Stokes mass drift: u_Stokes = sum omega_i * k_i * A_i^2
            stokesDriftMag += OMEGA[i] * K[i] * amp * amp;
        }

        out.x = slopeX;
        out.y = stokesDriftMag;
        out.z = slopeZ;
        return out;
    }

    public static Vector3d evaluateWaveSlopeAndDrift(double x, double z, double timeSeconds,
                                                    double stormIntensity, double windDirX, double windDirZ) {
        return evaluateWaveSlopeAndDrift(x, z, timeSeconds, stormIntensity, windDirX, windDirZ, new Vector3d());
    }

    /**
     * Computes the net horizontal Stokes drift vector [N or m/s] aligned with wave propagation.
     */
    public static Vector3d evaluateStokesDriftVector(double stormIntensity, double windDirX, double windDirZ, Vector3d out) {
        double driftX = 0.0;
        double driftZ = 0.0;

        for (int i = 0; i < NUM_COMPONENTS; i++) {
            double dot = DIR_X[i] * windDirX + DIR_Z[i] * windDirZ;
            double alignment = dot > 0.0 ? dot : 0.0;
            double weight = 0.20 + 0.80 * (alignment * alignment);

            double amp = BASE_AMPLITUDES[i] * stormIntensity * weight;
            double componentDrift = OMEGA[i] * K[i] * amp * amp;
            driftX += componentDrift * DIR_X[i];
            driftZ += componentDrift * DIR_Z[i];
        }

        out.x = driftX;
        out.y = 0.0;
        out.z = driftZ;
        return out;
    }

    private GerstnerWaveEngine() {}
}
