package caeruleusTait.world.preview.domain.preview.accuracy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureAnchorTest {
    @Test
    void centerIsBbMidpoint() {
        assertEquals(5, StructureAnchor.centerX(0, 10));
        assertEquals(15, StructureAnchor.centerZ(0, 30));
        assertEquals(10, StructureAnchor.centerY(0, 20));
    }
}
