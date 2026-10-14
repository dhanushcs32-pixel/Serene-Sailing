package com.serenesailing.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class SailingConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue BUOYANCY_SCALAR;
    public static final ModConfigSpec.DoubleValue METACENTRIC_SCALE;
    public static final ModConfigSpec.DoubleValue LINEAR_DAMPING;
    public static final ModConfigSpec.DoubleValue ANGULAR_DAMPING;
    public static final ModConfigSpec.DoubleValue LINEAR_DRAG_MU;
    public static final ModConfigSpec.DoubleValue QUADRATIC_DRAG_MU;
    public static final ModConfigSpec.DoubleValue ANGULAR_DRAG_MU;
    public static final ModConfigSpec.DoubleValue STEER_TORQUE_COEFF;
    public static final ModConfigSpec.DoubleValue SAIL_THRUST_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue MIN_WIND_SPEED;
    public static final ModConfigSpec.BooleanValue ANTIGRAVITY_PROBE;
    public static final ModConfigSpec.DoubleValue PROBE_LINEAR_IMPULSE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("Physics Parameters");
        BUOYANCY_SCALAR = builder.comment("Buoyancy force multiplier")
                .defineInRange("buoyancyScalar", 1.0, 0.1, 10.0);
        METACENTRIC_SCALE = builder.comment("Righting torque scale (high = stiff, low = smooth roll)")
                .defineInRange("metacentricScale", 0.85, 0.0, 5.0);
        LINEAR_DAMPING = builder.comment("Vertical hydrodynamic drag")
                .defineInRange("linearDamping", 250.0, 0.0, 5000.0);
        ANGULAR_DAMPING = builder.comment("Rotational hydrodynamic drag")
                .defineInRange("angularDamping", 1800.0, 0.0, 10000.0);
        LINEAR_DRAG_MU = builder.comment(
                        "Viscous (linear) planar water drag coefficient [N*s/m].",
                        "Quadratic drag alone vanishes as v->0 (infinite glide); this",
                        "linear term guarantees the ship actually comes to a stop.")
                .defineInRange("linearDragMu", 80.0, 0.0, 5000.0);
        QUADRATIC_DRAG_MU = builder.comment(
                        "Form (quadratic) planar water drag coefficient [N*s^2/m^2],",
                        "dominant at cruising speed.")
                .defineInRange("quadraticDragMu", 150.0, 0.0, 5000.0);
        ANGULAR_DRAG_MU = builder.comment(
                        "Rotational water damping [N*m*s/rad] opposing yaw/roll/pitch",
                        "angular velocity so turns settle predictably.")
                .defineInRange("angularDragMu", 300.0, 0.0, 20000.0);
        STEER_TORQUE_COEFF = builder.comment(
                        "Rudder steering authority [N*m*s/m]: tau = -coeff * v_fwd * sin(theta)",
                        "per submerged rudder, applied as pure local yaw torque",
                        "(Aeronautics-style stabilized turning - no parasitic roll/pitch).")
                .defineInRange("steerTorqueCoeff", 800.0, 0.0, 20000.0);
        builder.pop();

        builder.push("Sailing");
        // Raw sail math outputs ~240 N per sail at 7 m/s wind. A realistic raft
        // masses ~18 t (planks at 600 kg/block), which would take minutes to get
        // moving. This multiplier scales the Newton output into playable
        // accelerations; 40 gives roughly 0.5 m/s^2 on a small plank raft.
        SAIL_THRUST_MULTIPLIER = builder.comment(
                        "Multiplier applied to the aerodynamic thrust (in Newtons) before it is",
                        "converted to an impulse (impulse = force * physics timestep).",
                        "Sable's pre-physics event fires at high sub-step rates (60-120 Hz),",
                        "so keep this small - large values accumulate impulses per sub-step",
                        "and launch ships into orbit.")
                .defineInRange("sailThrustMultiplier", 1.0, 0.0, 50.0);
        MIN_WIND_SPEED = builder.comment(
                        "Baseline true wind speed in m/s used when Weather2 reports dead calm.",
                        "0.0 = no wind floor; sails produce nothing in a calm.")
                .defineInRange("minWindSpeed", 0.0, 0.0, 50.0);
        ANTIGRAVITY_PROBE = builder.comment(
                        "DIAGNOSTIC: inject an upward linear impulse into every assembled",
                        "sub-level's REAL Rapier rigid body each physics step. If enabled and",
                        "ships rise, the force bridge works; if not, the problem is in Sable",
                        "itself. Leave OFF for normal play.")
                .define("antigravityProbe", false);
        PROBE_LINEAR_IMPULSE = builder.comment(
                        "Magnitude of the diagnostic probe impulse in kg*m/s per physics step.",
                        "3000 on a ~20 t raft gives ~0.15 m/s of upward velocity per step.")
                .defineInRange("probeLinearImpulse", 3000.0, 0.0, 100000.0);
        builder.pop();

        SPEC = builder.build();
    }
}