package caeruleusTait.world.preview.domain.preview;

/**
 * Render pipeline interface: converts PreviewData to screen pixels.
 */
public interface PreviewRenderer {

    /**
     * Renders the preview data to the given render target.
     *
     * @param data   the preview data to render
     * @param viewport the current viewport
     * @param target  the render target
     */
    void render(PreviewData data, Viewport viewport, PreviewRenderTarget target);

    /** Returns {@code true} if this renderer supports the given data type. */
    boolean supports(PreviewData.DataTypeFlags dataTypes);
}
