package com.serenesailing.sail;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Connected-component flood-fill helper for sails.
 * Decoupled from Block/Level initialization so propagation limits,
 * diagonal traversal, and bounding box guards can be tested without
 * Minecraft bootstrap.
 */
public final class SailPropagationHelper {

    /** Maximum safety cap for the connected-sail-sheet flood-fill (expanded for massive course sails / tall-ships). */
    public static final int MAX_SAIL_BLOCKS = 1024;
    public static final int MAX_SHEET_SIZE = MAX_SAIL_BLOCKS;
    /** Search bounding box radius (+-48 blocks) from the clicked origin. */
    public static final int MAX_SEARCH_RADIUS = 48;

    private SailPropagationHelper() {}

    /**
     * Breadth-first search over all face-adjacent and vertical-planar diagonal sail blocks
     * (dx in [-1, 0, 1], dy in [-1, 0, 1], dz in [-1, 0, 1] within distance <= 1 in each axis)
     * within a +-48 block bounding box from origin, capped at MAX_SAIL_BLOCKS (1024).
     */
    public static List<BlockPos> findConnectedSailPositions(BlockPos origin,
                                                            int cap,
                                                            Predicate<BlockPos> sailPredicate) {
        List<BlockPos> sheet = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        visited.add(origin);

        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();

        while (!queue.isEmpty() && sheet.size() < cap) {
            BlockPos current = queue.poll();
            sheet.add(current);

            // Explore 6 cardinal directions AND vertical-planar diagonal neighbors
            // (dx in [-1, 0, 1], dy in [-1, 0, 1], dz in [-1, 0, 1]) within distance <= 1 in each axis.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (visited.contains(neighbor)) {
                            continue;
                        }

                        // Bounding box radius guard (+-48 blocks from clicked origin)
                        if (Math.abs(neighbor.getX() - ox) > MAX_SEARCH_RADIUS
                                || Math.abs(neighbor.getY() - oy) > MAX_SEARCH_RADIUS
                                || Math.abs(neighbor.getZ() - oz) > MAX_SEARCH_RADIUS) {
                            continue;
                        }
                        visited.add(neighbor);

                        if (sailPredicate.test(neighbor)) {
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
        return sheet;
    }
}

