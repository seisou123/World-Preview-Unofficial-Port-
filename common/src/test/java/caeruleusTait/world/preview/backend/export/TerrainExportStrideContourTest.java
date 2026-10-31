package caeruleusTait.world.preview.backend.export;

import caeruleusTait.world.preview.backend.terrain.ContourRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interpolated-height contour artifact tests: sampling every stride-th pixel
 * and interpolating between integer nodes turns node-scale terrain
 * oscillation into per-cell bumps, and every bump straddling a contour level
 * renders as an isolated ring or cross locked to the stride lattice -- on a
 * real stride-4 export, a regular field of dots covering whole oceans. The
 * bumps are the interpolation's shape, so the fix renders onto a copy and
 * keeps only contour components that connect into larger structures.
 */
class TerrainExportStrideContourTest {

    private static final int SIZE = 64;
    private static final int STRIDE = 4;
    private static final int GRID = SIZE / STRIDE;
    private static final int BASE = 0xFF20908C;
    private static final int INTERVAL = 20;

    /** Bilinear + round, the same arithmetic {@code interpolateCoarse} uses. */
    private static short[] interpolate(int[] coarse) {
        short[] heights = new short[SIZE * SIZE];
        for (int row = 0; row < SIZE; row++) {
            final int gz = row / STRIDE;
            final int fz = row - gz * STRIDE;
            final int gz1 = Math.min(gz + 1, GRID - 1);
            for (int col = 0; col < SIZE; col++) {
                final int gx = col / STRIDE;
                final int fx = col - gx * STRIDE;
                final int gx1 = Math.min(gx + 1, GRID - 1);
                final long w00 = (long) (STRIDE - fx) * (STRIDE - fz);
                final long w10 = (long) fx * (STRIDE - fz);
                final long w01 = (long) (STRIDE - fx) * fz;
                final long w11 = (long) fx * fz;
                final long sum = (long) coarse[gz * GRID + gx] * w00
                        + (long) coarse[gz * GRID + gx1] * w10
                        + (long) coarse[gz1 * GRID + gx] * w01
                        + (long) coarse[gz1 * GRID + gx1] * w11;
                heights[row * SIZE + col] = (short) Math.round((double) sum / (w00 + w10 + w01 + w11));
            }
        }
        return heights;
    }

    /** Ocean-floor-like node field oscillating +-2 around the minor level 44. */
    private static int[] oscillatingNodes() {
        int[] coarse = new int[GRID * GRID];
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                coarse[z * GRID + x] = 44 + (int) Math.round(2 * Math.sin(x * 0.7) * Math.cos(z * 0.9));
            }
        }
        return coarse;
    }

    private static void render(short[] heights, int[] colors) {
        java.util.Arrays.fill(colors, BASE);
        new ContourRenderer(INTERVAL, true, 0xC08B4513, 0x608B6914)
                .render(heights, colors, SIZE, SIZE);
    }

    /** Connected components of at most 64 contour pixels: the rings and crosses. */
    private static int smallComponents(int[] contoured, int[] base) {
        int size = contoured.length;
        boolean[] seen = new boolean[size];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        int small = 0;
        for (int start = 0; start < size; start++) {
            if (seen[start] || contoured[start] == base[start]) {
                continue;
            }
            queue.add(start);
            seen[start] = true;
            int component = 0;
            while (!queue.isEmpty()) {
                int idx = queue.poll();
                component++;
                int x = idx % SIZE;
                int y = idx / SIZE;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE) {
                            continue;
                        }
                        int n = ny * SIZE + nx;
                        if (!seen[n] && contoured[n] != base[n]) {
                            seen[n] = true;
                            queue.add(n);
                        }
                    }
                }
            }
            if (component <= 64) {
                small++;
            }
        }
        return small;
    }

    @Test
    void cleanupRemovesTheLatticeRingsButKeepsTheNetwork() {
        int[] coarse = oscillatingNodes();
        short[] heights = interpolate(coarse);

        int[] target = new int[SIZE * SIZE];
        java.util.Arrays.fill(target, BASE);
        int[] contoured = target.clone();
        render(heights, contoured);
        int before = smallComponents(contoured, target);
        assertTrue(before >= 10,
                "the oscillating node field must reproduce the lattice rings (got " + before + ")");

        TerrainMapExporter.copyLargeContourComponents(contoured, target, SIZE, SIZE, 64, () -> false);
        // The function only writes large components back into target; the
        // contoured copy itself is left untouched.
        int kept = 0;
        for (int i = 0; i < target.length; i++) {
            if (target[i] != BASE) {
                kept++;
            }
        }
        assertEquals(0, kept, "nothing is copied back when all components are small");
    }

    @Test
    void cleanupKeepsLargeConnectedContours() {
        int[] target = new int[SIZE * SIZE];
        java.util.Arrays.fill(target, BASE);
        int[] contoured = target.clone();
        // A full-width horizontal line (large component) plus an isolated 5px cross.
        int lineY = 32;
        for (int x = 0; x < SIZE; x++) {
            contoured[lineY * SIZE + x] = 0xFF8B4513;
        }
        int cx = 10;
        int cy = 10;
        contoured[cy * SIZE + cx] = 0xFF8B4513;
        contoured[(cy - 1) * SIZE + cx] = 0xFF8B4513;
        contoured[(cy + 1) * SIZE + cx] = 0xFF8B4513;
        contoured[cy * SIZE + cx - 1] = 0xFF8B4513;
        contoured[cy * SIZE + cx + 1] = 0xFF8B4513;

        TerrainMapExporter.copyLargeContourComponents(contoured, target, SIZE, SIZE, 32, () -> false);
        int kept = 0;
        for (int i = 0; i < target.length; i++) {
            if (target[i] != BASE) {
                kept++;
            }
        }
        assertEquals(SIZE, kept, "the connected line survives, the isolated cross is dropped");
        assertEquals(BASE, target[cy * SIZE + cx], "the cross center is dropped");
        assertEquals(0xFF8B4513, target[lineY * SIZE + 5], "the line is kept");
    }

    @Test
    void cleanupHandlesTwoComponentsWithSharedColors() {
        // Two separate rings of the same blended color: both dropped, and the
        // base color underneath each is restored verbatim.
        int[] target = new int[8 * 8];
        java.util.Arrays.fill(target, BASE);
        int[] contoured = target.clone();
        int[] ringAt = {1, 4};
        for (int at : ringAt) {
            int x0 = at;
            int y0 = 2;
            for (int x = x0; x < x0 + 3; x++) {
                contoured[y0 * 8 + x] = 0xFF8B6914;
                contoured[(y0 + 2) * 8 + x] = 0xFF8B6914;
            }
            contoured[(y0 + 1) * 8 + x0] = 0xFF8B6914;
            contoured[(y0 + 1) * 8 + x0 + 2] = 0xFF8B6914;
        }
        TerrainMapExporter.copyLargeContourComponents(contoured, target, 8, 8, 64, () -> false);
        for (int i = 0; i < target.length; i++) {
            assertEquals(BASE, target[i], "both rings are below the threshold and dropped at " + i);
        }
    }
}
