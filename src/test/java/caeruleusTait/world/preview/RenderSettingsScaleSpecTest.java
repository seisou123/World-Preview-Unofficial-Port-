package caeruleusTait.world.preview;

import caeruleusTait.world.preview.domain.preview.accuracy.ScaleSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderSettingsScaleSpecTest {
    @Test
    void toScaleSpec_matchesPixelsPerChunk() {
        RenderSettings r = RenderSettings.defaults();
        r.setPixelsPerChunk(16);
        ScaleSpec s = r.toScaleSpec();
        assertEquals(16, s.pixelsPerChunk());
        assertEquals(r.quartExpand(), s.quartExpand());
        assertEquals(r.quartStride(), s.quartStride());
    }
}
