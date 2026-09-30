package caeruleusTait.world.preview.domain.preview.accuracy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewportMappingTest {
    @Test
    void worldTextureRoundTrip_withinOneSampleStep() {
        ScaleSpec scale = ScaleSpec.fromPixelsPerChunk(4);
        ViewportMapping map = new ViewportMapping(0, 64, 0, 256, 256, scale, 2.0);
        int wx = 32;
        int wz = -48;
        int tx = map.worldToTextureX(wx);
        int tz = map.worldToTextureZ(wz);
        int rx = map.textureToWorldX(tx);
        int rz = map.textureToWorldZ(tz);
        assertTrue(Math.abs(rx - wx) <= scale.blockScale(), "x " + rx + " vs " + wx);
        assertTrue(Math.abs(rz - wz) <= scale.blockScale(), "z " + rz + " vs " + wz);
    }

    @Test
    void queueAabb_matchesTextureWorldCorners() {
        ScaleSpec scale = ScaleSpec.fromPixelsPerChunk(4);
        ViewportMapping map = new ViewportMapping(100, 80, 200, 128, 96, scale, 1.0);
        QueueAabb aabb = QueueAabb.fromViewport(map, 0);
        assertEquals(map.worldMinX(), aabb.minX());
        assertEquals(map.worldMinZ(), aabb.minZ());
        assertEquals(map.worldMaxX(), aabb.maxX());
        assertEquals(map.worldMaxZ(), aabb.maxZ());
        assertEquals(80, aabb.y());
    }

    @Test
    void screenConversion_usesGuiScale() {
        ScaleSpec scale = ScaleSpec.fromPixelsPerChunk(4);
        ViewportMapping map = new ViewportMapping(0, 0, 0, 100, 100, scale, 2.0);
        int sx = map.textureToScreenX(50, 10);
        assertEquals(10 + 25, sx);
    }
}
