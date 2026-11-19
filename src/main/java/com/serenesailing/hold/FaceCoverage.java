package com.serenesailing.hold;

/**
 * How much of each face of a block's cell is covered by its collision shape, on a 16 x 16 grid per face. Pure Java.
 *
 * <p>Vanilla fluids decide whether water passes from one cell to the next by checking whether the UNION of the two
 * blocks' faces on the shared boundary covers it completely ({@code Shapes.mergedFaceOccludes}); neither block has to
 * cover it alone. A bottom slab next to a top slab, or stairs interlocking with a block, is therefore watertight, while
 * a fence post next to a slab is not. A model that only asks "does one block fully cover the face" leaks through every
 * hull built from stairs and slabs.
 *
 * <p>Face order, matching {@link HoldFlood}: 0 = -X, 1 = +X, 2 = -Y, 3 = +Y, 4 = -Z, 5 = +Z. A face grid is indexed
 * {@code u * 16 + v}, with (u, v) = (y, z) for X faces, (x, z) for Y faces and (x, y) for Z faces, so the two blocks that
 * share a boundary use the same coordinates. 256 bits = 4 longs.
 */
public final class FaceCoverage {

    public static final int WORDS = 4;
    private static final double EPS = 1.0e-4;

    private FaceCoverage() {
    }

    /**
     * @param boxes the block's collision boxes in block-local coordinates [0, 1]: {minX, minY, minZ, maxX, maxY, maxZ}
     * @return per face the covered cells; a box covers a face only if it reaches that face's plane
     */
    public static long[][] fromBoxes(double[][] boxes) {
        final long[][] faces = new long[6][WORDS];
        for (final double[] b : boxes) {
            if (b[0] <= EPS) mark(faces[0], b[1], b[4], b[2], b[5]);       // -X: u = y, v = z
            if (b[3] >= 1.0 - EPS) mark(faces[1], b[1], b[4], b[2], b[5]); // +X
            if (b[1] <= EPS) mark(faces[2], b[0], b[3], b[2], b[5]);       // -Y: u = x, v = z
            if (b[4] >= 1.0 - EPS) mark(faces[3], b[0], b[3], b[2], b[5]); // +Y
            if (b[2] <= EPS) mark(faces[4], b[0], b[3], b[1], b[4]);       // -Z: u = x, v = y
            if (b[5] >= 1.0 - EPS) mark(faces[5], b[0], b[3], b[1], b[4]); // +Z
        }
        return faces;
    }

    private static void mark(long[] bits, double u0, double u1, double v0, double v1) {
        final int a0 = clamp((int) Math.round(u0 * 16.0)), a1 = clamp((int) Math.round(u1 * 16.0));
        final int b0 = clamp((int) Math.round(v0 * 16.0)), b1 = clamp((int) Math.round(v1 * 16.0));
        for (int u = a0; u < a1; u++) {
            for (int v = b0; v < b1; v++) {
                final int i = u * 16 + v;
                bits[i >> 6] |= 1L << (i & 63);
            }
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(16, v));
    }

    /** Does this single face cover the whole 16 x 16 grid? */
    public static boolean isFull(long[] bits) {
        for (final long w : bits) {
            if (w != -1L) {
                return false;
            }
        }
        return true;
    }

    /** Do the two faces together cover the whole boundary between their cells? */
    public static boolean unionIsFull(long[] a, long[] b) {
        for (int i = 0; i < WORDS; i++) {
            if ((a[i] | b[i]) != -1L) {
                return false;
            }
        }
        return true;
    }

    /** Bit {@code face} of the result is set if that face is covered completely by the block alone. */
    public static int fullMask(long[][] faces) {
        int mask = 0;
        for (int f = 0; f < 6; f++) {
            if (isFull(faces[f])) {
                mask |= 1 << f;
            }
        }
        return mask;
    }
}

