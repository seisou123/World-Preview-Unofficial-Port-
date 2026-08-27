package caeruleusTait.world.preview.client.gui.widgets;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PreviewRenderThrottle} — the render-skip and
 * initial-queue state machine that fixed the "black screen until drag" bug.
 */
class PreviewDisplayRenderSkipTest {

    private static final BlockPos CENTER = new BlockPos(100, 64, 100);

    // === Tests for textureNeedsUpload flag ===

    @Test
    void needRerender_true_whenTextureNeedsUpload_andStorageNotNull() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        assertTrue(t.shouldRerender(false, true, 5, CENTER, true));
    }

    @Test
    void needRerender_false_whenTextureNeedsUpload_butStorageNull() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        assertFalse(t.shouldRerender(false, false, 0, CENTER, true));
    }

    @Test
    void needRerender_false_whenTextureNeedsUpload_butDragThrottling() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        assertFalse(t.shouldRerender(true, true, 5, CENTER, true));
    }

    // === Tests for writeCounterChanged bypassing adaptive throttle ===

    @Test
    void needRerender_true_whenWriteCounterChanged_evenWithAdaptiveThrottle() {
        // KEY FIX: writeCounter change is detected even when adaptive throttle
        // is active. This was the root cause of the "black screen until drag"
        // bug: the throttle could skip the frame that would have detected the
        // data change.
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        t.markTextureUploaded();
        t.markRendered(CENTER, 5);

        assertTrue(t.shouldRerender(false, true, 6, CENTER, true));
    }

    @Test
    void needRerender_true_whenWriteCounterChanged_noThrottle() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        t.markTextureUploaded();
        t.markRendered(CENTER, 5);

        assertTrue(t.shouldRerender(false, true, 6, CENTER, true));
    }

    @Test
    void needRerender_false_whenIdle_andDataUnchanged() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        t.markTextureUploaded();
        t.markRendered(CENTER, 5);

        assertFalse(t.shouldRerender(false, true, 5, CENTER, true));
    }

    @Test
    void needRerender_true_whenCenterChanged() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        t.markTextureUploaded();
        t.markRendered(CENTER, 5);

        assertTrue(t.shouldRerender(false, true, 5, CENTER.offset(16, 0, 0), true));
    }

    @Test
    void needRerender_true_whenCachedRenderDataNull() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        t.markTextureUploaded();
        t.markRendered(CENTER, 5);

        assertTrue(t.shouldRerender(false, true, 5, CENTER, false));
    }

    @Test
    void needRerender_true_whenNoLastRenderedCenter() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        t.markTextureUploaded();

        assertTrue(t.shouldRerender(false, true, 5, CENTER, true));
    }

    // === Tests for needsInitialQueue flag ===

    @Test
    void needsInitialQueue_trueByDefault_andClearable() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        assertTrue(t.needsInitialQueue());

        t.clearNeedsInitialQueue();
        assertFalse(t.needsInitialQueue());
    }

    @Test
    void invalidateAll_reArmsInitialQueue() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        t.clearNeedsInitialQueue();

        t.invalidateAll();

        assertTrue(t.needsInitialQueue());
        assertTrue(t.textureNeedsUpload());
    }

    // === Edge case: drag throttle interaction ===

    @Test
    void needRerender_false_duringDragThrottle_evenIfDataChanged() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        t.markTextureUploaded();
        t.markRendered(CENTER, 5);

        assertFalse(t.shouldRerender(true, true, 6, CENTER.offset(16, 0, 0), true));
    }

    // === Adaptive skip counting ===

    @Test
    void adaptiveSkipEveryN_defaultsToOne() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        assertEquals(1, t.adaptiveSkipEveryN());
    }

    @Test
    void invalidateAfterResize_keepsQueueState_butForcesTextureUpload() {
        PreviewRenderThrottle t = new PreviewRenderThrottle();
        t.clearNeedsInitialQueue();
        t.markTextureUploaded();
        t.markRendered(CENTER, 5);

        t.invalidateAfterResize();

        assertFalse(t.needsInitialQueue());
        assertTrue(t.textureNeedsUpload());
        // Rendered-content cache is stale after resize
        assertTrue(t.shouldRerender(false, true, 5, CENTER, true));
    }
}
