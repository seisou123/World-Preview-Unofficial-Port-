// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.export;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grid arithmetic of the live terrain-export height sampler.
 * <p>
 * The export walks a pixel grid anchored at an arbitrary block coordinate with
 * a fixed step, while the noise sampler only yields heights per cell of a
 * 4-block lattice. The two grids are generally misaligned, so the collector has
 * to find exactly the requested columns belonging to each cell. A mistake here
 * does not crash: it silently leaves pixels on the biome-based estimate (the
 * seam this sampler exists to remove) or, worse, lets one pixel overwrite
 * another.
 * <p>
 * These tests therefore assert the property that matters -- every grid cell is
 * visited exactly once across all cells -- over a range of misalignments and
 * steps, instead of hard-coding expected coordinates.
 */
class NoiseHeightSamplerGridTest {

    private static final int CELL_WIDTH = 4;

    /** Runs the collector over every cell the grid touches and returns the visited indices. */
    private static Set<Integer> collectAll(int originX, int originZ, int step, int width, int height) {
        final int maxX = originX + (width - 1) * step;
        final int maxZ = originZ + (height - 1) * step;
        final int firstCellX = Math.floorDiv(originX, CELL_WIDTH);
        final int lastCellX = Math.floorDiv(maxX, CELL_WIDTH);
        final int firstCellZ = Math.floorDiv(originZ, CELL_WIDTH);
        final int lastCellZ = Math.floorDiv(maxZ, CELL_WIDTH);

        final int cap = (CELL_WIDTH / step + 2) * (CELL_WIDTH / step + 2);
        final int[] xs = new int[cap];
        final int[] zs = new int[cap];
        final double[] dXs = new double[cap];
        final double[] dZs = new double[cap];
        final int[] outIdx = new int[cap];

        final Set<Integer> visited = new java.util.ArrayList<>(width * height).isEmpty()
                ? new HashSet<>() : new HashSet<>();
        int duplicates = 0;

        for (int cx = firstCellX; cx <= lastCellX; cx++) {
            for (int cz = firstCellZ; cz <= lastCellZ; cz++) {
                final int count = NoiseHeightSampler.collectCellColumns(
                        cx * CELL_WIDTH, cz * CELL_WIDTH, CELL_WIDTH,
                        step, originX, originZ, maxX, maxZ, width,
                        xs, zs, dXs, dZs, outIdx);
                for (int i = 0; i < count; i++) {
                    if (!visited.add(outIdx[i])) {
                        duplicates++;
                    }
                    assertTrue(outIdx[i] >= 0 && outIdx[i] < width * height,
                            "index " + outIdx[i] + " outside grid of " + (width * height));
                    // The column must map back to the coordinates it was collected at.
                    final int px = (xs[i] - originX) / step;
                    final int pz = (zs[i] - originZ) / step;
                    assertEquals(pz * width + px, outIdx[i],
                            "index does not match its column at (" + xs[i] + "," + zs[i] + ")");
                    // The cell-local interpolation factor must stay inside [0,1).
                    assertTrue(dXs[i] >= 0.0 && dXs[i] < 1.0, "dX out of range: " + dXs[i]);
                    assertTrue(dZs[i] >= 0.0 && dZs[i] < 1.0, "dZ out of range: " + dZs[i]);
                }
            }
        }

        assertEquals(0, duplicates, "a grid cell was visited more than once");
        return visited;
    }

    @Test
    void alignedGridIsFullyCovered() {
        // origin on the cell lattice: the common case for a centred export.
        Set<Integer> visited = collectAll(0, 0, 4, 16, 16);
        assertEquals(16 * 16, visited.size(), "every cell must be visited once");
    }

    @Test
    void misalignedOriginStillCoversEveryCell() {
        for (int offset = 1; offset < CELL_WIDTH; offset++) {
            Set<Integer> visited = collectAll(offset, offset, 4, 13, 11);
            assertEquals(13 * 11, visited.size(),
                    "offset " + offset + " left cells unvisited");
        }
    }

    @Test
    void negativeCoordinatesAreHandled() {
        // Exports centred near the origin can start at negative blocks.
        Set<Integer> visited = collectAll(-4093, -4091, 4, 17, 19);
        assertEquals(17 * 19, visited.size(), "negative origin must not skip cells");
    }

    @Test
    void everySupportedStepCoversTheGrid() {
        // The UI offers 1, 2 and 4 blocks per pixel.
        for (int step : new int[] {1, 2, 4}) {
            for (int offset = 0; offset < step; offset++) {
                Set<Integer> visited = collectAll(offset, offset * 2, step, 9, 7);
                assertEquals(9 * 7, visited.size(),
                        "step " + step + " offset " + offset + " left cells unvisited");
            }
        }
    }

    @Test
    void singleCellGridWorks() {
        Set<Integer> visited = collectAll(7, 9, 4, 1, 1);
        assertEquals(1, visited.size());
    }

    @Test
    void gridSmallerThanOneCellWorks() {
        // A 1x1 export still has to land on exactly one column.
        for (int x = 0; x < CELL_WIDTH; x++) {
            for (int z = 0; z < CELL_WIDTH; z++) {
                Set<Integer> visited = collectAll(x, z, 2, 1, 1);
                assertEquals(1, visited.size(), "single cell at (" + x + "," + z + ") missing");
            }
        }
    }
}