package caeruleusTait.world.preview.domain.preview;

import java.util.Objects;

/**
 * Event fired when the viewport changes (drag, zoom, Y-layer switch).
 */
public record ViewportChangeEvent(
        Viewport oldViewport,
        Viewport newViewport,
        ChangeType changeType
) {

    public ViewportChangeEvent {
        Objects.requireNonNull(oldViewport, "oldViewport");
        Objects.requireNonNull(newViewport, "newViewport");
        Objects.requireNonNull(changeType, "changeType");
    }

    /** The type of viewport change. */
    public enum ChangeType {
        /** Viewport was dragged/panned. */
        PAN,
        /** Viewport was zoomed in or out. */
        ZOOM,
        /** Y level was changed. */
        Y_LAYER,
        /** Viewport was resized. */
        RESIZE,
        /** Viewport was fully replaced (e.g. seed/dimension switch). */
        REPLACE
    }

    /** Returns {@code true} if the block range changed. */
    public boolean blockRangeChanged() {
        return oldViewport.minBlockX() != newViewport.minBlockX()
                || oldViewport.minBlockZ() != newViewport.minBlockZ()
                || oldViewport.maxBlockX() != newViewport.maxBlockX()
                || oldViewport.maxBlockZ() != newViewport.maxBlockZ();
    }

    /** Returns {@code true} if only the Y level changed. */
    public boolean yOnlyChange() {
        return changeType == ChangeType.Y_LAYER
                && oldViewport.minBlockX() == newViewport.minBlockX()
                && oldViewport.minBlockZ() == newViewport.minBlockZ()
                && oldViewport.maxBlockX() == newViewport.maxBlockX()
                && oldViewport.maxBlockZ() == newViewport.maxBlockZ();
    }
}
