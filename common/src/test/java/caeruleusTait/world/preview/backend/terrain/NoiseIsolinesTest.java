package caeruleusTait.world.preview.backend.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the isoline overlay on the noise views.
 * <p>
 * The lines must mark band boundaries of the same normalised space the noise
 * palette uses, must not appear inside a single band, must never be drawn from
 * an un-sampled neighbour, and must stay a subtle darkening rather than a hard
 * black stroke.
 * </p>
 */
class NoiseIsolinesTest {

    private static final int BASE = 0xFF808080;
    private static final int BANDS = 8;
    /** Raw sample of Short.MAX_VALUE maps to the top of the range. */
    private static final int MAX = Short.MAX_VALUE;
    private static final int MIN = -Short.MAX_VALUE;

    @Test
    void bandSpansTheFullRange() {
        assertEquals(0, NoiseIsolines.band(MIN, BANDS));
        assertEquals(BANDS - 1, NoiseIsolines.band(MAX, BANDS));
        assertEquals(0, NoiseIsolines.band(Short.MIN_VALUE + 1, BANDS));
    }

    @Test
    void bandIsMonotonic() {
        int previous = -1;
        for (int i = 0; i <= 100; i++) {
            int value = (int) (MIN + (long) (MAX - MIN) * i / 100);
            int band = NoiseIsolines.band(value, BANDS);
            assertTrue(band >= previous, "band must not decrease as the value rises: " + band + " < " + previous);
            previous = band;
        }
    }

    @Test
    void bandsAreEvenlySpaced() {
        // Eight bands over [-MAX, MAX]: each band is 2*MAX/8 = 8191.75 wide, so
        // the first boundary sits between MIN+8191 and MIN+8192.
        final int step = Math.round(2f * MAX / BANDS);
        assertEquals(0, NoiseIsolines.band(MIN, BANDS));
        assertEquals(1, NoiseIsolines.band(MIN + step, BANDS));
        assertEquals(2, NoiseIsolines.band(MIN + 2 * step, BANDS));
        assertEquals(0, NoiseIsolines.band(MIN + step - 2, BANDS),
                "just below the first boundary must still be band 0");
        assertEquals(0, NoiseIsolines.band(MIN + step - 1, BANDS),
                "the boundary sits at -24575.25, so -24576 is still band 0");
    }

    @Test
    void sameBandDrawsNoLine() {
        int value = MIN + 100;
        assertEquals(BASE, NoiseIsolines.apply(BASE, value, value + 10, value + 10, BANDS));
    }

    @Test
    void crossingInEitherDirectionDrawsALine() {
        int step = Math.round(2f * MAX / BANDS);
        int low = MIN + step / 4;
        int high = MIN + step + step / 4;

        int byEast = NoiseIsolines.apply(BASE, low, high, low, BANDS);
        int bySouth = NoiseIsolines.apply(BASE, low, low, high, BANDS);
        assertTrue(byEast != BASE, "crossing the band to the east must draw a line");
        assertEquals(byEast, bySouth, "both directions use the same line colour");
        assertTrue(luminance(byEast) < luminance(BASE), "the line must darken the field colour");
    }

    @Test
    void unknownValueSkipsTheOverlay() {
        assertEquals(BASE, NoiseIsolines.apply(BASE, NoiseIsolines.UNKNOWN, MAX, MAX, BANDS));
    }

    @Test
    void unknownNeighbourCannotDrawALine() {
        int low = MIN + 100;
        assertEquals(BASE, NoiseIsolines.apply(BASE, low, NoiseIsolines.UNKNOWN, NoiseIsolines.UNKNOWN, BANDS));
    }

    @Test
    void lineStaysSubtle() {
        int step = Math.round(2f * MAX / BANDS);
        int low = MIN + step / 4;
        int high = MIN + step + step / 4;
        int line = NoiseIsolines.apply(BASE, low, high, low, BANDS);
        // A 0.42 mix of a near-black line over mid grey must stay well above black.
        assertTrue(luminance(line) > 60, "isoline must remain a tint, got luminance=" + luminance(line));
        assertTrue(luminance(line) < luminance(BASE), "isoline must still be visibly darker");
    }

    @Test
    void degenerateBandCountsAreClamped() {
        assertEquals(0, NoiseIsolines.band(MAX, 0));
        assertEquals(0, NoiseIsolines.band(MAX, -5));
        assertEquals(0, NoiseIsolines.band(MIN, 1));
    }

    private static int luminance(int argb) {
        return (((argb >> 16) & 0xFF) * 299 + ((argb >> 8) & 0xFF) * 587 + (argb & 0xFF) * 114) / 1000;
    }
}
