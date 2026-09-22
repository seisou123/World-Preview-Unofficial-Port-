package caeruleusTait.world.preview.backend.terrain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for contour rendering on short height fields.
 * <p>
 * Heights are world-block units; the fields below sit above 255, pinning the
 * removal of the old byte height field that flattened all high terrain
 * (contour lines vanished above the clamp).
 * </p>
 * <p>
 * The landing-point tests below assert <em>where</em> a contour is drawn, not
 * merely that some pixel changed. A contour of a fixed level on a height field
 * that varies only along X is a vertical line at one column, so the marked
 * column must be identical in every row and must equal the bilinearly
 * interpolated crossing. A wrong interpolation edge (or an unclamped
 * interpolation parameter) smears the line along Y and moves the landing
 * point, which the older "some pixel changed" assertion could not see.
 * </p>
 */
class ContourRendererTest {

    private static final int W = 48;
    private static final int H = 32;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MAJOR = 0xC08B4513;
    private static final int MINOR = 0x608B6914;

    private static final int INTERVAL = 10;

    @Test
    void flatFieldDrawsNoContours() {
        short[] heights = new short[W * H];
        Arrays.fill(heights, (short) 300);
        int[] colors = new int[W * H];
        Arrays.fill(colors, WHITE);

        new ContourRenderer(INTERVAL, false, MAJOR, MINOR).render(heights, colors, W, H);

        for (int i = 0; i < colors.length; i++) {
            assertEquals(WHITE, colors[i], "flat field must stay untouched at index " + i);
        }
    }

    @Test
    void contourLandsOnInterpolatedColumnInEveryRow() {
        // Height varies only along X: 240 -> 381, so levels 250..380 are
        // crossed, most of them above the old 255 clamp. Each contour level is
        // a vertical line at a single column, hence the same column in every
        // row.
        final int base = 240;
        final int slope = 3;
        short[] heights = new short[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                heights[y * W + x] = (short) (base + slope * x);
            }
        }
        int[] colors = new int[W * H];
        Arrays.fill(colors, WHITE);

        new ContourRenderer(INTERVAL, false, MAJOR, MINOR).render(heights, colors, W, H);

        final int maxHeight = base + slope * (W - 1);
        Set<Integer> expected = landingColumns(base, slope, maxHeight);

        // Every row must mark exactly the interpolated columns: constant along
        // Y, varying with X. The unclamped/wrong-edge version marks a
        // different, wider column set that also drifts from row to row.
        for (int y = 0; y < H; y++) {
            Set<Integer> marked = new LinkedHashSet<>();
            for (int x = 0; x < W; x++) {
                if (colors[y * W + x] != WHITE) {
                    marked.add(x);
                }
            }
            assertEquals(expected, marked,
                    "contour landing columns in row " + y + " must be the interpolated crossings");
        }

        // The ramp crosses levels above the legacy 255 byte range; those
        // crossings must be drawn too.
        assertTrue(expected.contains(47),
                "the highest contour column (height " + (base + slope * 47) + ") must land at x=47");
    }

    @Test
    void contourLandsOnInterpolatedRowInEveryColumn() {
        // Height varies only along Y: each contour level is a horizontal line
        // at one row, spanning the full width.
        final int base = 100;
        final int slope = 3;
        short[] heights = new short[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                heights[y * W + x] = (short) (base + slope * y);
            }
        }
        int[] colors = new int[W * H];
        Arrays.fill(colors, WHITE);

        new ContourRenderer(INTERVAL, false, MAJOR, MINOR).render(heights, colors, W, H);

        final int maxHeight = base + slope * (H - 1);
        Set<Integer> expectedRows = landingColumns(base, slope, maxHeight);

        for (int y = 0; y < H; y++) {
            boolean rowDrawn = colors[y * W] != WHITE;
            assertEquals(expectedRows.contains(y), rowDrawn,
                    "row " + y + " must be drawn iff a contour level lands there");
            if (rowDrawn) {
                for (int x = 0; x < W; x++) {
                    assertTrue(colors[y * W + x] != WHITE,
                            "a horizontal contour must span the full width (row " + y + ", x " + x + ")");
                }
            }
        }
    }

    /**
     * Columns (or rows, when the ramp runs along Y) at which each contour level
     * of the given linear ramp lands, using the same bilinear rounding as
     * {@code drawEdgePoint}: the crossing lands on the nearer of the two cell
     * corners.
     */
    private static Set<Integer> landingColumns(int base, int slope, int maxHeight) {
        Set<Integer> result = new LinkedHashSet<>();
        int level = ((base / INTERVAL) + 1) * INTERVAL;
        if (level < base) {
            level += INTERVAL;
        }
        for (; level <= maxHeight; level += INTERVAL) {
            double position = (level - base) / (double) slope;
            int floor = (int) position;
            result.add((position - floor) < 0.5 ? floor : floor + 1);
        }
        return result;
    }
}
