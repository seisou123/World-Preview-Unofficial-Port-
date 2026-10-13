package caeruleusTait.world.preview.backend.analysis;

/**
 * Helper for dimension-related Y calculations.
 *
 * <p>Extracted from {@code PreviewContainer} to decouple dimension
 * height logic from the GUI layer.
 */
public final class DimensionHelper {

    private DimensionHelper() {
        // Utility class
    }

    /**
     * Computes the analysis Y level for a dimension.
     *
     * @param minY   the dimension's minimum Y
     * @param height the dimension's height
     * @return the analysis Y level (minY + height)
     */
    public static int analysisYForDimension(int minY, int height) {
        return minY + height;
    }

    /**
     * Validates that a Y level is within a dimension's range.
     *
     * @param y      the Y level to check
     * @param minY   the dimension's minimum Y
     * @param height the dimension's height
     * @return {@code true} if the Y level is valid
     */
    public static boolean isValidY(int y, int minY, int height) {
        return y >= minY && y < minY + height;
    }

    /**
     * Clamps a Y level to a dimension's range.
     *
     * @param y      the Y level to clamp
     * @param minY   the dimension's minimum Y
     * @param height the dimension's height
     * @return the clamped Y level
     */
    public static int clampY(int y, int minY, int height) {
        int maxY = minY + height - 1;
        return Math.max(minY, Math.min(maxY, y));
    }
}
