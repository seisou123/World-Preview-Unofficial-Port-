package caeruleusTait.world.preview.backend.terrain;

/**
 * Isolines for the noise views.
 * <p>
 * A continuous colour ramp makes a smooth field readable as a gradient but hides
 * where the bands actually fall; the eye cannot tell a two-degree difference
 * from a ten-degree one across a large area. This overlay draws a thin dark
 * line wherever the displayed parameter crosses one of {@code bands} evenly
 * spaced levels, which restores the band structure the palette deliberately
 * avoids: the ramp stays smooth (no ragged filled contours), but the levels are
 * visible as lines.
 * <p>
 * Levels are spaced in the same normalised space the noise palette uses
 * ({@code pos = (value / Short.MAX_VALUE + 1) / 2}), so a band boundary sits
 * where the colour ramp changes from one band to the next. For the temperature
 * view these lines are isotherms; for the other noise modes they are the
 * corresponding isolines of that field.
 * <p>
 * Detection is per sample against its east and south neighbours, which keeps it
 * allocation-free and in step with the rest of the overlay. A {@code
 * Short.MIN_VALUE} neighbour means "not sampled yet" and never produces a line,
 * so the progressive fill does not draw false isolines at the sampling frontier.
 */
public final class NoiseIsolines {

    /** Sentinel used by the storage for un-sampled columns. */
    public static final int UNKNOWN = Short.MIN_VALUE;

    /** Default number of bands, matching the eight-step climate banding used elsewhere. */
    public static final int DEFAULT_BANDS = 8;

    /** Isoline color. */
    private static final int LINE_COLOR = 0xFF16202A;
    /** How strongly the isoline darkens the field colour. */
    private static final float LINE_MIX = 0.42f;

    private NoiseIsolines() {
    }

    /**
     * Applies the isoline overlay to one sampled value.
     *
     * @param baseColor ARGB color produced by the noise palette
     * @param value     raw noise sample of this column, or {@link #UNKNOWN}
     * @param eastValue raw noise sample one step to the east, or {@link #UNKNOWN}
     * @param southValue raw noise sample one step to the south, or {@link #UNKNOWN}
     * @param bands     number of bands to split the value range into
     * @return the shaded ARGB color, or {@code baseColor} when no line falls here
     */
    public static int apply(int baseColor, int value, int eastValue, int southValue, int bands) {
        if (value == UNKNOWN) {
            return baseColor;
        }
        final int band = band(value, bands);

        boolean cross = false;
        if (eastValue != UNKNOWN && band(eastValue, bands) != band) {
            cross = true;
        }
        if (!cross && southValue != UNKNOWN && band(southValue, bands) != band) {
            cross = true;
        }
        return cross ? NoiseTerrainOverlay.blend(baseColor, LINE_COLOR, LINE_MIX) : baseColor;
    }

    /**
     * Band index of a raw noise sample, {@code 0..bands-1}.
     * <p>
     * The mapping matches the palette: {@code -Short.MAX_VALUE} lands in the
     * first band, {@code +Short.MAX_VALUE} in the last, and the boundaries fall
     * at the same positions the colour ramp changes band.
     * </p>
     */
    static int band(int value, int bands) {
        final int count = Math.max(1, bands);
        final float pos = (value / (float) Short.MAX_VALUE + 1.0f) * 0.5f;
        final int index = (int) Math.floor(pos * count);
        return index < 0 ? 0 : Math.min(index, count - 1);
    }
}
