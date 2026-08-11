package caeruleusTait.world.preview.domain.preview.accuracy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightSampleSpecTest {
    @Test
    void latticeCoversChunkWithStride() {
        List<int[]> points = HeightSampleSpec.blockPositionsInChunk(0, 0, 4);
        assertEquals(16, points.size());
        assertTrue(points.stream().anyMatch(p -> p[0] == 0 && p[1] == 0));
        assertTrue(points.stream().anyMatch(p -> p[0] == 12 && p[1] == 12));
    }

    @Test
    void heightLattice_stride4_count_otherChunk() {
        assertEquals(16, HeightSampleSpec.blockPositionsInChunk(1, 2, 4).size());
    }
}
