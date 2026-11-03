package com.serenesailing.physics;

import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

/**
 * Integration layer around {@link ShipPhysicsEngine}: gathers hull blocks from a
 * {@link SubLevelHandle}, evaluates the physics and applies the resulting impulses.
 *
 * <p><b>Status: not wired into the game.</b> Nothing in the mod's runtime calls this
 * class; it is exercised only by unit tests. The live ship physics runs in
 * {@link com.serenesailing.event.ServerPhysicsHandler}, which has its own separate,
 * hand-rolled implementation. This class together with {@link ShipPhysicsEngine},
 * {@link WaterlineProvider}, {@link SailingPhysicsConfig} and {@link BlockMassTable}
 * forms a self-contained alternative engine that is kept for reference.
 * Changes here do <b>not</b> affect gameplay; change {@code ServerPhysicsHandler} for that.
 */
public class SailingPhysicsEngine {

    private final SailingPhysicsConfig config;
    private final WaterlineProvider waterlineProvider;

    public SailingPhysicsEngine(SailingPhysicsConfig config, WaterlineProvider waterlineProvider) {
        this.config = config;
        this.waterlineProvider = waterlineProvider;
    }

    public ShipPhysicsEngine.PhysicsState tick(SubLevelHandle subLevel, double worldTimeSeconds) {
        if (subLevel == null || !subLevel.isSimulated()) return null;

        return tick(subLevel, gatherHullBlocks(subLevel), worldTimeSeconds);
    }

    /**
     * Evaluates physics for an already-gathered hull and applies the resulting
     * impulses. Split out from {@link #tick(SubLevelHandle, double)} so it can be
     * tested without Minecraft BlockStates.
     */
    ShipPhysicsEngine.PhysicsState tick(SubLevelHandle subLevel,
                                        List<ShipPhysicsEngine.BlockNode> hullBlocks,
                                        double worldTimeSeconds) {
        if (hullBlocks.isEmpty()) return null;

        Vector3d linearVelocity = subLevel.getLinearVelocity();
        Vector3d angularVelocity = subLevel.getAngularVelocity();

        Vector3d sailPosition = subLevel.getSailPosition();
        Vector3d sailNormal = subLevel.getSailNormal();
        double sailArea = subLevel.getSailArea();

        Vector3d windVector = resolveWind(subLevel, worldTimeSeconds);
        org.joml.Quaterniondc orientation = subLevel.getOrientation();

        ShipPhysicsEngine.PhysicsState state = ShipPhysicsEngine.evaluatePhysics(
                hullBlocks,
                linearVelocity,
                angularVelocity,
                sailPosition,
                sailNormal,
                windVector,
                sailArea,
                waterlineProvider,
                worldTimeSeconds,
                orientation,
                config
        );

        // JOML's mul() mutates in place. Scale copies so the returned state keeps
        // totalForce (N) and totalTorque (N*m) rather than being turned into impulses.
        double dt = subLevel.getDeltaTime();
        subLevel.applyImpulseAt(new Vector3d(state.totalForce).mul(dt), state.centerOfMass);
        subLevel.applyAngularImpulse(new Vector3d(state.totalTorque).mul(dt));

        return state;
    }

    private List<ShipPhysicsEngine.BlockNode> gatherHullBlocks(SubLevelHandle subLevel) {
        List<ShipPhysicsEngine.BlockNode> blocks = new ArrayList<>();
        for (SubLevelHandle.BlockSample sample : subLevel.getBlocks()) {
            double mass = BlockMassTable.getBlockMass(sample.state());
            blocks.add(new ShipPhysicsEngine.BlockNode(sample.localPos(), sample.worldPos(), mass));
        }
        return blocks;
    }

    private Vector3d resolveWind(SubLevelHandle subLevel, double worldTimeSeconds) {
        return new Vector3d(config.fallbackWindX, 0.0, config.fallbackWindZ);
    }

    public interface SubLevelHandle {
        boolean isSimulated();
        Vector3d getLinearVelocity();
        Vector3d getAngularVelocity();
        Vector3d getSailPosition();
        Vector3d getSailNormal();
        double getSailArea();
        double getDeltaTime();
        List<BlockSample> getBlocks();
        default org.joml.Quaterniondc getOrientation() {
            return new org.joml.Quaterniond();
        }
        void applyImpulseAt(Vector3d impulse, Vector3d point);
        void applyAngularImpulse(Vector3d torqueImpulse);

        record BlockSample(
            Vector3d localPos, 
            Vector3d worldPos, 
            net.minecraft.world.level.block.state.BlockState state
        ) {}
    }
}
