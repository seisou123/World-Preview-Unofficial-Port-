package caeruleusTait.world.preview.domain.preview;

/**
 * Maps pixel coordinates to block coordinates and vice versa.
 *
 * <p>Replaces the per-component coordinate mapping logic previously
 * duplicated in PreviewContainer and PreviewDisplay.
 */
public final class PixelToBlockMapper {

    private final int pixelsPerChunk;
    private final int blocksPerChunk;
    private final double blocksPerPixel;

    /**
     * Creates a mapper with the given pixels-per-chunk resolution.
     *
     * @param pixelsPerChunk number of pixels per chunk (e.g. 4, 8, 16)
     */
    public PixelToBlockMapper(int pixelsPerChunk) {
        if (pixelsPerChunk < 1) {
            throw new IllegalArgumentException("pixelsPerChunk must be at least 1");
        }
        this.pixelsPerChunk = pixelsPerChunk;
        this.blocksPerChunk = 16; // Minecraft chunks are 16x16 blocks
        this.blocksPerPixel = (double) blocksPerChunk / pixelsPerChunk;
    }

    /** Returns the pixels-per-chunk resolution. */
    public int pixelsPerChunk() {
        return pixelsPerChunk;
    }

    /** Returns the blocks-per-chunk (always 16 in Minecraft). */
    public int blocksPerChunk() {
        return blocksPerChunk;
    }

    /** Returns the blocks-per-pixel ratio. */
    public double blocksPerPixel() {
        return blocksPerPixel;
    }

    /** Converts a pixel X coordinate to a block X coordinate. */
    public int pixelToBlockX(int pixelX, int viewportOffsetX) {
        return viewportOffsetX + (int) (pixelX * blocksPerPixel);
    }

    /** Converts a pixel Z coordinate to a block Z coordinate. */
    public int pixelToBlockZ(int pixelZ, int viewportOffsetZ) {
        return viewportOffsetZ + (int) (pixelZ * blocksPerPixel);
    }

    /** Converts a block X coordinate to a pixel X coordinate. */
    public int blockToPixelX(int blockX, int viewportOffsetX) {
        return (int) ((blockX - viewportOffsetX) / blocksPerPixel);
    }

    /** Converts a block Z coordinate to a pixel Z coordinate. */
    public int blockToPixelZ(int blockZ, int viewportOffsetZ) {
        return (int) ((blockZ - viewportOffsetZ) / blocksPerPixel);
    }

    /** Converts a chunk X coordinate to a pixel X coordinate. */
    public int chunkToPixelX(int chunkX) {
        return chunkX * pixelsPerChunk;
    }

    /** Converts a chunk Z coordinate to a pixel Z coordinate. */
    public int chunkToPixelZ(int chunkZ) {
        return chunkZ * pixelsPerChunk;
    }

    /** Converts a pixel X coordinate to a chunk X coordinate. */
    public int pixelToChunkX(int pixelX) {
        return pixelX / pixelsPerChunk;
    }

    /** Converts a pixel Z coordinate to a chunk Z coordinate. */
    public int pixelToChunkZ(int pixelZ) {
        return pixelZ / pixelsPerChunk;
    }
}
