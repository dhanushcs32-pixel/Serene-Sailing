package com.serenesailing.physics;

import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

public class SailingPhysicsEngine {

    private final SailingPhysicsConfig config;
    private final WaterlineProvider waterlineProvider;

    public SailingPhysicsEngine(SailingPhysicsConfig config, WaterlineProvider waterlineProvider) {
        this.config = config;
        this.waterlineProvider = waterlineProvider;
    }

    public ShipPhysicsEngine.PhysicsState tick(SubLevelHandle subLevel, double worldTimeSeconds) {
        if (subLevel == null || !subLevel.isSimulated()) return null;

        List<ShipPhysicsEngine.BlockNode> hullBlocks = gatherHullBlocks(subLevel);
        if (hullBlocks.isEmpty()) return null;

        Vector3d linearVelocity = subLevel.getLinearVelocity();
        Vector3d angularVelocity = subLevel.getAngularVelocity();

        Vector3d sailPosition = subLevel.getSailPosition();
        Vector3d sailNormal = subLevel.getSailNormal();
        double sailArea = subLevel.getSailArea();

        Vector3d windVector = resolveWind(subLevel, worldTimeSeconds);

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
                config
        );

        double dt = subLevel.getDeltaTime();
        subLevel.applyImpulseAt(state.totalForce.mul(dt), state.centerOfMass);
        subLevel.applyAngularImpulse(state.totalTorque.mul(dt));

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
        void applyImpulseAt(Vector3d impulse, Vector3d point);
        void applyAngularImpulse(Vector3d torqueImpulse);

        record BlockSample(
            Vector3d localPos, 
            Vector3d worldPos, 
            net.minecraft.world.level.block.state.BlockState state
        ) {}
    }
}