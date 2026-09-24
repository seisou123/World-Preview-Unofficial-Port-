package caeruleusTait.world.preview.backend.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arithmetic tests for {@link HeightmapWorkUnit#cellScan}, the pure Y-scan
 * geometry of the fast heightmap path (no Minecraft bootstrap needed).
 *
 * <p>Grid conventions follow vanilla {@code NoiseChunk}:
 * {@code cellCountY = floorDiv(noiseHeight, cellHeight)}, cell index 0 is the
 * cell containing {@code noiseMinY} and {@code selectCellYZ} indexes the slice
 * arrays directly, so a cell index outside {@code [0, cellCountY)} would throw.
 *
 * <p>The regression at the centre of this class: the pre-fix arithmetic
 * ({@code cellCountY = floorDiv(maxY - minY, cellHeight)}) dropped the topmost
 * PARTIAL cell, so with the default visual range 32..255 the scan stopped at
 * y=239 (cellHeight 16) and surfaces between 240 and 255 were never sampled.
 */
class HeightmapWorkUnitCellScanTest {

    /** Overworld-like noise grid: minY -64, height 384. */
    private static final int NOISE_MIN_Y = -64;
    private static final int NOISE_HEIGHT = 384;

    /**
     * Grid invariants every non-empty scan must hold: the cell span covers the
     * whole effective range, never leaves the noise grid, and
     * {@code cellY + cellOffsetY} stays inside the grid's cell index range for
     * every scanned cellY.
     */
    private static void assertScanCoversRangeInsideGrid(
            HeightmapWorkUnit.CellScan scan, int noiseMinY, int noiseHeight, int cellHeight) {
        final int gridCellStart = Math.floorDiv(noiseMinY, cellHeight);
        final int gridCellCount = Math.floorDiv(noiseHeight, cellHeight);

        assertTrue(scan.cellOffset() >= 0, "cellOffsetY must stay >= 0: " + scan);
        assertTrue(scan.cellOffset() + scan.cellCount() <= gridCellCount,
                "cellY + cellOffsetY must stay inside the grid for every scanned cellY: " + scan);

        if (scan.cellCount() > 0) {
            assertEquals(gridCellStart + scan.cellOffset(), scan.cellStart(),
                    "cellOffsetY must map the first scanned cell onto the grid: " + scan);
            assertTrue(scan.cellStart() >= gridCellStart, "first cell below the grid: " + scan);
            assertTrue(scan.cellStart() * cellHeight <= scan.effMinY(),
                    "first cell must start at or below effMinY: " + scan);
            assertTrue((scan.cellStart() + scan.cellCount()) * cellHeight - 1 >= scan.effMaxY(),
                    "last cell must reach up to effMaxY: " + scan);
            assertTrue((scan.cellStart() + scan.cellCount()) * cellHeight - 1
                            <= (gridCellStart + gridCellCount) * cellHeight - 1,
                    "scan must not overshoot the grid top: " + scan);
        }
    }

    @Test
    void defaultVisualRangeNowIncludesTheTopPartialCell() {
        // Default config: 32..255, cellHeight 16 -> the pre-fix scan covered
        // y 32..239 (13 cells); the fixed scan must cover the whole band up to
        // and including y=255 (14 cells).
        HeightmapWorkUnit.CellScan scan =
                HeightmapWorkUnit.cellScan(32, 255, true, NOISE_MIN_Y, NOISE_HEIGHT, 16);

        assertEquals(32, scan.effMinY());
        assertEquals(255, scan.effMaxY());
        assertEquals(2, scan.cellStart());
        assertEquals(Math.floorDiv(255 - 32, 16) + 1, scan.cellCount(),
                "exactly one cell (the top partial one) must be added over the pre-fix count");
        assertEquals(14, scan.cellCount());
        assertEquals(6, scan.cellOffset());
        assertEquals(255, (scan.cellStart() + scan.cellCount()) * 16 - 1,
                "the topmost sampled y must reach the configured maximum");

        assertScanCoversRangeInsideGrid(scan, NOISE_MIN_Y, NOISE_HEIGHT, 16);
    }

    @Test
    void defaultVisualRangeWithOverworldCellHeight() {
        // Realistic overworld cellHeight is 8: pre-fix the scan stopped at
        // y=247 (27 cells from cell 4); the fix must reach y=255 (28 cells).
        HeightmapWorkUnit.CellScan scan =
                HeightmapWorkUnit.cellScan(32, 255, true, NOISE_MIN_Y, NOISE_HEIGHT, 8);

        assertEquals(4, scan.cellStart());
        assertEquals(28, scan.cellCount());
        assertEquals(255, (scan.cellStart() + scan.cellCount()) * 8 - 1);
        assertEquals(12, scan.cellOffset());

        assertScanCoversRangeInsideGrid(scan, NOISE_MIN_Y, NOISE_HEIGHT, 8);
    }

    @Test
    void nonAlignedBoundsIncludePartialCellsOnBothEnds() {
        // 40..250: bottom cell 2 (32..47) starts below effMinY, top cell 15
        // (240..255) ends above effMaxY - both partial cells must be scanned,
        // with the per-y skip expected to discard the overshoot.
        HeightmapWorkUnit.CellScan scan =
                HeightmapWorkUnit.cellScan(40, 250, true, NOISE_MIN_Y, NOISE_HEIGHT, 16);

        assertEquals(40, scan.effMinY());
        assertEquals(250, scan.effMaxY());
        assertEquals(2, scan.cellStart());
        assertEquals(15, scan.cellStart() + scan.cellCount() - 1, "top partial cell must be included");
        assertEquals(14, scan.cellCount());
        assertTrue(scan.cellStart() * 16 < scan.effMinY(), "bottom cell must be partial");
        assertTrue((scan.cellStart() + scan.cellCount()) * 16 - 1 > scan.effMaxY(), "top cell must be partial");

        assertScanCoversRangeInsideGrid(scan, NOISE_MIN_Y, NOISE_HEIGHT, 16);
    }

    @Test
    void nonAlignedBoundsJustAboveACellBoundary() {
        // 33..257: 257 sits inside cell 16 (256..271); the pre-fix formula
        // floorDiv(257-33, 16) = 14 cells ended at y=255 and missed it.
        HeightmapWorkUnit.CellScan scan =
                HeightmapWorkUnit.cellScan(33, 257, true, NOISE_MIN_Y, NOISE_HEIGHT, 16);

        assertEquals(33, scan.effMinY());
        assertEquals(257, scan.effMaxY());
        assertEquals(2, scan.cellStart());
        assertEquals(16, scan.cellStart() + scan.cellCount() - 1);
        assertEquals(15, scan.cellCount());
        assertEquals(Math.floorDiv(257, 16), scan.cellStart() + scan.cellCount() - 1,
                "the cell containing effMaxY must be the last scanned cell");

        assertScanCoversRangeInsideGrid(scan, NOISE_MIN_Y, NOISE_HEIGHT, 16);
    }

    @Test
    void cfgMinBelowTheNoiseFloorIsClamped() {
        // The settings UI accepts down to -64; anything below must clamp to the
        // grid bottom (-64) instead of producing a negative cell index (the
        // pre-fix arithmetic computed cellOffsetY = -3 here, which would make
        // selectCellYZ index outside the slice arrays).
        HeightmapWorkUnit.CellScan scan =
                HeightmapWorkUnit.cellScan(-100, 100, true, NOISE_MIN_Y, NOISE_HEIGHT, 16);

        assertEquals(-64, scan.effMinY(), "effMinY must clamp to the grid bottom");
        assertEquals(100, scan.effMaxY());
        assertEquals(-4, scan.cellStart());
        assertEquals(0, scan.cellOffset());

        assertScanCoversRangeInsideGrid(scan, NOISE_MIN_Y, NOISE_HEIGHT, 16);
    }

    @Test
    void cfgMaxAboveTheNoiseCeilingIsClamped() {
        HeightmapWorkUnit.CellScan scan =
                HeightmapWorkUnit.cellScan(32, 5000, true, NOISE_MIN_Y, NOISE_HEIGHT, 16);

        assertEquals(319, scan.effMaxY(), "effMaxY must clamp to the grid top");
        assertEquals(32, scan.effMinY());
        assertEquals(19, scan.cellStart() + scan.cellCount() - 1,
                "the last scanned cell must be the top grid cell (no index out of the grid)");

        assertScanCoversRangeInsideGrid(scan, NOISE_MIN_Y, NOISE_HEIGHT, 16);
    }

    @Test
    void fullModeMatchesThePreFixArithmeticExactly() {
        // onlySampleInVisualRange=false must sample the aligned noise grid with
        // no per-y skip, byte-for-byte what the pre-fix code scanned; the cfg
        // bounds are ignored entirely.
        for (int cfgMinY : new int[] {-5000, -64, 32, 1234}) {
            for (int cfgMaxY : new int[] {255, 9999}) {
                HeightmapWorkUnit.CellScan scan = HeightmapWorkUnit.cellScan(
                        cfgMinY, cfgMaxY, false, NOISE_MIN_Y, NOISE_HEIGHT, 16);

                assertEquals(Math.floorDiv(NOISE_MIN_Y, 16), scan.cellStart());
                assertEquals(Math.floorDiv(NOISE_HEIGHT, 16), scan.cellCount());
                assertEquals(0, scan.cellOffset());
                assertEquals(scan.cellStart() * 16, scan.effMinY(), "no bottom skip in full mode");
                assertEquals((scan.cellStart() + scan.cellCount()) * 16 - 1, scan.effMaxY(),
                        "no top skip in full mode");

                assertScanCoversRangeInsideGrid(scan, NOISE_MIN_Y, NOISE_HEIGHT, 16);
            }
        }
    }

    @Test
    void degenerateRangesYieldAnEmptyScan() {
        // Entirely above the grid.
        HeightmapWorkUnit.CellScan above =
                HeightmapWorkUnit.cellScan(320, 400, true, NOISE_MIN_Y, NOISE_HEIGHT, 16);
        assertEquals(0, above.cellCount());

        // Inverted bounds (config normalization prevents this, but the helper
        // must stay safe).
        HeightmapWorkUnit.CellScan inverted =
                HeightmapWorkUnit.cellScan(300, 200, true, NOISE_MIN_Y, NOISE_HEIGHT, 16);
        assertEquals(0, inverted.cellCount());

        assertScanCoversRangeInsideGrid(above, NOISE_MIN_Y, NOISE_HEIGHT, 16);
        assertScanCoversRangeInsideGrid(inverted, NOISE_MIN_Y, NOISE_HEIGHT, 16);
    }

    @Test
    void gridNotDivisibleByCellHeightClampsToTheGridExtent() {
        // Vanilla NoiseChunk only has floorDiv(height, cellHeight) cells; the
        // effective range must clamp to the grid's block extent (here 0..95),
        // not to noiseMinY + noiseHeight - 1 (99), so selectCellYZ never sees an
        // out-of-grid cell index.
        HeightmapWorkUnit.CellScan scan = HeightmapWorkUnit.cellScan(0, 99, true, 0, 100, 16);

        assertEquals(95, scan.effMaxY());
        assertEquals(0, scan.effMinY());
        assertEquals(5, scan.cellStart() + scan.cellCount() - 1);
        assertEquals(6, scan.cellCount());

        assertScanCoversRangeInsideGrid(scan, 0, 100, 16);
    }

    @Test
    void matrixOfRangesAndCellHeightsHoldsTheGridInvariants() {
        int[][] ranges = {
                {32, 255}, {40, 250}, {33, 257}, {-100, 100}, {32, 5000},
                {-5000, 5000}, {200, 255}, {-64, -1}, {0, 0}, {320, 400}, {300, 200},
        };
        for (int cellHeight : new int[] {8, 16}) {
            for (int[] range : ranges) {
                for (boolean onlyVisual : new boolean[] {true, false}) {
                    HeightmapWorkUnit.CellScan scan = HeightmapWorkUnit.cellScan(
                            range[0], range[1], onlyVisual, NOISE_MIN_Y, NOISE_HEIGHT, cellHeight);
                    assertScanCoversRangeInsideGrid(scan, NOISE_MIN_Y, NOISE_HEIGHT, cellHeight);
                }
            }
        }
    }
}
