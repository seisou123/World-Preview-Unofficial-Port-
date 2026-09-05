package caeruleusTait.world.preview.backend.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Height-field unit tests for the terrain exporter.
 * <p>
 * The export height field stores blocks above the dimension's yMin so the full
 * world Y range survives: the old absolute-Y byte clamp flattened mountains
 * above y=255 and the sea floor below y=0 in the overworld.
 * </p>
 */
class TerrainMapExporterTest {

    private static final int OW_YMIN = -64;
    private static final int OW_SPAN = 384;

    @Test
    void offsetMapsFullOverworldRange() {
        assertEquals(0, TerrainMapExporter.toHeightFieldOffset(-64, OW_YMIN, OW_SPAN));
        assertEquals(64, TerrainMapExporter.toHeightFieldOffset(0, OW_YMIN, OW_SPAN));
        assertEquals(129, TerrainMapExporter.toHeightFieldOffset(65, OW_YMIN, OW_SPAN));
        assertEquals(OW_SPAN, TerrainMapExporter.toHeightFieldOffset(320, OW_YMIN, OW_SPAN));
    }

    @Test
    void offsetClampsOutOfRangeHeights() {
        assertEquals(0, TerrainMapExporter.toHeightFieldOffset(-1000, OW_YMIN, OW_SPAN));
        assertEquals(OW_SPAN, TerrainMapExporter.toHeightFieldOffset(1000, OW_YMIN, OW_SPAN));
    }

    @Test
    void netherStyleRangeStartsAtZero() {
        assertEquals(0, TerrainMapExporter.toHeightFieldOffset(0, 0, 256));
        assertEquals(128, TerrainMapExporter.toHeightFieldOffset(128, 0, 256));
        assertEquals(255, TerrainMapExporter.toHeightFieldOffset(255, 0, 256));
    }

    @Test
    void graySpansFullRangeMonotonically() {
        assertEquals(0, TerrainMapExporter.grayForOffset(0, OW_SPAN));
        assertEquals(255, TerrainMapExporter.grayForOffset(OW_SPAN, OW_SPAN));
        int prev = -1;
        for (int offset = 0; offset <= OW_SPAN; offset++) {
            int gray = TerrainMapExporter.grayForOffset(offset, OW_SPAN);
            assertTrue(gray >= prev, "gray must be monotonic in offset");
            assertTrue(gray >= 0 && gray <= 255);
            prev = gray;
        }
    }

    @Test
    void seaLevelMapsToAboutOneThirdGray() {
        // Overworld sea level y=63 sits 127 blocks above yMin=-64, i.e. a third
        // of the 384-block dimension range: full-range grayscale puts it at ~84.
        int offset = TerrainMapExporter.toHeightFieldOffset(63, OW_YMIN, OW_SPAN);
        int gray = TerrainMapExporter.grayForOffset(offset, OW_SPAN);
        assertTrue(gray > 70 && gray < 100, "sea level gray = " + gray);
    }
}
