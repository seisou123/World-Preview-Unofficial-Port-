package caeruleusTait.world.preview.backend.export;

/**
 * Immutable terrain export specification.
 * <p>
 * Describes all parameters for a terrain map export task: coverage, resolution, center coordinates.
 * Unlike TFC's fixed presets (50k/100k), this allows free configuration of coverage and resolution via sliders.
 * </p>
 *
 * @param coverageRadius Coverage radius (blocks). The export area is a square centered at center,
 *                       with side length = coverageRadius x 2
 * @param blocksPerPixel Blocks per pixel, determines export resolution granularity
 * @param centerX        Export center X coordinate (block coords)
 * @param centerZ        Export center Z coordinate (block coords)
 */
public record TerrainExportSpec(
        int coverageRadius,
        int blocksPerPixel,
        int centerX,
        int centerZ,
        boolean exportContours,
        int contourInterval
) {
    /** Default coverage radius (blocks), yields ~2048x2048 pixel export at blocksPerPixel=4. */
    public static final int DEFAULT_COVERAGE_RADIUS = 4096;

    /** Default blocks per pixel. */
    public static final int DEFAULT_BLOCKS_PER_PIXEL = 4;

    /** Minimum coverage radius. */
    public static final int MIN_COVERAGE_RADIUS = 256;

    /** Maximum coverage radius. */
    public static final int MAX_COVERAGE_RADIUS = 16384;

    /** Minimum blocks per pixel. */
    public static final int MIN_BLOCKS_PER_PIXEL = 1;

    /** Maximum blocks per pixel. */
    public static final int MAX_BLOCKS_PER_PIXEL = 16;

    public TerrainExportSpec {
        if (coverageRadius < MIN_COVERAGE_RADIUS || coverageRadius > MAX_COVERAGE_RADIUS) {
            throw new IllegalArgumentException("coverageRadius must be between "
                    + MIN_COVERAGE_RADIUS + " and " + MAX_COVERAGE_RADIUS);
        }
        if (blocksPerPixel < MIN_BLOCKS_PER_PIXEL || blocksPerPixel > MAX_BLOCKS_PER_PIXEL) {
            throw new IllegalArgumentException("blocksPerPixel must be between "
                    + MIN_BLOCKS_PER_PIXEL + " and " + MAX_BLOCKS_PER_PIXEL);
        }
        if (contourInterval < 1) {
            throw new IllegalArgumentException("contourInterval must be >= 1");
        }
    }

    /** Compact constructor for backward compatibility */
    public TerrainExportSpec(int coverageRadius, int blocksPerPixel, int centerX, int centerZ) {
        this(coverageRadius, blocksPerPixel, centerX, centerZ, true, 10);
    }

    /**
     * Export image width (pixels).
     */
    public int imageWidth() {
        return (coverageRadius * 2) / blocksPerPixel;
    }

    /**
     * Export image height (pixels).
     */
    public int imageHeight() {
        return imageWidth();
    }

    /**
     * Minimum X coordinate of export area (block coords).
     */
    public int minBlockX() {
        return centerX - coverageRadius;
    }

    /**
     * Maximum X coordinate of export area (block coords).
     */
    public int maxBlockX() {
        return centerX + coverageRadius;
    }

    /**
     * Minimum Z coordinate of export area (block coords).
     */
    public int minBlockZ() {
        return centerZ - coverageRadius;
    }

    /**
     * Maximum Z coordinate of export area (block coords).
     */
    public int maxBlockZ() {
        return centerZ + coverageRadius;
    }

    /**
     * Estimate total sampling workload (pixels x samples per pixel).
     * Used for progress tracking and ETA calculation.
     */
    public long totalWork() {
        return (long) imageWidth() * imageHeight();
    }
}
