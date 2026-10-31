package caeruleusTait.world.preview.backend.terrain;

/**
 * Semantic terrain overlay for the noise views.
 * <p>
 * The noise fields themselves (temperature, humidity, continentalness, &hellip;)
 * carry no coastline: vanilla climate noise is smooth by design, so a noise map
 * cannot be read against the terrain without a reference. This overlay uses the
 * heightmap channel that the preview already samples to
 * <ul>
 *   <li>tint water columns (surface height below the dimension's sea level) with
 *       a depth-shaded blue,</li>
 *   <li>draw a dark line along shorelines, on the water side only, and</li>
 *   <li>modulate land brightness by slope so relief reads as relief.</li>
 * </ul>
 * Water takes its hue from the depth ramp, never from the climate field: a
 * fixed blue blended over a warm palette colour cancels the chroma and turns
 * orange basins into grey-green sludge. Only the field's <em>lightness</em> is
 * carried into the water colour, and that fades out with depth, so shallow
 * water keeps a hint of the underlying structure while open sea reads as sea.
 * <p>
 * Two local guards keep the coastline from turning into speckle. A water column
 * with no water neighbour at all is a one-quart dip in the terrain rather than a
 * water body, so it is left untinted (unless not a single neighbour is known, in
 * which case there is nothing to conclude and the plain tint is kept). The coast
 * line is then drawn only where at least two of the eight surrounding columns
 * are land.
 * <p>
 * Both the eight-neighbour count and the threshold of two are load-bearing, for
 * different reasons. A straight shore has three land columns among the eight but
 * only one among the four orthogonal ones, so a 4-neighbour count would drop
 * below any useful threshold and the shore would vanish; that is why the
 * diagonals are counted. A single-column island, meanwhile, is seen by each of
 * its orthogonal water neighbours as exactly one land column, so a threshold of
 * one would ring the island with four ink pixels — a blot four times the island's
 * own area. The threshold of two rejects that while keeping every straight and
 * diagonal shore.
 * <p>
 * All methods are pure integer math: the renderer feeds in the height samples,
 * the overlay returns the shaded ARGB color.
 */
public final class NoiseTerrainOverlay {

    /** Sentinel used by the storage for un-sampled columns. */
    public static final int UNKNOWN_HEIGHT = Short.MIN_VALUE;

    /** Water tint at the shoreline. */
    private static final int SHALLOW_WATER = 0xFF3E7CA6;
    /** Water tint at and below {@link #FULL_DEPTH_BLOCKS}. */
    private static final int DEEP_WATER = 0xFF12314F;
    /** Coastline color. */
    private static final int COAST_LINE = 0xFF071018;

    /**
     * How much of the noise field's lightness survives in water at the shore,
     * fading to zero at {@link #FULL_DEPTH_BLOCKS}.
     */
    private static final float WATER_FIELD_LIGHT = 0.35f;
    /** Depth at which the water tint is pure water color. */
    private static final float FULL_DEPTH_BLOCKS = 40.0f;
    /**
     * How strongly the coastline replaces the underlying color. Kept below
     * full replacement: at 0.85 the line read as near-black and every pond
     * became a blot, at 0.65 it still separates land from water but sits a
     * step lighter than the darkest water.
     */
    private static final float COAST_MIX = 0.65f;

    /** Maximum land brightness change from relief shading. */
    private static final float LAND_SHADE_AMPLITUDE = 0.12f;
    /** Slope (blocks per sample) that reaches the full {@link #LAND_SHADE_AMPLITUDE}. */
    private static final float SLOPE_REFERENCE = 12.0f;

    private NoiseTerrainOverlay() {
    }

    /**
     * Applies the overlay to one sampled column.
     *
     * @param baseColor       ARGB color produced by the noise palette
     * @param height          surface height of this column, or {@link #UNKNOWN_HEIGHT}
     * @param eastHeight      surface height one sample to the east, or {@link #UNKNOWN_HEIGHT}
     * @param westHeight      surface height one sample to the west, or {@link #UNKNOWN_HEIGHT}
     * @param southHeight     surface height one sample to the south, or {@link #UNKNOWN_HEIGHT}
     * @param northHeight     surface height one sample to the north, or {@link #UNKNOWN_HEIGHT}
     * @param northEastHeight surface height one sample to the north-east, or {@link #UNKNOWN_HEIGHT}
     * @param northWestHeight surface height one sample to the north-west, or {@link #UNKNOWN_HEIGHT}
     * @param southEastHeight surface height one sample to the south-east, or {@link #UNKNOWN_HEIGHT}
     * @param southWestHeight surface height one sample to the south-west, or {@link #UNKNOWN_HEIGHT}
     * @param seaLevel        sea level of the current dimension
     * @return the shaded ARGB color, or {@code baseColor} when nothing applies
     */
    public static int apply(int baseColor, int height,
                            int eastHeight, int westHeight, int southHeight, int northHeight,
                            int northEastHeight, int northWestHeight,
                            int southEastHeight, int southWestHeight,
                            int seaLevel) {
        if (height == UNKNOWN_HEIGHT) {
            return baseColor;
        }

        if (height < seaLevel) {
            return applyWater(baseColor, height,
                    eastHeight, westHeight, southHeight, northHeight,
                    northEastHeight, northWestHeight, southEastHeight, southWestHeight,
                    seaLevel);
        }

        // Land: keep the palette color and add a cheap directional relief shade.
        final float lit = litSlope(height, eastHeight, westHeight, southHeight, northHeight);
        if (lit == 0.0f) {
            return baseColor;
        }
        return scale(baseColor, 1.0f + lit * LAND_SHADE_AMPLITUDE);
    }

    private static int applyWater(int baseColor, int height,
                                  int eastHeight, int westHeight, int southHeight, int northHeight,
                                  int northEastHeight, int northWestHeight,
                                  int southEastHeight, int southWestHeight,
                                  int seaLevel) {
        // One comparison per neighbour, no per-column array: the eight counts
        // are packed as land in the high nibble and water in the low one.
        final int packed = neighbourClass(eastHeight, seaLevel)
                + neighbourClass(westHeight, seaLevel)
                + neighbourClass(southHeight, seaLevel)
                + neighbourClass(northHeight, seaLevel)
                + neighbourClass(northEastHeight, seaLevel)
                + neighbourClass(northWestHeight, seaLevel)
                + neighbourClass(southEastHeight, seaLevel)
                + neighbourClass(southWestHeight, seaLevel);
        final int land = packed >> 4;
        final int water = packed & 0x0F;

        // A lone water column with no water neighbour is a one-quart dip, not a
        // body of water. Tinting it would paint a blue speck (and, before the
        // coast guard below, ring it with ink). Leave the field color alone.
        // With no known neighbour at all there is nothing to conclude, so the
        // plain tint is kept.
        if (water == 0 && land > 0) {
            return baseColor;
        }

        // Coastline: the water side of a real shore. Two land neighbours among
        // the eight rejects the ring a single-column island would draw around
        // itself; a straight shore has three (the orthogonal land column plus
        // the two diagonals beside it).
        if (land >= 2) {
            return blend(baseColor, COAST_LINE, COAST_MIX);
        }

        final float depth = Math.min(1.0f, (seaLevel - height) / FULL_DEPTH_BLOCKS);
        final int waterColor = blend(SHALLOW_WATER, DEEP_WATER, depth);
        final float fieldWeight = WATER_FIELD_LIGHT * (1.0f - depth);
        if (fieldWeight <= 0.0f) {
            return waterColor;
        }
        // Lightness only: the grey of the field's luminance, not its hue.
        return blend(waterColor, grey(luma(baseColor)), fieldWeight);
    }

    /**
     * Classifies one neighbour for the eight-neighbour count: {@code 0x10} for
     * land, {@code 0x01} for water, {@code 0} for an un-sampled column.
     */
    private static int neighbourClass(int neighbourHeight, int seaLevel) {
        if (neighbourHeight == UNKNOWN_HEIGHT) {
            return 0;
        }
        return neighbourHeight < seaLevel ? 0x01 : 0x10;
    }

    /**
     * Directional slope term for the land relief shade, in {@code [-1, 1]}.
     * <p>
     * Matches the hillshade light convention (azimuth 0 = north, clockwise,
     * default 315 = north-west): a surface rising toward east and south faces
     * the light and comes out positive, one rising toward west and north faces
     * away and comes out negative. Axes with only one known neighbour fall back
     * to a one-sided difference; axes with none contribute nothing.
     * </p>
     */
    private static float litSlope(int height, int east, int west, int south, int north) {
        float dhdx = 0.0f;
        float dhdz = 0.0f;

        if (east != UNKNOWN_HEIGHT && west != UNKNOWN_HEIGHT) {
            dhdx = (east - west) * 0.5f;
        } else if (east != UNKNOWN_HEIGHT) {
            dhdx = east - height;
        } else if (west != UNKNOWN_HEIGHT) {
            dhdx = height - west;
        }

        if (south != UNKNOWN_HEIGHT && north != UNKNOWN_HEIGHT) {
            dhdz = (south - north) * 0.5f;
        } else if (south != UNKNOWN_HEIGHT) {
            dhdz = south - height;
        } else if (north != UNKNOWN_HEIGHT) {
            dhdz = height - north;
        }

        final float lit = (dhdx + dhdz) / SLOPE_REFERENCE;
        return Math.max(-1.0f, Math.min(1.0f, lit));
    }

    /** Linear interpolation between two opaque ARGB colors. */
    static int blend(int from, int to, float t) {
        final float inv = 1.0f - t;
        final int a = (int) (((from >>> 24) & 0xFF) * inv + ((to >>> 24) & 0xFF) * t);
        final int r = (int) (((from >>> 16) & 0xFF) * inv + ((to >>> 16) & 0xFF) * t);
        final int g = (int) (((from >>> 8) & 0xFF) * inv + ((to >>> 8) & 0xFF) * t);
        final int b = (int) ((from & 0xFF) * inv + (to & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Multiplies the RGB channels by {@code factor}, leaving alpha alone. */
    static int scale(int color, float factor) {
        final int a = (color >>> 24) & 0xFF;
        final int r = clamp255((int) (((color >>> 16) & 0xFF) * factor));
        final int g = clamp255((int) (((color >>> 8) & 0xFF) * factor));
        final int b = clamp255((int) ((color & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Rec. 601 luminance of the RGB channels, {@code 0..255}. */
    static int luma(int color) {
        return (((color >>> 16) & 0xFF) * 299 + ((color >>> 8) & 0xFF) * 587 + (color & 0xFF) * 114) / 1000;
    }

    /** Opaque grey of the given {@code 0..255} level. */
    static int grey(int level) {
        final int v = clamp255(level);
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
