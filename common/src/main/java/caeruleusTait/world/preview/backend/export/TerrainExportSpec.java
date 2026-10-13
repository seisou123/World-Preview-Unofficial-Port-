package caeruleusTait.world.preview.backend.export;

/**
 * Immutable terrain export specification.
 * <p>
 * Describes all parameters for a terrain map export task: coverage, resolution, center coordinates.
 * Unlike TFC's fixed presets (50k/100k), this allows free configuration of coverage and resolution via sliders.
 * </p>
 *
 * @param coverageRadius    Coverage radius (blocks). The export area is a square centered at center,
 *                          with side length = coverageRadius x 2
 * @param blocksPerPixel    Blocks per pixel, determines export resolution granularity
 * @param centerX           Export center X coordinate (block coords)
 * @param centerZ           Export center Z coordinate (block coords)
 * @param gridIntervalBlocks Spacing of the coordinate grid overlay (blocks); 0 disables the grid
 * @param exportHeightmap    Whether a grayscale heightmap PNG is written alongside the terrain PNG
 * @param colorMode          Pixel color source: nine terrain categories or per-biome colors
 * @param exportY            World Y the biome sampler evaluates (block coords); follows the
 *                           preview center so the exported layer matches the viewed layer
 */
public record TerrainExportSpec(
        int coverageRadius,
        int blocksPerPixel,
        int centerX,
        int centerZ,
        boolean exportContours,
        int contourInterval,
        int gridIntervalBlocks,
        boolean exportHeightmap,
        TerrainExportColorMode colorMode,
        int exportY
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

    /** Minimum grid interval when the grid overlay is enabled. */
    public static final int MIN_GRID_INTERVAL_BLOCKS = 16;

    /** Maximum grid interval. */
    public static final int MAX_GRID_INTERVAL_BLOCKS = 4096;

    /** Hard allocation limit shared by the UI and exporter (4096 x 4096 pixels). */
    public static final long MAX_PIXELS = 16_777_216L;

    /** Legacy fixed sampling layer (block Y) used by the backward-compatible constructors. */
    public static final int DEFAULT_EXPORT_Y = 64;

    /** Checks slider values without constructing a potentially invalid specification. */
    public static boolean isSupported(int coverageRadius, int blocksPerPixel) {
        if (coverageRadius < MIN_COVERAGE_RADIUS || coverageRadius > MAX_COVERAGE_RADIUS
                || blocksPerPixel < MIN_BLOCKS_PER_PIXEL || blocksPerPixel > MAX_BLOCKS_PER_PIXEL) {
            return false;
        }
        long width = (2L * coverageRadius) / blocksPerPixel;
        return width * width <= MAX_PIXELS;
    }

    /** Conservative working-set estimate including colors, heights, native PNG and bounded tiles. */
    public static long estimatedMemoryBytes(int coverageRadius, int blocksPerPixel) {
        if (coverageRadius < MIN_COVERAGE_RADIUS || coverageRadius > MAX_COVERAGE_RADIUS
                || blocksPerPixel < MIN_BLOCKS_PER_PIXEL || blocksPerPixel > MAX_BLOCKS_PER_PIXEL) {
            return Long.MAX_VALUE;
        }
        long width = (2L * coverageRadius) / blocksPerPixel;
        return width * width * 16L + 8L * 1024 * 1024;
    }

    public long estimatedMemoryBytes() {
        return estimatedMemoryBytes(coverageRadius, blocksPerPixel);
    }

    public TerrainExportSpec {
        if (coverageRadius < MIN_COVERAGE_RADIUS || coverageRadius > MAX_COVERAGE_RADIUS) {
            throw new IllegalArgumentException("coverageRadius must be between "
                    + MIN_COVERAGE_RADIUS + " and " + MAX_COVERAGE_RADIUS);
        }
        if (blocksPerPixel < MIN_BLOCKS_PER_PIXEL || blocksPerPixel > MAX_BLOCKS_PER_PIXEL) {
            throw new IllegalArgumentException("blocksPerPixel must be between "
                    + MIN_BLOCKS_PER_PIXEL + " and " + MAX_BLOCKS_PER_PIXEL);
        }
        if (!isSupported(coverageRadius, blocksPerPixel)) {
            throw new IllegalArgumentException("Export exceeds " + MAX_PIXELS + " pixels");
        }
        if (contourInterval < 1) {
            throw new IllegalArgumentException("contourInterval must be >= 1");
        }
        if (gridIntervalBlocks != 0
                && (gridIntervalBlocks < MIN_GRID_INTERVAL_BLOCKS || gridIntervalBlocks > MAX_GRID_INTERVAL_BLOCKS)) {
            throw new IllegalArgumentException("gridIntervalBlocks must be 0 or between "
                    + MIN_GRID_INTERVAL_BLOCKS + " and " + MAX_GRID_INTERVAL_BLOCKS);
        }
    }

    /** Compact constructor for backward compatibility */
    public TerrainExportSpec(int coverageRadius, int blocksPerPixel, int centerX, int centerZ) {
        this(coverageRadius, blocksPerPixel, centerX, centerZ, true, 10, 0, false);
    }

    /** Compact constructor for backward compatibility (grid overlay disabled) */
    public TerrainExportSpec(int coverageRadius, int blocksPerPixel, int centerX, int centerZ,
                             boolean exportContours, int contourInterval) {
        this(coverageRadius, blocksPerPixel, centerX, centerZ, exportContours, contourInterval, 0, false);
    }

    /**
     * Compact constructor for backward compatibility: category colors at the
     * legacy fixed Y layer, matching the pre-1.5.6 export behavior exactly.
     */
    public TerrainExportSpec(int coverageRadius, int blocksPerPixel, int centerX, int centerZ,
                             boolean exportContours, int contourInterval, int gridIntervalBlocks,
                             boolean exportHeightmap) {
        this(coverageRadius, blocksPerPixel, centerX, centerZ, exportContours, contourInterval,
                gridIntervalBlocks, exportHeightmap, TerrainExportColorMode.CATEGORY, DEFAULT_EXPORT_Y);
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
