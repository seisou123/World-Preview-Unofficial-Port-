package caeruleusTait.world.preview.client.gui.widgets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the render-skip and initial-queue logic that was introduced
 * to fix the "black screen until drag" bug.
 *
 * <p>These tests verify the pure logic of the flags without requiring Minecraft's
 * client infrastructure.  The actual flag values are mirrored from
 * {@link PreviewDisplay}'s field declarations and decision logic.
 */
class PreviewDisplayRenderSkipTest {

    // --- Mirror of PreviewDisplay's needRerender decision logic (v2) ---
    //
    // Key change from v1: writeCounterChanged is checked BEFORE the adaptive
    // throttle, so data changes are always detected on every frame.

    static boolean computeNeedRerender(
            boolean textureNeedsUpload,
            boolean dragThrottleSkipHeavy,
            boolean storageNull,
            int adaptiveSkipEveryN,
            boolean lastRenderedCenter,
            boolean centerEquals,
            boolean writeCounterChanged,
            boolean cachedRenderDataNull
    ) {
        if (textureNeedsUpload) {
            return !storageNull && !dragThrottleSkipHeavy;
        }
        if (dragThrottleSkipHeavy) {
            return false;
        }
        if (storageNull) {
            return false;
        }
        if (writeCounterChanged) {
            // Data changed: ALWAYS re-render, bypassing adaptive throttle.
            return true;
        }
        if (adaptiveSkipEveryN > 1 && lastRenderedCenter && centerEquals) {
            // Adaptive throttling: only check cachedRenderData on Nth frame
            return cachedRenderDataNull;
        }
        return !lastRenderedCenter
                || !centerEquals
                || cachedRenderDataNull;
    }

    // --- Mirror of PreviewDisplay.queueGeneration() dedup logic ---

    static boolean shouldQueueRange(boolean needsInitialQueue, boolean rangeEqualsLast) {
        if (!needsInitialQueue && rangeEqualsLast) {
            return false;
        }
        return true;
    }

    // --- Mirror of queueGeneration() preload logic ---

    /**
     * When needsInitialQueue is true, preload MUST be 0 to match the early
     * queue range and prevent work cancellation.
     */
    static int computePreload(boolean enablePreload, boolean needsInitialQueue,
                              boolean preloadOnlyWhenIdle, boolean isSetup,
                              int activeBatchCount, int preloadRadius) {
        if (!enablePreload || needsInitialQueue) {
            return 0;
        }
        if (preloadOnlyWhenIdle && isSetup && activeBatchCount > 0) {
            return 0;
        }
        return preloadRadius;
    }

    // === Tests for textureNeedsUpload flag ===

    @Test
    void needRerender_true_whenTextureNeedsUpload_andStorageNotNull() {
        assertTrue(computeNeedRerender(
                true,  // textureNeedsUpload
                false, // not drag throttling
                false, // storage not null
                1,     // no adaptive throttle
                true,  // had a last rendered center
                true,  // center equals last
                false, // write counter unchanged
                false  // cached data not null
        ));
    }

    @Test
    void needRerender_false_whenTextureNeedsUpload_butStorageNull() {
        assertFalse(computeNeedRerender(
                true, false, true, 1, true, true, false, false
        ));
    }

    @Test
    void needRerender_false_whenTextureNeedsUpload_butDragThrottling() {
        assertFalse(computeNeedRerender(
                true, true, false, 1, true, true, false, false
        ));
    }

    // === Tests for writeCounterChanged bypassing adaptive throttle ===

    @Test
    void needRerender_true_whenWriteCounterChanged_evenWithAdaptiveThrottle() {
        // KEY FIX: writeCounter change is detected even when adaptive throttle
        // is active (adaptiveSkipEveryN > 1).  This was the root cause of the
        // "black screen until drag" bug: the throttle could skip the frame
        // that would have detected the data change.
        assertTrue(computeNeedRerender(
                false, // textureNeedsUpload = false
                false, // not drag throttling
                false, // storage not null
                3,     // adaptive throttle active (skip every 3rd frame)
                true,  // had a last rendered center
                true,  // center equals last
                true,  // write counter CHANGED
                false  // cached data not null
        ));
    }

    @Test
    void needRerender_true_whenWriteCounterChanged_noThrottle() {
        assertTrue(computeNeedRerender(
                false, false, false, 1, true, true, true, false
        ));
    }

    @Test
    void needRerender_false_whenIdle_andDataUnchanged() {
        assertFalse(computeNeedRerender(
                false, false, false, 1, true, true, false, false
        ));
    }

    @Test
    void needRerender_true_whenCenterChanged() {
        assertTrue(computeNeedRerender(
                false, false, false, 1, true, false, false, false
        ));
    }

    @Test
    void needRerender_true_whenCachedRenderDataNull() {
        assertTrue(computeNeedRerender(
                false, false, false, 1, true, true, false, true
        ));
    }

    @Test
    void needRerender_true_whenNoLastRenderedCenter() {
        assertTrue(computeNeedRerender(
                false, false, false, 1, false, false, false, false
        ));
    }

    // === Tests for needsInitialQueue flag ===

    @Test
    void shouldQueueRange_true_whenNeedsInitialQueue_evenIfRangeMatches() {
        assertTrue(shouldQueueRange(true, true));
    }

    @Test
    void shouldQueueRange_true_whenNeedsInitialQueue_andRangeDifferent() {
        assertTrue(shouldQueueRange(true, false));
    }

    @Test
    void shouldQueueRange_true_whenNotInitial_andRangeDifferent() {
        assertTrue(shouldQueueRange(false, false));
    }

    @Test
    void shouldQueueRange_false_whenNotInitial_andRangeMatches() {
        assertFalse(shouldQueueRange(false, true));
    }

    // === Tests for preload=0 when needsInitialQueue ===

    @Test
    void preload_zero_whenNeedsInitialQueue_evenIfPreloadEnabled() {
        // KEY FIX: when needsInitialQueue is true, preload MUST be 0 so the
        // computed range matches the early queue range.  Otherwise, the range
        // difference causes workManager.queueRange() to NOT dedup, which
        // cancels the early queue's in-flight work.
        assertEquals(0, computePreload(
                true,  // enablePreload
                true,  // needsInitialQueue
                true,  // preloadOnlyWhenIdle
                true,  // isSetup
                0,     // activeBatchCount (no batches yet)
                128    // preloadRadius
        ));
    }

    @Test
    void preload_nonzero_whenNotInitialQueue_andWorkersIdle() {
        assertEquals(128, computePreload(
                true, false, true, true, 0, 128
        ));
    }

    @Test
    void preload_zero_whenNotInitialQueue_andWorkersBusy() {
        assertEquals(0, computePreload(
                true, false, true, true, 5, 128
        ));
    }

    @Test
    void preload_zero_whenPreloadDisabled() {
        assertEquals(0, computePreload(
                false, false, true, true, 0, 128
        ));
    }

    // === Edge case: drag throttle interaction ===

    @Test
    void needRerender_false_duringDragThrottle_evenIfDataChanged() {
        assertFalse(computeNeedRerender(
                false, true, false, 1, true, false, true, false
        ));
    }

    // === Edge case: adaptive throttle without data change ===

    @Test
    void needRerender_false_whenAdaptiveThrottle_andNoDataChange_andCachedDataExists() {
        // When adaptive throttle is active and no data changed, skip rendering
        // (unless cachedRenderData is null, which is checked on Nth frame).
        assertFalse(computeNeedRerender(
                false, // textureNeedsUpload
                false, // not drag throttling
                false, // storage not null
                3,     // adaptive throttle
                true,  // had last center
                true,  // center equals
                false, // write counter unchanged
                false  // cached data not null
        ));
    }
}
