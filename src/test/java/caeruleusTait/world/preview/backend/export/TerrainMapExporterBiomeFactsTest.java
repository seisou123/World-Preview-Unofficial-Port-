package caeruleusTait.world.preview.backend.export;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the terrain export's storage-backed biome facts path:
 * run-merged row painting (must be pixel-identical to the per-pixel fillRect
 * loops it replaces), the id-misalignment guards of {@link TerrainMapExporter.IdTableResolver},
 * and the per-category height table extracted from the legacy estimate switch.
 */
class TerrainMapExporterBiomeFactsTest {

    /**
     * int[]-backed {@link TerrainMapExporter.RectSink} emulating
     * {@code NativeImage#fillRect} overwrite (no blending) semantics.
     */
    private static final class MatrixSink implements TerrainMapExporter.RectSink {
        final int width;
        final int height;
        final int[] matrix;
        int rectCount;

        MatrixSink(int width, int height) {
            this.width = width;
            this.height = height;
            this.matrix = new int[width * height];
            Arrays.fill(matrix, 0); // sentinel: never painted
        }

        @Override
        public void fillRect(int x, int y, int w, int h, int color) {
            rectCount++;
            for (int py = y; py < y + h; py++) {
                for (int px = x; px < x + w; px++) {
                    matrix[py * width + px] = color;
                }
            }
        }
    }

    /** The per-pixel painting the collect loop used before run merging. */
    private static void paintPerPixel(MatrixSink sink, int[] pixels, int rowOffset,
                                      int startX, int y, int cols, int imageWidth) {
        for (int col = 0; col < cols; col++) {
            int x = startX + col;
            if (x >= imageWidth) break;
            sink.fillRect(x, y, 1, 1, pixels[rowOffset + col]);
        }
    }

    @Test
    void fillRowRunsMatchesPerPixelPainting() {
        Random random = new Random(0xC0FFEE);
        int[] palette = {0xFF112233, 0xFF445566, 0xFF778899, 0xFFAABBCC};

        for (int trial = 0; trial < 500; trial++) {
            int imageWidth = 8 + random.nextInt(48);
            // Tiles start inside the image but may overrun its right edge.
            int startX = random.nextInt(imageWidth);
            int cols = 1 + random.nextInt(imageWidth + 32);
            int y = random.nextInt(16);
            int rowOffset = random.nextInt(8) * 64; // exercise non-zero offsets too

            int[] pixels = new int[rowOffset + cols];
            for (int i = rowOffset; i < pixels.length; i++) {
                pixels[i] = palette[random.nextInt(palette.length)];
            }

            MatrixSink merged = new MatrixSink(imageWidth, 16);
            TerrainMapExporter.fillRowRuns(merged, pixels, rowOffset, startX, y, cols, imageWidth);

            MatrixSink perPixel = new MatrixSink(imageWidth, 16);
            paintPerPixel(perPixel, pixels, rowOffset, startX, y, cols, imageWidth);

            assertTrue(Arrays.equals(perPixel.matrix, merged.matrix),
                    "trial " + trial + ": run-merged painting must be pixel-identical"
                            + " (startX=" + startX + ", cols=" + cols + ", imageWidth=" + imageWidth + ")");
        }
    }

    @Test
    void fillRowRunsMergesUniformRowIntoOneRect() {
        int cols = 64;
        int[] pixels = new int[cols];
        Arrays.fill(pixels, 0xFF010203);

        MatrixSink sink = new MatrixSink(cols, 1);
        TerrainMapExporter.fillRowRuns(sink, pixels, 0, 0, 0, cols, cols);

        assertEquals(1, sink.rectCount, "uniform row must merge into a single fillRect");
        assertEquals(0xFF010203, sink.matrix[0]);
        assertEquals(0xFF010203, sink.matrix[cols - 1]);
    }

    @Test
    void fillRowRunsClipsEdgeTileOverrun() {
        // Row of 10 pixels starting at x=6 of a 12-wide image: the clipped
        // uniform run paints x=6..11 as one rect.
        int[] pixels = new int[10];
        Arrays.fill(pixels, 0xFF323232);

        MatrixSink sink = new MatrixSink(12, 1);
        TerrainMapExporter.fillRowRuns(sink, pixels, 0, 6, 0, 10, 12);

        assertEquals(1, sink.rectCount, "clipped uniform run must merge into a single fillRect");
        for (int x = 0; x < 12; x++) {
            assertEquals(x >= 6 ? 0xFF323232 : 0, sink.matrix[x],
                    "pixels left of startX stay untouched; pixels from startX to the edge are painted");
        }
    }

    @Test
    void fillRowRunsSkipsWhenStartXBeyondImage() {
        MatrixSink sink = new MatrixSink(8, 1);
        TerrainMapExporter.fillRowRuns(sink, new int[4], 0, 8, 0, 4, 8);
        TerrainMapExporter.fillRowRuns(sink, new int[4], 0, 100, 0, 4, 8);
        assertEquals(0, sink.rectCount, "fully clipped rows must not emit any rect");
    }

    @Test
    void idTableResolverGuardsMisalignedIds() {
        TerrainMapExporter.IdTableResolver resolver = new TerrainMapExporter.IdTableResolver(
                new TerrainCategory[]{TerrainCategory.DEEP_OCEAN, null, TerrainCategory.OCEAN},
                new byte[]{30, 0, 50});

        assertFalse(resolver.known((short) -1), "negative storage id must not be trusted");
        assertFalse(resolver.known((short) 3), "out-of-range id must not be trusted");
        assertFalse(resolver.known((short) 1), "null table slot (unregistered biome) must not be trusted");
        assertTrue(resolver.known((short) 0));
        assertTrue(resolver.known((short) 2));

        assertEquals(TerrainCategory.UNKNOWN, resolver.category((short) 1), "unknown id classifies as UNKNOWN");
        assertEquals(TerrainCategory.OCEAN, resolver.category((short) 2));
        assertEquals(TerrainClassifier.categoryHeight(TerrainCategory.UNKNOWN),
                resolver.estimatedHeight((short) 1), "unknown id estimates like UNKNOWN");
        assertEquals(50, resolver.estimatedHeight((short) 2));
    }

    @Test
    void categoryHeightMatchesLegacyEstimateLevels() {
        // Pins the switch extracted from TerrainMapExporter.estimateHeight so
        // the per-id table cannot silently drift from the fallback path.
        // PEAK is (byte) 160 == -96: byte arithmetic identical to the legacy
        // switch (whose value was widened to int and clamped to offset 0).
        assertEquals((byte) 30, TerrainClassifier.categoryHeight(TerrainCategory.DEEP_OCEAN));
        assertEquals((byte) 50, TerrainClassifier.categoryHeight(TerrainCategory.OCEAN));
        assertEquals((byte) 55, TerrainClassifier.categoryHeight(TerrainCategory.RIVER));
        assertEquals((byte) 63, TerrainClassifier.categoryHeight(TerrainCategory.BEACH));
        assertEquals((byte) 70, TerrainClassifier.categoryHeight(TerrainCategory.PLAINS));
        assertEquals((byte) 75, TerrainClassifier.categoryHeight(TerrainCategory.FOREST));
        assertEquals((byte) 90, TerrainClassifier.categoryHeight(TerrainCategory.HILLS));
        assertEquals((byte) 120, TerrainClassifier.categoryHeight(TerrainCategory.MOUNTAIN));
        assertEquals((byte) 160, TerrainClassifier.categoryHeight(TerrainCategory.PEAK));
        assertEquals((byte) 70, TerrainClassifier.categoryHeight(TerrainCategory.UNKNOWN));
    }
}
