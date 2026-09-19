package com.serenesailing.physics;

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
            Vector3d sailNormal,      // 5th parameter (sail facing direction)
            Vector3d windVector,
            double sailArea,
            WaterlineProvider waterline,
            double worldTimeSeconds,
            SailingPhysicsConfig config
    ) {
        PhysicsState state = new PhysicsState();

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

        // 3. Righting Torque
        if (state.submergedVolume > 0) {
            Vector3d buoyancyOffset = new Vector3d(state.centerOfBuoyancy).sub(state.centerOfMass);
            Vector3d rightingTorque = new Vector3d(buoyancyOffset).cross(buoyantForce).mul(config.metacentricTorqueScale);
            state.totalTorque.add(rightingTorque);
        }

        // 4. Sail Force with Directional Alignment
        if (sailArea > 0 && windVector.lengthSquared() > 0) {
            double windSpeed = windVector.length();
            Vector3d windDir = new Vector3d(windVector).normalize();
            
            // Align force with sail normal vector: cos(windAngle) clamped to non-negative
            double alignment = Math.max(0.0, windDir.dot(sailNormal));
            double sailForceMag = sailArea * (windSpeed * windSpeed) * config.sailDragCoefficient * alignment;
            Vector3d sailForce = new Vector3d(windDir).mul(sailForceMag);

            state.totalForce.add(sailForce);

            Vector3d sailLever = new Vector3d(sailPosition).sub(state.centerOfMass);
            Vector3d heelingTorque = new Vector3d(sailLever).cross(sailForce);
            state.totalTorque.add(heelingTorque);
        }

        // 5. Hydrodynamic Damping
        if (state.submergedVolume > 0) {
            Vector3d linearDrag = new Vector3d(linearVelocity)
                    .mul(-config.linearDampingCoeff * linearVelocity.length());
            
            Vector3d angularDrag = new Vector3d(angularVelocity)
                    .mul(-config.angularDampingCoeff * angularVelocity.length());

            state.totalForce.add(linearDrag);
            state.totalTorque.add(angularDrag);
        }

        return state;
    }
}