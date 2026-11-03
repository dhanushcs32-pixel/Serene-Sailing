package com.serenesailing.physics;

import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test: tick() must apply force*dt / torque*dt as impulses without
 * mutating the totalForce / totalTorque on the PhysicsState it returns.
 */
public class SailingPhysicsEngineTickTest {

    /** Records the impulses it receives; no Minecraft types involved. */
    private static class FakeSubLevel implements SailingPhysicsEngine.SubLevelHandle {
        final double dt;
        Vector3d linearImpulse;
        Vector3d angularImpulse;

        FakeSubLevel(double dt) { this.dt = dt; }

        @Override public boolean isSimulated() { return true; }
        @Override public Vector3d getLinearVelocity() { return new Vector3d(); }
        @Override public Vector3d getAngularVelocity() { return new Vector3d(); }
        @Override public Vector3d getSailPosition() { return new Vector3d(0, 1, 0); }
        @Override public Vector3d getSailNormal() { return new Vector3d(0, 0, 1); }
        @Override public double getSailArea() { return 0.0; }
        @Override public double getDeltaTime() { return dt; }
        @Override public List<BlockSample> getBlocks() { return List.of(); } // unused by the tested overload
        @Override public void applyImpulseAt(Vector3d impulse, Vector3d point) { linearImpulse = new Vector3d(impulse); }
        @Override public void applyAngularImpulse(Vector3d torqueImpulse) { angularImpulse = new Vector3d(torqueImpulse); }
    }

    private static List<ShipPhysicsEngine.BlockNode> raft() {
        List<ShipPhysicsEngine.BlockNode> hull = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                hull.add(new ShipPhysicsEngine.BlockNode(
                        new Vector3d(x, 0, z), new Vector3d(x, 62.7, z), 600.0));
            }
        }
        return hull;
    }

    @Test
    @DisplayName("tick() returns force/torque, not impulses, and applies force*dt")
    public void testTickDoesNotMutateReturnedState() {
        double dt = 0.05;
        SailingPhysicsEngine engine =
                new SailingPhysicsEngine(new SailingPhysicsConfig(), WaterlineProvider.FLAT_SEA);
        FakeSubLevel sub = new FakeSubLevel(dt);
        List<ShipPhysicsEngine.BlockNode> hull = raft();

        ShipPhysicsEngine.PhysicsState state = engine.tick(sub, hull, 0.0);

        assertNotNull(state);
        assertTrue(state.totalForce.y > 0, "Raft is over-submerged, net force should be upward");
        // Applied impulse is the returned force scaled by dt...
        assertEquals(state.totalForce.x * dt, sub.linearImpulse.x, 1e-9);
        assertEquals(state.totalForce.y * dt, sub.linearImpulse.y, 1e-9);
        assertEquals(state.totalForce.z * dt, sub.linearImpulse.z, 1e-9);
        assertEquals(state.totalTorque.x * dt, sub.angularImpulse.x, 1e-9);
        assertEquals(state.totalTorque.y * dt, sub.angularImpulse.y, 1e-9);
        assertEquals(state.totalTorque.z * dt, sub.angularImpulse.z, 1e-9);

        // ...and the returned state still matches a fresh, independent evaluation (i.e. it was NOT scaled by dt).
        ShipPhysicsEngine.PhysicsState expected = ShipPhysicsEngine.evaluatePhysics(
                hull, new Vector3d(), new Vector3d(), new Vector3d(0, 1, 0), new Vector3d(0, 0, 1),
                new Vector3d(5.0, 0, 0), 0.0, WaterlineProvider.FLAT_SEA, 0.0,
                new org.joml.Quaterniond(), new SailingPhysicsConfig());
        assertEquals(expected.totalForce.y, state.totalForce.y, 1e-9,
                "totalForce must stay in newtons after tick()");
    }

    @Test
    @DisplayName("tick() with an empty hull returns null and applies nothing")
    public void testEmptyHull() {
        SailingPhysicsEngine engine =
                new SailingPhysicsEngine(new SailingPhysicsConfig(), WaterlineProvider.FLAT_SEA);
        FakeSubLevel sub = new FakeSubLevel(0.05);
        assertNull(engine.tick(sub, new ArrayList<>(), 0.0));
        assertNull(sub.linearImpulse);
    }
}

