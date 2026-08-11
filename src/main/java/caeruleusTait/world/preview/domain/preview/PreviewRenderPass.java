package caeruleusTait.world.preview.domain.preview;

/**
 * A single pass in the render pipeline.
 * Each pass renders one layer: biome, structure, height, or Y-layer intersection.
 */
public interface PreviewRenderPass {

    /** Returns the name of this render pass. */
    String name();

    /** Returns the priority (lower = rendered first). */
    int priority();

    /** Returns {@code true} if this pass is enabled. */
    boolean isEnabled();

    /** Enables or disables this pass. */
    void setEnabled(boolean enabled);

    /**
     * Executes this render pass.
     *
     * @param data     the preview data
     * @param viewport the current viewport
     * @param target   the render target
     */
    void execute(PreviewData data, Viewport viewport, PreviewRenderTarget target);

    /** Standard render pass names. */
    String BIOME_PASS = "biome";
    String STRUCTURE_PASS = "structure";
    String HEIGHT_PASS = "height";
    String INTERSECTION_PASS = "intersection";
}
