package caeruleusTait.world.preview.domain.preview.accuracy;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lattice sizes match historical sampler strides without loading Minecraft classes:
 * FullQuart stride 4 → 4×4; Quarter stride 8 → 2×2; Single stride 16 → 1.
 */
class HeightSampleLatticeAlignmentTest {
    @Test
    void fullQuart_stride4() {
        assertLattice(0, 0, 4, 16);
    }

    @Test
    void quarterQuart_stride8() {
        assertLattice(1, 2, 8, 4);
    }

    @Test
    void singleQuart_stride16() {
        assertLattice(-3, 5, 16, 1);
    }

    private static void assertLattice(int chunkX, int chunkZ, int stride, int expectedCount) {
        List<int[]> points = HeightSampleSpec.blockPositionsInChunk(chunkX, chunkZ, stride);
        assertEquals(expectedCount, points.size());
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        Set<Long> seen = new HashSet<>();
        for (int[] xz : points) {
            assertTrue(xz[0] >= minX && xz[0] < minX + 16);
            assertTrue(xz[1] >= minZ && xz[1] < minZ + 16);
            assertEquals(0, (xz[0] - minX) % stride);
            assertEquals(0, (xz[1] - minZ) % stride);
            seen.add((((long) xz[0]) << 32) ^ (xz[1] & 0xffffffffL));
        }
        assertEquals(expectedCount, seen.size());
    }
}
