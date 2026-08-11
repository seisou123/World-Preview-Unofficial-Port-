package caeruleusTait.world.preview.domain.preview;

/**
 * The target surface for rendering: canvas, FBO, or screen.
 */
public interface PreviewRenderTarget {

    /** Returns the width of the render target in pixels. */
    int width();

    /** Returns the height of the render target in pixels. */
    int height();

    /**
     * Sets a pixel at the given coordinates.
     *
     * @param x     pixel X
     * @param z     pixel Z
     * @param color ARGB color (0xAARRGGBB)
     */
    void setPixel(int x, int z, int color);

    /**
     * Gets the pixel color at the given coordinates.
     *
     * @return ARGB color, or 0 if unset
     */
    int getPixel(int x, int z);

    /** Fills the entire target with the given color. */
    void clear(int color);

    /** The type of render target. */
    enum Type {
        /** Direct screen rendering. */
        SCREEN,
        /** Off-screen canvas buffer. */
        CANVAS,
        /** Framebuffer object (GPU). */
        FBO,
        /** Thumbnail-sized buffer. */
        THUMBNAIL
    }

    /** Returns the type of this render target. */
    Type type();
}
