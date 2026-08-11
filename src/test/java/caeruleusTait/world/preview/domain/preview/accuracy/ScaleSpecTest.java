package caeruleusTait.world.preview.domain.preview.accuracy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaleSpecTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16, 32, 64})
    void fromPixelsPerChunk_roundTrips(int ppc) {
        ScaleSpec s = ScaleSpec.fromPixelsPerChunk(ppc);
        assertEquals(ppc, s.pixelsPerChunk());
        assertTrue(s.quartExpand() >= 1);
        assertTrue(s.quartStride() >= 1);
        assertEquals(ppc, (4 * s.quartExpand()) / s.quartStride());
    }

    @Test
    void blockScale_matchesDisplayFormula() {
        ScaleSpec s = ScaleSpec.fromPixelsPerChunk(4);
        assertEquals(1, s.quartExpand());
        assertEquals(1, s.quartStride());
        assertEquals(4, s.blockScale());
    }

    @Test
    void invalidPixelsPerChunk_throws() {
        assertThrows(IllegalArgumentException.class, () -> ScaleSpec.fromPixelsPerChunk(3));
    }
}
