package com.serenesailing.physics;

public class SailingPhysicsConfig {
    public double buoyancyScalar = 1.0;           // Tune buoyancy strength against Sable physics
    public double metacentricTorqueScale = 0.85;  // Higher = stiffer righting, Lower = rolls smoother
    public double sailDragCoefficient = 1.2;      // Sail wind energy capture factor
    public double linearDampingCoeff = 250.0;     // Opposes vertical bobbing speed
    public double angularDampingCoeff = 1800.0;   // Opposes roll/pitch velocity
    public boolean weather2WindIntegration = false; // toggle: Weather 2 wind vs constant fallback
    public double fallbackWindX = 5.0;              // m/s, constant test wind
    public double fallbackWindZ = 0.0;
}