package caeruleusTait.world.preview.backend.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arithmetic tests for {@link HeightmapWorkUnit#heightScan}, the pure Y-scan
 * geometry of the fast heightmap path (no Minecraft bootstrap needed).
 *
 * <p>26.3 removed the NoiseChunk cell grid (the compiled sampler tree handles
 * interpolation internally), so the scan geometry reduced to: the configured
 * heightmap range clipped to the noise grid's block extent
 * {@code [noiseMinY, noiseMinY + noiseHeight - 1]}.
 */
class HeightmapWorkUnitHeightScanTest {

    /** Overworld-like noise grid: minY -64, height 384 (extent -64..319). */
    private static final int NOISE_MIN_Y = -64;
    private static final int NOISE_HEIGHT = 384;
    private static final int GRID_BOTTOM_Y = NOISE_MIN_Y;
    private static final int GRID_TOP_Y = NOISE_MIN_Y + NOISE_HEIGHT - 1;

    /** Every non-empty visual scan must sit inside the grid and cover the clamped range. */
    private static void assertScanInsideGrid(HeightmapWorkUnit.HeightScan scan, boolean onlyVisual) {
        if (scan.effMinY() > scan.effMaxY()) {
            return; // empty
        }
        assertTrue(scan.effMinY() >= GRID_BOTTOM_Y, "effMinY below grid: " + scan);
        assertTrue(scan.effMaxY() <= GRID_TOP_Y, "effMaxY above grid: " + scan);
        if (onlyVisual) {
            assertEquals(Math.max(scan.effMinY(), GRID_BOTTOM_Y), scan.effMinY(),
                    "visual scans are clamped to the grid by construction: " + scan);
        }
    }

    @Test
    void defaultVisualRangeIsTheConfiguredBand() {
        HeightmapWorkUnit.HeightScan scan =
                HeightmapWorkUnit.heightScan(32, 255, true, NOISE_MIN_Y, NOISE_HEIGHT);

        assertEquals(32, scan.effMinY());
        assertEquals(255, scan.effMaxY());
        assertScanInsideGrid(scan, true);
    }

    @Test
    void cfgMinBelowTheNoiseFloorIsClamped() {
        HeightmapWorkUnit.HeightScan scan =
                HeightmapWorkUnit.heightScan(-100, 100, true, NOISE_MIN_Y, NOISE_HEIGHT);

        assertEquals(GRID_BOTTOM_Y, scan.effMinY(), "effMinY must clamp to the grid bottom");
        assertEquals(100, scan.effMaxY());
        assertScanInsideGrid(scan, true);
    }

    @Test
    void cfgMaxAboveTheNoiseCeilingIsClamped() {
        HeightmapWorkUnit.HeightScan scan =
                HeightmapWorkUnit.heightScan(32, 5000, true, NOISE_MIN_Y, NOISE_HEIGHT);

        assertEquals(GRID_TOP_Y, scan.effMaxY(), "effMaxY must clamp to the grid top");
        assertEquals(32, scan.effMinY());
        assertScanInsideGrid(scan, true);
    }

    @Test
    void fullModeIgnoresTheConfiguredBounds() {
        for (int cfgMinY : new int[] {-5000, -64, 32, 1234}) {
            for (int cfgMaxY : new int[] {255, 9999}) {
                HeightmapWorkUnit.HeightScan scan = HeightmapWorkUnit.heightScan(
                        cfgMinY, cfgMaxY, false, NOISE_MIN_Y, NOISE_HEIGHT);

                assertEquals(GRID_BOTTOM_Y, scan.effMinY(), "full mode scans the whole grid: " + scan);
                assertEquals(GRID_TOP_Y, scan.effMaxY(), "full mode scans the whole grid: " + scan);
                assertScanInsideGrid(scan, false);
            }
        }
    }

    @Test
    void degenerateRangesYieldAnEmptyScan() {
        // Entirely above the grid.
        HeightmapWorkUnit.HeightScan above =
                HeightmapWorkUnit.heightScan(320, 400, true, NOISE_MIN_Y, NOISE_HEIGHT);
        assertTrue(above.effMinY() > above.effMaxY(), "320..400 is above the grid top: " + above);

        // Inverted bounds (config normalization prevents this, but the helper
        // must stay safe).
        HeightmapWorkUnit.HeightScan inverted =
                HeightmapWorkUnit.heightScan(300, 200, true, NOISE_MIN_Y, NOISE_HEIGHT);
        assertTrue(inverted.effMinY() > inverted.effMaxY(), "inverted bounds must be empty: " + inverted);
    }

    @Test
    void gridHeightNotReachingTheConfiguredTopClamps() {
        // noise 0..99, cfg 0..99 visual: extent is 0..99 (height 100 fits
        // exactly); a cfg above 99 must clamp to 99.
        HeightmapWorkUnit.HeightScan scan = HeightmapWorkUnit.heightScan(0, 150, true, 0, 100);

        assertEquals(0, scan.effMinY());
        assertEquals(99, scan.effMaxY());
    }

    @Test
    void matrixOfRangesHoldsTheClampInvariants() {
        int[][] ranges = {
                {32, 255}, {40, 250}, {33, 257}, {-100, 100}, {32, 5000},
                {-5000, 5000}, {200, 255}, {-64, -1}, {0, 0}, {320, 400}, {300, 200},
        };
        for (int[] range : ranges) {
            for (boolean onlyVisual : new boolean[] {true, false}) {
                HeightmapWorkUnit.HeightScan scan = HeightmapWorkUnit.heightScan(
                        range[0], range[1], onlyVisual, NOISE_MIN_Y, NOISE_HEIGHT);
                assertScanInsideGrid(scan, onlyVisual);
                if (onlyVisual) {
                    int expectedMin = Math.max(range[0], GRID_BOTTOM_Y);
                    int expectedMax = Math.min(range[1], GRID_TOP_Y);
                    if (expectedMin > expectedMax) {
                        assertTrue(scan.effMinY() > scan.effMaxY(),
                                "out-of-grid band must be empty: " + scan);
                    } else {
                        assertEquals(expectedMin, scan.effMinY(), "range=" + range + ": " + scan);
                        assertEquals(expectedMax, scan.effMaxY(), "range=" + range + ": " + scan);
                    }
                }
            }
        }
    }
}
