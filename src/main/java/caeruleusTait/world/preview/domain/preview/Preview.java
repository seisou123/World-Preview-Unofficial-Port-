package caeruleusTait.world.preview.domain.preview;

/**
 * Top-level interface for all preview-related operations.
 *
 * <p>A preview represents a world-generation snapshot identified by:
 * <ul>
 *   <li>A seed</li>
 *   <li>A dimension</li>
 *   <li>A resolution (pixels per chunk)</li>
 *   <li>A coordinate range (minX, minZ, maxX, maxZ)</li>
 * </ul>
 */
public interface Preview {

    /** Returns the world seed of this preview. */
    long seed();

    /** Returns the dimension identifier (e.g. "minecraft:overworld"). */
    String dimension();

    /** Returns the resolution in pixels per chunk. */
    int pixelsPerChunk();

    /** Returns the minimum X coordinate of the preview area. */
    int minX();

    /** Returns the minimum Z coordinate of the preview area. */
    int minZ();

    /** Returns the maximum X coordinate of the preview area. */
    int maxX();

    /** Returns the maximum Z coordinate of the preview area. */
    int maxZ();

    /** Returns the Y level of this preview. */
    int y();

    /** Returns the width in blocks. */
    default int width() {
        return maxX() - minX() + 1;
    }

    /** Returns the height in blocks. */
    default int height() {
        return maxZ() - minZ() + 1;
    }
}
