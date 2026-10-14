package com.serenesailing.physics;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import java.util.List;

public class ShipPhysicsEngine {

    public static class BlockNode {
        public Vector3d localPos;
        public Vector3d worldPos;
        public double volume = 1.0;
        public double mass = 100.0;

        public BlockNode(Vector3d localPos, Vector3d worldPos, double mass) {
            this.localPos = localPos;
            this.worldPos = worldPos;
            this.mass = mass;
        }
    }

    public static class PhysicsState {
        public Vector3d totalForce = new Vector3d();
        public Vector3d totalTorque = new Vector3d();
        public Vector3d centerOfMass = new Vector3d();
        public Vector3d centerOfBuoyancy = new Vector3d();
        public Quaterniond orientation = new Quaterniond();
        public double submergedVolume = 0.0;
        public double totalMass = 0.0;
    }

    private static final double GRAVITY = 9.81;
    private static final double WATER_DENSITY = 1000.0;

    /**
     * Evaluates net buoyancy, gravity, sail forces, righting torques, and hydrodynamic drag.
     */
    public static PhysicsState evaluatePhysics(
            List<BlockNode> hullBlocks,
            Vector3d linearVelocity,
            Vector3d angularVelocity,
            Vector3d sailPosition,
            Vector3d sailNormal,
            Vector3d windVector,
            double sailArea,
            WaterlineProvider waterline,
            double worldTimeSeconds,
            SailingPhysicsConfig config
    ) {
        return evaluatePhysics(
                hullBlocks,
                linearVelocity,
                angularVelocity,
                sailPosition,
                sailNormal,
                windVector,
                sailArea,
                waterline,
                worldTimeSeconds,
                new Quaterniond(),
                config
        );
    }

    /**
     * Evaluates net buoyancy, gravity, sail forces, righting torques, and hydrodynamic drag with orientation.
     */
    public static PhysicsState evaluatePhysics(
            List<BlockNode> hullBlocks,
            Vector3d linearVelocity,
            Vector3d angularVelocity,
            Vector3d sailPosition,
            Vector3d sailNormal,      // 5th parameter (sail facing direction)
            Vector3d windVector,
            double sailArea,
            WaterlineProvider waterline,
            double worldTimeSeconds,
            Quaterniondc orientation,
            SailingPhysicsConfig config
    ) {
        PhysicsState state = new PhysicsState();
        if (orientation != null) {
            state.orientation.set(orientation);
        }

        if (hullBlocks.isEmpty()) return state;

        // 1. Mass & Buoyancy accumulation
        Vector3d massWeightedPosSum = new Vector3d();
        Vector3d buoyWeightedPosSum = new Vector3d();

        for (BlockNode block : hullBlocks) {
            state.totalMass += block.mass;
            massWeightedPosSum.add(new Vector3d(block.worldPos).mul(block.mass));

            double waterY = waterline.getWaterHeight(block.worldPos.x, block.worldPos.z, worldTimeSeconds);
            double blockBottom = block.worldPos.y - 0.5;
            double blockTop = block.worldPos.y + 0.5;

            double submergedFraction = 0.0;
            if (blockTop <= waterY) {
                submergedFraction = 1.0;
            } else if (blockBottom < waterY) {
                submergedFraction = (waterY - blockBottom) / (blockTop - blockBottom);
            }

            if (submergedFraction > 0.0) {
                double subVol = block.volume * submergedFraction;
                state.submergedVolume += subVol;
                buoyWeightedPosSum.add(new Vector3d(block.worldPos).mul(subVol));
            }
        }

        if (state.totalMass > 0) {
            state.centerOfMass = massWeightedPosSum.div(state.totalMass);
            // Virtual Keel Ballast Bias: Lower effective CoM to simulate low-hull ballast
            state.centerOfMass.y -= 1.2;
        }

        if (state.submergedVolume > 0) {
            state.centerOfBuoyancy = buoyWeightedPosSum.div(state.submergedVolume);
        }

        // 2. Gravity & Buoyancy Forces
        Vector3d gravityForce = new Vector3d(0, -state.totalMass * GRAVITY, 0);
        double netBuoyancyMagnitude = WATER_DENSITY * GRAVITY * state.submergedVolume * config.buoyancyScalar;
        Vector3d buoyantForce = new Vector3d(0, netBuoyancyMagnitude, 0);

        state.totalForce.add(gravityForce);
        state.totalForce.add(buoyantForce);

        // 3. Righting Torque (orientation-based upright-restoring alignment torque)
        if (state.submergedVolume > 0) {
            // Vector pointing from world UP to ship local UP
            Vector3d shipUp = new Vector3d(0.0, 1.0, 0.0).rotate(state.orientation);
            Vector3d worldUp = new Vector3d(0.0, 1.0, 0.0);

            // Torque axis perpendicular to tilt
            Vector3d rightingAxis = new Vector3d(shipUp).cross(worldUp);

            // Magnitude proportional to submerged volume and metacentric scale
            double rightingMagnitude = state.submergedVolume * WATER_DENSITY * GRAVITY * config.metacentricTorqueScale;
            Vector3d rightingTorque = rightingAxis.mul(rightingMagnitude);
            state.totalTorque.add(rightingTorque);
        }

        // 4. Sail Force with Directional Alignment
        //
        // FIX (Task 2): Forward thrust is applied at the waterline / hull
        // center of mass, NOT at the elevated sail position. This eliminates
        // the pitch torque lever arm that was ramming the bow underwater
        // (pitchpoling). Lateral heeling torque is computed from the real
        // sail height but clamped so it cannot exceed the righting moment
        // at ~12 degrees of heel, preventing knockdown.
        if (sailArea > 0 && windVector.lengthSquared() > 0) {
            double windSpeed = windVector.length();
            Vector3d windDir = new Vector3d(windVector).normalize();

            // Align force with sail normal vector: cos(windAngle) clamped to non-negative
            double alignment = Math.max(0.0, windDir.dot(sailNormal));
            double sailForceMag = sailArea * (windSpeed * windSpeed) * config.sailDragCoefficient * alignment;
            Vector3d sailForce = new Vector3d(windDir).mul(sailForceMag);

            // Apply the linear force at CoM (no torque from linear thrust)
            state.totalForce.add(sailForce);

            Vector3d sailLever = new Vector3d(sailPosition).sub(state.centerOfMass);
            sailLever.x = 0.0;
            Vector3d heelingTorque = new Vector3d(sailLever).cross(sailForce);
            double sailYawTorque = 0.0;
            heelingTorque.y = sailYawTorque;
            state.totalTorque.add(heelingTorque);
        }

        // 5. Hydrodynamic Damping
        // FIX (Bug 4): Use planar XZ velocity length for linear drag and
        // per-axis angular rates for angular drag. The old code used the full
        // 3D velocity.length() which coupled vertical bobbing into horizontal
        // deceleration and cross-coupled pitch/roll into yaw damping.
        if (state.submergedVolume > 0) {
            double planarSpeed = Math.sqrt(
                    linearVelocity.x * linearVelocity.x + linearVelocity.z * linearVelocity.z);
            Vector3d linearDrag = new Vector3d(linearVelocity)
                    .mul(-config.linearDampingCoeff * planarSpeed);
            // Zero out the vertical drag component — let gravity/buoyancy handle Y.
            linearDrag.y = 0.0;
            
            // Per-axis angular damping: each axis is damped by its own rate,
            // preventing roll/pitch from artificially damping yaw and vice versa.
            Vector3d angularDrag = new Vector3d(
                    -config.angularDampingCoeff * angularVelocity.x * Math.abs(angularVelocity.x),
                    -config.angularDampingCoeff * angularVelocity.y * Math.abs(angularVelocity.y),
                    -config.angularDampingCoeff * angularVelocity.z * Math.abs(angularVelocity.z));

            state.totalForce.add(linearDrag);
            state.totalTorque.add(angularDrag);
        }

        return state;
    }
}