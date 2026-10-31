package caeruleusTait.world.preview.backend.export;

import caeruleusTait.world.preview.backend.terrain.ContourRenderer;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Contour overlay equivalence tests: the full-image render that replaced the
 * two-row strip loop must stay byte-identical to a local copy of the strip
 * reference on random and plateaued height fields, must leave the height
 * field untouched, and a cancelled overlay must not blend anything.
 */
class TerrainExportContourOverlayTest {

    /** The strip implementation overlayContours used before the full-image render. */
    private static void referenceStrips(short[] heights, int[] colors, int width, int height, int interval) {
        short[] stripHeights = new short[width * 2];
        int[] stripColors = new int[width * 2];
        int[] intervals = {Math.max(1, interval / 5), interval};
        int[] lineColors = {0x608B6914, 0xC08B4513};
        for (int pass = 0; pass < intervals.length; pass++) {
            ContourRenderer renderer = new ContourRenderer(intervals[pass], false,
                    lineColors[pass], lineColors[pass]);
            for (int y = 0; y < height - 1; y++) {
                System.arraycopy(heights, y * width, stripHeights, 0, stripHeights.length);
                System.arraycopy(colors, y * width, stripColors, 0, stripColors.length);
                renderer.render(stripHeights, stripColors, width, 2);
                System.arraycopy(stripColors, 0, colors, y * width, stripColors.length);
            }
        }
    }

    /** Random heights with plateaus: flat runs exercise the no-contour skip too. */
    private static short[] syntheticHeights(int width, int height, long seed) {
        Random random = new Random(seed);
        short[] heights = new short[width * height];
        short value = (short) random.nextInt(384);
        int run = 0;
        for (int i = 0; i < heights.length; i++) {
            if (run <= 0) {
                value = (short) random.nextInt(384);
                run = random.nextInt(4);
            }
            run--;
            heights[i] = value;
        }
        return heights;
    }

    private static int[] syntheticColors(int width, int height, long seed) {
        Random random = new Random(seed);
        int[] colors = new int[width * height];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = 0xFF000000 | random.nextInt(0x1000000);
        }
        return colors;
    }

    @Test
    void fullImageRenderMatchesTheStripReference() {
        for (int[] size : new int[][]{{37, 53}, {128, 128}, {256, 256}}) {
            int width = size[0];
            int height = size[1];
            short[] heights = syntheticHeights(width, height, 42L + width);
            int[] expected = syntheticColors(width, height, 7L + width);
            int[] actual = expected.clone();
            short[] heightsRead = heights.clone();
            referenceStrips(heights, expected, width, height, 10);
            TerrainMapExporter.overlayContours(heightsRead, actual, width, height, 10, () -> false);
            assertArrayEquals(expected, actual,
                    "full-image render must be byte-identical to the strips at " + width + "x" + height);
            assertArrayEquals(heights, heightsRead, "heights are read-only");
        }
    }

    @Test
    void cancellationLeavesColorsUntouched() {
        short[] heights = syntheticHeights(64, 64, 1L);
        int[] colors = syntheticColors(64, 64, 2L);
        int[] before = colors.clone();
        assertThrows(CancellationException.class,
                () -> TerrainMapExporter.overlayContours(heights, colors, 64, 64, 10, () -> true));
        assertArrayEquals(before, colors, "a cancelled overlay must not blend anything");
    }
}
