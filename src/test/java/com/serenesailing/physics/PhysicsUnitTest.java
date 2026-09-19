package com.serenesailing.physics;

import com.serenesailing.physics.ShipPhysicsEngine.BlockNode;
import com.serenesailing.physics.ShipPhysicsEngine.PhysicsState;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PhysicsUnitTest {

    @Test
    public void testSymmetricBoxFloatingEquilibrium() {
        SailingPhysicsConfig config = new SailingPhysicsConfig();
        List<BlockNode> hull = new ArrayList<>();

        // 3x1x3 Oak Plank raft centered at Y=62.7 (80% submerged in Y=63.0 water)
        // Oak density = 600 kg/m^3. Equilibrium depth is 60% submerged.
        // At 80% submerged, buoyant force exceeds gravity, producing a net UPWARD force (+Y).
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                hull.add(new BlockNode(
                    new Vector3d(x, 0, z), 
                    new Vector3d(x, 62.7, z), 
                    600.0
                ));
            }
        }

        PhysicsState state = ShipPhysicsEngine.evaluatePhysics(
                hull,
                new Vector3d(0, 0, 0),
                new Vector3d(0, 0, 0),
                new Vector3d(0, 1, 0),
                new Vector3d(0, 0, 1),
                new Vector3d(0, 0, 0),
                0.0,
                WaterlineProvider.FLAT_SEA,
                0.0,
                config
        );

        // 9 blocks * 600 kg = 5400 kg total mass
        assertEquals(5400.0, state.totalMass, 0.01);

        // Block spans Y=[62.2, 63.2]. Water at 63.0 submerged fraction = (63.0 - 62.2) / 1.0 = 0.8
        // Total submerged volume = 9 * 0.8 = 7.2 m^3
        assertEquals(7.2, state.submergedVolume, 0.01);

        // At 80% submerged, net force (Buoyancy - Gravity) must push upward (+Y)
        assertTrue(state.totalForce.y > 0, "Net force should act upward when submerged past equilibrium");
        
        // Symmetric raft on flat water must have zero net roll/pitch torque
        assertEquals(0.0, state.totalTorque.length(), 0.001, "Symmetric raft should experience zero torque");
    }
}