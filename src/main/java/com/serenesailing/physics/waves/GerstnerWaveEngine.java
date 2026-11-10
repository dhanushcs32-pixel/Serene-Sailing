package com.serenesailing.physics.waves;

import com.serenesailing.config.SereneSailingClientConfig;
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
        // FIX (Audit 106): Made volatile so render-thread reads see writes
        // from the tick-thread's synchronized update(). Without volatile,
        // the JMM permits stale caching in CPU registers/L1 indefinitely.
        public volatile double smoothedWindSpeed = 1.0;
        public volatile double smoothedWindDirX = 0.0;
        public volatile double smoothedWindDirZ = -1.0;
        public volatile double smoothedStormIntensity = 0.15;
        public volatile long lastUpdateTick = -1L;

        // Smoothing targets. On the server (and in single-player) these are derived from weather every tick;
        // on a remote client they are received from the server (serverDriven == true) because the client does
        // NOT know Weather2's wind (ServerPhysicsHandler's wind cache is only filled by the server tick).
        public volatile double targetWindSpeed = 1.0;
        public volatile double targetStormIntensity = 0.15;
        public volatile double targetWindDirX = 0.0;
        public volatile double targetWindDirZ = -1.0;
        /** True on a remote client once a server snapshot has been received. */
        public volatile boolean serverDriven = false;

        /** Upper bound on how many missed ticks one update() call replays (alpha=0.02 -> ~98% converged). */
        private static final int MAX_CATCHUP_STEPS = 200;

        /**
         * Advances the smoothed state to {@code gameTime}, replaying every tick since the last update.
         *
         * <p>This makes the state a pure function of (targets, game time): it no longer matters whether the
         * server, the client render thread, or the physics step happens to call it first, nor how often.
         * Previously it advanced exactly once per call that saw a new game time, so a dedicated server with
         * no ships (nobody querying) froze while clients kept smoothing, and the two diverged.
         */
        public synchronized void update(Level level, long gameTime) {
            if (lastUpdateTick < 0) {
                lastUpdateTick = gameTime - 1L;
            }
            if (gameTime < lastUpdateTick - 100L) {
                // The clock went backwards (new world / reload): resynchronise instead of freezing forever.
                lastUpdateTick = gameTime - 1L;
            }
            long steps = Math.min(gameTime - lastUpdateTick, (long) MAX_CATCHUP_STEPS);
            if (steps <= 0L) {
                return;
            }
            lastUpdateTick = gameTime;

            if (!serverDriven) {
                computeTargets(level);
            }
            for (long i = 0; i < steps; i++) {
                stepTowardTargets();
            }
        }

        private void computeTargets(Level level) {
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
                // Clear weather: Weather2 baseline wind (rawSpeed <= 1.15) provides gentle visual swell
                if (rawSpeed <= 1.15f) {
                    targetStorm = 0.15; // Gentle visual swell floor
                } else {
                    // High wind event on a clear day: ramp gently from 0.15 to 0.28
                    double frac = Math.min(1.0, (rawSpeed - 1.15) / 0.85);
                    targetStorm = 0.15 + frac * 0.13;
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
                targetStorm = 0.15;
            }

            double rad = Math.toRadians(rawAngle);
            double targetDirX = -Math.sin(rad);
            double targetDirZ = Math.cos(rad);
            if (targetDirX * targetDirX + targetDirZ * targetDirZ < 1.0e-4) {
                targetDirX = 0.0;
                targetDirZ = -1.0;
            }
            this.targetWindSpeed = rawSpeed;
            this.targetStormIntensity = targetStorm;
            this.targetWindDirX = targetDirX;
            this.targetWindDirZ = targetDirZ;
        }

        private void stepTowardTargets() {
            double alpha = 0.02; // Smooth ramp up over ~5-10 seconds
            smoothedWindSpeed += (targetWindSpeed - smoothedWindSpeed) * alpha;
            smoothedStormIntensity += (targetStormIntensity - smoothedStormIntensity) * alpha;
            smoothedStormIntensity = Mth.clamp(smoothedStormIntensity, 0.01, 1.0);

            smoothedWindDirX += (targetWindDirX - smoothedWindDirX) * alpha;
            smoothedWindDirZ += (targetWindDirZ - smoothedWindDirZ) * alpha;
            double dirLen = Math.sqrt(smoothedWindDirX * smoothedWindDirX + smoothedWindDirZ * smoothedWindDirZ);
            if (dirLen > 1.0e-6) {
                smoothedWindDirX /= dirLen;
                smoothedWindDirZ /= dirLen;
            } else {
                smoothedWindDirX = 0.0;
                smoothedWindDirZ = -1.0;
            }
        }

        /**
         * Applies an authoritative server snapshot (remote clients only). The snapshot describes the server's
         * state at {@code serverTick}; the next {@link #update} replays the ticks between then and now.
         */
        public synchronized void applyServerSnapshot(long serverTick,
                                                     double speed, double dirX, double dirZ, double storm,
                                                     double tSpeed, double tDirX, double tDirZ, double tStorm) {
            this.smoothedWindSpeed = speed;
            this.smoothedWindDirX = dirX;
            this.smoothedWindDirZ = dirZ;
            this.smoothedStormIntensity = Mth.clamp(storm, 0.0, 1.0);
            this.targetWindSpeed = tSpeed;
            this.targetWindDirX = tDirX;
            this.targetWindDirZ = tDirZ;
            this.targetStormIntensity = Mth.clamp(tStorm, 0.0, 1.0);
            this.lastUpdateTick = serverTick;
            this.serverDriven = true;
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
        if (level == null || level.dimension() == null) {
            return DEFAULT_STATE;
        }
        return DIMENSION_STATES.computeIfAbsent(level.dimension(), dim -> new DimensionWaveState());
    }

    /**
     * Wave-aggressiveness scales received from the server on a remote client ({calm, storm}), or null.
     * The aggressiveness sliders live in the CLIENT config; if each client's own value fed the physics the
     * visible wave would differ from the one the (server-authoritative) physics rides. On a remote client the
     * server's values therefore win; in single-player the server reads the same config so nothing changes.
     */
    private static volatile double[] authoritativeScales = null;

    public static void setAuthoritativeScales(double calm, double storm) {
        authoritativeScales = new double[] { calm, storm };
    }

    public static double[] currentScales() {
        double[] a = authoritativeScales;
        return a != null ? new double[] { a[0], a[1] }
                : new double[] { SereneSailingClientConfig.getCalmAggressiveness(),
                                 SereneSailingClientConfig.getStormAggressiveness() };
    }

    /** Drops all per-world wave state (logout / server stop) so a new world never inherits a stale clock. */
    public static void resetAll() {
        DIMENSION_STATES.clear();
        authoritativeScales = null;
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
     * Computes the active linear wave multiplier based on stormIntensity and user config settings.
     * Safe during unit tests (defaults to 1.0).
     */
    public static double getActiveWaveScale(double stormIntensity) {
        double[] scales = currentScales();
        double calmScale = scales[0];
        double stormScale = scales[1];
        double stormBlend = Mth.clamp((stormIntensity - 0.15) / 0.55, 0.0, 1.0);
        return Mth.lerp(stormBlend, calmScale, stormScale);
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
        double activeWaveScale = getActiveWaveScale(stormIntensity);

        // Baseline Swell Boost:
        // Clear/calm weather (stormIntensity <= 0.03) stays strictly <= 0.05m.
        // Normal weather (0.10 to 0.25) produces the 0.45m - 0.75m swell that previously required stormIntensity = 1.0.
        // Full storms (stormIntensity > 0.4) scale up to 1.2m - 1.8m+ crest-to-trough variation.
        double effectiveIntensity;
        if (stormIntensity <= 0.03) {
            effectiveIntensity = stormIntensity;
        } else {
            double t = (stormIntensity - 0.03) / 0.12;
            if (t < 1.0) {
                double s = t * t * (3.0 - 2.0 * t);
                effectiveIntensity = 0.03 + s * 0.72;
            } else {
                double u = (stormIntensity - 0.15) / 0.85;
                effectiveIntensity = 0.75 + u * 0.60;
            }
        }

        // Low-frequency Wave Group Envelope Modulation for rogue wave sets during storms
        double envelope = 1.0;
        if (stormIntensity > 0.35) {
            double stormFrac = (stormIntensity - 0.35) / 0.65;
            // Phase-aligned wave group envelope: produces rogue wave sets with deep drops and towering peaks
            double groupWave = 0.65 + 0.55 * Math.sin(x * 0.03 + z * 0.025 - timeSeconds * 0.4 + 1.5707963);
            double baseEnvelope = Mth.lerp(stormFrac, 1.0, groupWave);
            envelope = 1.0 + (baseEnvelope - 1.0) * activeWaveScale;
        }

        double steepnessQ = (stormIntensity > 0.4)
                ? Mth.clamp(0.20 + 0.25 * stormIntensity, 0.05, 0.35)
                : Mth.clamp(0.25 * effectiveIntensity, 0.05, 0.28);
        steepnessQ *= activeWaveScale;

        double totalOffset = 0.0;

        for (int i = 0; i < NUM_COMPONENTS; i++) {
            // Directional alignment weight: W_i = 0.20 + 0.80 * max(0.0, d_i · w)^2
            double dot = DIR_X[i] * windDirX + DIR_Z[i] * windDirZ;
            double alignment = dot > 0.0 ? dot : 0.0;
            double weight = 0.20 + 0.80 * (alignment * alignment);

            double amp = BASE_AMPLITUDES[i] * effectiveIntensity * weight * envelope * activeWaveScale;
            double phase = K[i] * (DIR_X[i] * x + DIR_Z[i] * z) - OMEGA[i] * timeSeconds;

            double cosPhi = Math.cos(phase);
            // 2nd-order Trochoidal crest sharpening: cos(2phi) = 2*cos^2(phi) - 1
            double cos2Phi = 2.0 * cosPhi * cosPhi - 1.0;
            double waveProfile = cosPhi + steepnessQ * cos2Phi;

            if (stormIntensity > 0.4) {
                // Sharper trochoidal crest sharpening curve
                double sharpness = 1.0 + 0.8 * ((stormIntensity - 0.4) / 0.6) * activeWaveScale;
                double u = (cosPhi + 1.0) * 0.5;
                double sharpened = Math.pow(u, sharpness) * 2.2 - 0.85;
                double blend = ((stormIntensity - 0.4) / 0.6) * 0.4 * Math.min(1.0, activeWaveScale);
                waveProfile = Mth.lerp(blend, waveProfile, sharpened);
            }

            totalOffset += amp * waveProfile;
        }

        return totalOffset;
    }

    /**
     * The three short-period "chop" harmonics (lambda ~ 11 m, 5.8 m, 3.4 m) that used to live only inside
     * {@code ClientWaveRenderer}. They are part of the visible surface, so they must be part of the physical
     * surface too. Construct once per frame / per query, then call {@link #offset} per point.
     */
    public static final class ChopField {
        private final double dx1, dz1, dx2, dz2, dx3, dz3, amp1, amp2, amp3;

        public ChopField(double stormIntensity, double windDirX, double windDirZ) {
            double windAngle = Math.atan2(windDirZ, windDirX);
            double a1 = windAngle + 0.30;  // ~ +18 deg
            double a2 = windAngle - 0.40;  // ~ -23 deg
            double a3 = windAngle + 0.733; // ~ +42 deg
            dx1 = Math.cos(a1); dz1 = Math.sin(a1);
            dx2 = Math.cos(a2); dz2 = Math.sin(a2);
            dx3 = Math.cos(a3); dz3 = Math.sin(a3);
            double activeWaveScale = getActiveWaveScale(stormIntensity);
            double stormScale = stormIntensity > 0.4 ? (1.0 + (stormIntensity - 0.4) * 0.8) : 1.0;
            amp1 = (0.12 * stormIntensity + 0.035) * stormScale * activeWaveScale;
            amp2 = (0.07 * stormIntensity + 0.020) * stormScale * activeWaveScale;
            amp3 = (stormIntensity > 0.25 ? (0.055 * stormIntensity + 0.015) : 0.0) * stormScale * activeWaveScale;
        }

        public double offset(double x, double z, double timeSeconds) {
            return Math.sin(0.5712 * (dx1 * x + dz1 * z) - 2.367 * timeSeconds) * amp1
                 + Math.sin(1.0833 * (dx2 * x + dz2 * z) - 3.259 * timeSeconds) * amp2
                 + Math.sin(1.8480 * (dx3 * x + dz3 * z) - 4.150 * timeSeconds) * amp3;
        }
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
        double activeWaveScale = getActiveWaveScale(stormIntensity);

        double effectiveIntensity;
        if (stormIntensity <= 0.03) {
            effectiveIntensity = stormIntensity;
        } else {
            double t = (stormIntensity - 0.03) / 0.12;
            if (t < 1.0) {
                double s = t * t * (3.0 - 2.0 * t);
                effectiveIntensity = 0.03 + s * 0.72;
            } else {
                double u = (stormIntensity - 0.15) / 0.85;
                effectiveIntensity = 0.75 + u * 0.60;
            }
        }

        double envelope = 1.0;
        if (stormIntensity > 0.35) {
            double stormFrac = (stormIntensity - 0.35) / 0.65;
            double groupWave = 0.65 + 0.55 * Math.sin(x * 0.03 + z * 0.025 - timeSeconds * 0.4 + 1.5707963);
            double baseEnvelope = Mth.lerp(stormFrac, 1.0, groupWave);
            envelope = 1.0 + (baseEnvelope - 1.0) * activeWaveScale;
        }

        double steepnessQ = (stormIntensity > 0.4)
                ? Mth.clamp(0.20 + 0.25 * stormIntensity, 0.05, 0.35)
                : Mth.clamp(0.25 * effectiveIntensity, 0.05, 0.28);
        steepnessQ *= activeWaveScale;
        double slopeX = 0.0;
        double slopeZ = 0.0;
        double stokesDriftMag = 0.0;

        for (int i = 0; i < NUM_COMPONENTS; i++) {
            double dot = DIR_X[i] * windDirX + DIR_Z[i] * windDirZ;
            double alignment = dot > 0.0 ? dot : 0.0;
            double weight = 0.20 + 0.80 * (alignment * alignment);

            double amp = BASE_AMPLITUDES[i] * effectiveIntensity * weight * envelope * activeWaveScale;
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
        double activeWaveScale = getActiveWaveScale(stormIntensity);

        double effectiveIntensity;
        if (stormIntensity <= 0.03) {
            effectiveIntensity = stormIntensity;
        } else {
            double t = (stormIntensity - 0.03) / 0.12;
            if (t < 1.0) {
                double s = t * t * (3.0 - 2.0 * t);
                effectiveIntensity = 0.03 + s * 0.72;
            } else {
                double u = (stormIntensity - 0.15) / 0.85;
                effectiveIntensity = 0.75 + u * 0.60;
            }
        }

        double driftX = 0.0;
        double driftZ = 0.0;

        for (int i = 0; i < NUM_COMPONENTS; i++) {
            double dot = DIR_X[i] * windDirX + DIR_Z[i] * windDirZ;
            double alignment = dot > 0.0 ? dot : 0.0;
            double weight = 0.20 + 0.80 * (alignment * alignment);

            double amp = BASE_AMPLITUDES[i] * effectiveIntensity * weight * activeWaveScale;
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
