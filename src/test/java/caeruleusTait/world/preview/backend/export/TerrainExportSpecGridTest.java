package caeruleusTait.world.preview.backend.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainExportSpecGridTest {

    @Test
    void gridDisabledIsAccepted() {
        TerrainExportSpec spec = new TerrainExportSpec(4096, 4, 0, 0, true, 10, 0);
        assertEquals(0, spec.gridIntervalBlocks());
    }

    @Test
    void minimumGridIntervalIsAccepted() {
        TerrainExportSpec spec = new TerrainExportSpec(4096, 4, 0, 0, true, 10,
                TerrainExportSpec.MIN_GRID_INTERVAL_BLOCKS);
        assertEquals(16, spec.gridIntervalBlocks());
    }

    @Test
    void maximumGridIntervalIsAccepted() {
        TerrainExportSpec spec = new TerrainExportSpec(4096, 4, 0, 0, true, 10,
                TerrainExportSpec.MAX_GRID_INTERVAL_BLOCKS);
        assertEquals(4096, spec.gridIntervalBlocks());
    }

    @Test
    void gridIntervalBelowMinimumIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainExportSpec(4096, 4, 0, 0, true, 10, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainExportSpec(4096, 4, 0, 0, true, 10, -16));
    }

    @Test
    void gridIntervalAboveMaximumIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainExportSpec(4096, 4, 0, 0, true, 10, 5000));
    }

    @Test
    void sixArgumentCompatConstructorDefaultsGridToZero() {
        TerrainExportSpec spec = new TerrainExportSpec(4096, 4, 100, -200, true, 10);
        assertEquals(0, spec.gridIntervalBlocks());
        assertEquals(100, spec.centerX());
        assertEquals(-200, spec.centerZ());
        assertTrue(spec.exportContours());
        assertEquals(10, spec.contourInterval());
    }

    @Test
    void imageWidthMathUnchangedByGridComponent() {
        TerrainExportSpec withoutGrid = new TerrainExportSpec(4096, 4, 0, 0);
        TerrainExportSpec withGrid = new TerrainExportSpec(4096, 4, 0, 0, true, 10, 256);
        assertEquals(2048, withoutGrid.imageWidth());
        assertEquals(withoutGrid.imageWidth(), withGrid.imageWidth());
        assertEquals(withoutGrid.imageHeight(), withGrid.imageHeight());
        assertEquals(withoutGrid.totalWork(), withGrid.totalWork());
        assertEquals(withoutGrid.minBlockX(), withGrid.minBlockX());
        assertEquals(withoutGrid.maxBlockZ(), withGrid.maxBlockZ());
    }
}
