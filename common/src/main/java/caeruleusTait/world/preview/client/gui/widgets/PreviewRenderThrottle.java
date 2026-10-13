package caeruleusTait.world.preview.client.gui.widgets;

import net.minecraft.core.BlockPos;

/**
 * Frame-timing, adaptive-throttling and render-skip state for {@link PreviewDisplay}.
 *
 * <p>This is the extracted state machine behind two historical bugs:
 * <ul>
 *   <li><b>"Black screen until drag"</b> — a resized texture that was never
 *       re-rendered looked black; {@code textureNeedsUpload} forces a full
 *       render cycle until real data has been drawn into the texture.</li>
 *   <li><b>Missed data updates under throttling</b> — the write-counter check
 *       must run on every frame (see {@link #shouldRerender}), otherwise worker
 *       writes that land inside a throttle window are never picked up.</li>
 * </ul>
 */
final class PreviewRenderThrottle {

    private static final double ADAPTIVE_THRESHOLD_MS = 35.0;  // ~28 fps
    private static final double ADAPTIVE_CRITICAL_MS = 60.0;   // ~16 fps
    private static final long DRAG_RENDER_INTERVAL_NANOS = 50_000_000L;
    /** While dragging, re-queue sampling at most every 50ms (same cadence as drag re-render). */
    private static final long DRAG_QUEUE_INTERVAL_NANOS = 50_000_000L;
    /**
     * Minimum spacing between data-driven heavy renders. Worker batches
     * complete many times per second; without coalescing each completion
     * triggered a full re-rasterize + full-texture GPU upload even though a
     * later batch would overwrite it within the same frame period.
     */
    private static final long WRITE_RENDER_COALESCE_NANOS = 33_333_333L; // ~30 fps ceiling

    // --- Lightweight frame-time tracking (no boxing, no allocation) ---
    private long lastFrameNanos = 0;
    private double frameTimeEmaMs = 0; // EMA of frame time in milliseconds

    // --- Adaptive render throttling ---
    private int adaptiveSkipCounter = 0;
    private int adaptiveSkipEveryN = 1; // dynamically adjusted

    private long lastDragRenderNanos = 0;
    private long lastDragQueueNanos = 0;

    // --- Render-skip optimization ---
    private BlockPos lastRenderedCenter = null;
    private long lastWriteCounter = -1;
    private long lastWriteRenderNanos = 0;
    /** Test hook: 0 disables write-render coalescing entirely. */
    private long writeRenderCoalesceNanos = WRITE_RENDER_COALESCE_NANOS;

    // --- Texture upload tracking ---
    // Set whenever a new GPU texture has been created but not yet had biome
    // data rendered into it. The render-skip path must NOT treat such a
    // texture as up-to-date.
    private boolean textureNeedsUpload = true;

    // --- Initial queue tracking ---
    // Guarantees at least one real queueRange() call after setup, even if the
    // computed range matches a stale dedup guard from a previous configuration.
    private boolean needsInitialQueue = true;

    // --- Preload oscillation guard ---
    private boolean initialDataReceived = false;

    /**
     * Updates the frame-time EMA and the adaptive skip rate.
     * Must be called once per rendered frame.
     */
    void onFrameStart() {
        final long frameStartNanos = System.nanoTime();
        if (lastFrameNanos != 0) {
            final double deltaMs = (frameStartNanos - lastFrameNanos) / 1_000_000.0;
            // EMA with alpha=0.1 (smooth over ~10 frames)
            frameTimeEmaMs = frameTimeEmaMs == 0 ? deltaMs : (frameTimeEmaMs * 0.9 + deltaMs * 0.1);
        }
        lastFrameNanos = frameStartNanos;

        if (frameTimeEmaMs > ADAPTIVE_CRITICAL_MS) {
            adaptiveSkipEveryN = 3;
        } else if (frameTimeEmaMs > ADAPTIVE_THRESHOLD_MS) {
            adaptiveSkipEveryN = 2;
        } else {
            adaptiveSkipEveryN = 1;
        }
    }

    /** Current adaptive skip rate: 1 (none), 2 or 3. */
    int adaptiveSkipEveryN() {
        return adaptiveSkipEveryN;
    }

    /**
     * Drag re-render throttle. Returns {@code true} when this frame falls
     * inside the throttle window (skip the heavy path); otherwise records
     * {@code now} and returns {@code false}.
     */
    boolean dragRenderThrottled(long now) {
        if (now - lastDragRenderNanos < DRAG_RENDER_INTERVAL_NANOS) {
            return true;
        }
        lastDragRenderNanos = now;
        return false;
    }

    /** Same cadence as {@link #dragRenderThrottled(long)} for sampling re-queues. */
    boolean dragQueueThrottled(long now) {
        if (now - lastDragQueueNanos < DRAG_QUEUE_INTERVAL_NANOS) {
            return true;
        }
        lastDragQueueNanos = now;
        return false;
    }

    /** Called when a click starts so the first drag frame renders immediately. */
    void touchDragRenderTimer() {
        lastDragRenderNanos = System.nanoTime();
    }

    void resetDragTimers() {
        lastDragRenderNanos = 0;
        lastDragQueueNanos = 0;
    }

    /**
     * Decides whether the heavy render path (generate + texture update +
     * upload) must run this frame.
     *
     * @param dragThrottleSkipHeavy true while inside the drag render window
     * @param hasStorage            false when no preview storage is attached yet
     * @param currentWriteCounter   storage write counter (0 when no storage)
     * @param currentCenter         current viewport center block position
     * @param hasCachedRenderData   false when no cached render helper list exists
     */
    boolean shouldRerender(boolean dragThrottleSkipHeavy, boolean hasStorage,
                           long currentWriteCounter, BlockPos currentCenter,
                           boolean hasCachedRenderData) {
        if (currentWriteCounter > 0) {
            initialDataReceived = true;
        }
        // The writeCounter check MUST happen on every frame, even during
        // adaptive throttling: worker threads write asynchronously and the
        // render thread must pick changes up immediately.
        final boolean writeCounterChanged = currentWriteCounter != lastWriteCounter;

        if (textureNeedsUpload) {
            // A new texture was created (e.g. by resize) but has not yet had
            // biome data rendered into it. Force a full render cycle.
            return hasStorage && !dragThrottleSkipHeavy;
        }
        if (dragThrottleSkipHeavy) {
            // Keep previous GPU texture this frame; overlays still draw.
            return false;
        }
        if (!hasStorage) {
            return false;
        }
        if (writeCounterChanged) {
            // Data changed: re-render, bypassing the adaptive throttle, but
            // coalesced in time. The change itself is still DETECTED on every
            // frame (the check above runs unconditionally); only the heavy
            // render is rate-limited. While inside the window the still-stale
            // writeCounter keeps this branch active, so the deferred render
            // happens on the first frame after the window elapses — updates
            // are delayed by at most one coalesce interval, never dropped.
            final long now = System.nanoTime();
            if (now - lastWriteRenderNanos >= writeRenderCoalesceNanos) {
                lastWriteRenderNanos = now;
                return true;
            }
            return false;
        }
        if (adaptiveSkipEveryN > 1 && lastRenderedCenter != null && currentCenter.equals(lastRenderedCenter)) {
            // Adaptive throttling active: only re-render on every Nth frame.
            adaptiveSkipCounter++;
            if (adaptiveSkipCounter >= adaptiveSkipEveryN) {
                adaptiveSkipCounter = 0;
                return !hasCachedRenderData;
            }
            return false;
        }
        // Fast machine, or center changed (always re-render on center change).
        return lastRenderedCenter == null
                || !currentCenter.equals(lastRenderedCenter)
                || !hasCachedRenderData;
    }

    /** Records a completed heavy render pass. */
    void markRendered(BlockPos center, long writeCounter) {
        lastRenderedCenter = center;
        lastWriteCounter = writeCounter;
    }

    /** Test hook: override the write-render coalesce window (0 disables it). */
    void setWriteRenderCoalesceNanos(long nanos) {
        this.writeRenderCoalesceNanos = nanos;
    }

    boolean needsInitialQueue() {
        return needsInitialQueue;
    }

    void clearNeedsInitialQueue() {
        needsInitialQueue = false;
    }

    boolean initialDataReceived() {
        return initialDataReceived;
    }

    boolean textureNeedsUpload() {
        return textureNeedsUpload;
    }

    void markTextureUploaded() {
        textureNeedsUpload = false;
    }

    void markTextureNeedsUpload() {
        textureNeedsUpload = true;
    }

    /**
     * Invalidates everything after a resize: the new texture contains no data,
     * but the queued sampling range itself remains valid.
     */
    void invalidateAfterResize() {
        lastRenderedCenter = null;
        textureNeedsUpload = true;
    }

    /**
     * Full invalidation for a new world configuration / reused display.
     * Also drops the write-counter sentinel so the first render always
     * detects a change, and re-arms the initial-queue guarantee.
     */
    void invalidateAll() {
        lastRenderedCenter = null;
        lastWriteCounter = -1;
        lastWriteRenderNanos = 0;
        resetDragTimers();
        needsInitialQueue = true;
        textureNeedsUpload = true;
        initialDataReceived = false;
    }

    /**
     * Partial invalidation used by {@code setSelectedBiomeId}/
     * {@code setHighlightCaves}: only the rendered-content cache goes stale.
     */
    void invalidateRenderedContent() {
        lastRenderedCenter = null;
    }
}
