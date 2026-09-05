package caeruleusTait.world.preview.backend.terrain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for contour rendering on short height fields.
 * <p>
 * Heights are world-block units; the fields below based above 255 pin the
 * removal of the old byte height field that flattened all high terrain
 * (contour lines vanished above the clamp).
 * </p>
 */
class ContourRendererTest {

    private static final int W = 48;
    private static final int H = 32;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MAJOR = 0xC08B4513;
    private static final int MINOR = 0x608B6914;

    @Test
    void flatFieldDrawsNoContours() {
        short[] heights = new short[W * H];
        Arrays.fill(heights, (short) 300);
        int[] colors = new int[W * H];
        Arrays.fill(colors, WHITE);

        new ContourRenderer(10, false, MAJOR, MINOR).render(heights, colors, W, H);

        for (int i = 0; i < colors.length; i++) {
            assertEquals(WHITE, colors[i], "flat field must stay untouched at index " + i);
        }
    }

    @Test
    void contoursAppearAboveLegacyByteRange() {
        // Ramp 240 -> 334 along X: levels 250..330 are crossed, most of them above 255
        short[] heights = new short[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                heights[y * W + x] = (short) (240 + 2 * x);
            }
        }
        int[] colors = new int[W * H];
        Arrays.fill(colors, WHITE);

        new ContourRenderer(10, false, MAJOR, MINOR).render(heights, colors, W, H);

        int changedAbove255 = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 16; x < W; x++) { // heights there are >= 272
                if (colors[y * W + x] != WHITE) {
                    changedAbove255++;
                }
            }
        }
        assertTrue(changedAbove255 > 0,
                "contour lines must render above the old 255 clamp, got " + changedAbove255);
    }
}
