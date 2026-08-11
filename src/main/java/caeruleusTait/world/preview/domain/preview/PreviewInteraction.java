package caeruleusTait.world.preview.domain.preview;

/**
 * Shared interaction handler for drag, zoom, click, and hover.
 *
 * <p>Replaces the per-component interaction logic previously
 * duplicated in PreviewContainer and PreviewDisplay.
 */
public interface PreviewInteraction {

    /** Called when the user starts dragging. */
    void onDragStart(int pixelX, int pixelZ);

    /** Called when the user drags. */
    Viewport onDrag(int deltaPixelX, int deltaPixelZ, Viewport current);

    /** Called when the user ends dragging. */
    void onDragEnd();

    /** Called when the user scrolls to zoom. */
    Viewport onZoom(double zoomFactor, int centerPixelX, int centerPixelZ, Viewport current);

    /** Called when the user clicks. */
    void onClick(int pixelX, int pixelZ, Viewport viewport);

    /** Called when the user hovers. */
    void onHover(int pixelX, int pixelZ, Viewport viewport);

    /** Called when the user switches the Y layer. */
    Viewport onYLayerChange(int newY, Viewport current);
}
