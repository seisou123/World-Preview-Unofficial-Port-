package caeruleusTait.world.preview.backend.terrain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Directional regression tests for hillshade lighting.
 * <p>
 * Height field convention: row index = world +Z (south), so a ramp whose heights
 * rise with the row index is a north-facing slope. The documented light convention
 * is azimuth 0 = north, clockwise, default 315 = NW. If the light vector's Z
 * component is mirrored, north- and south-facing slopes swap brightness and the
 * rendered relief reads inverted (ridges as valleys).
 * </p>
 * <p>
 * Heights are short values in world-block units; ramps based above 255 pin the
 * removal of the old byte height field that flattened all high terrain.
 * </p>
 */
class HillshadeRendererTest {

    private static final int W = 64;
    private static final int H = 64;
    private static final int CENTER = (H / 2) * W + (W / 2);

    private short[] flatField() {
        short[] h = new short[W * H];
        Arrays.fill(h, (short) 128);
        return h;
    }

    /** Heights rise toward larger rows (south): the slope faces north. */
    private short[] rampRisingSouth(int base) {
        short[] h = new short[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                h[y * W + x] = (short) (base + 2 * y);
            }
        }
        return h;
    }

    /** Heights rise toward row 0 (north): the slope faces south. */
    private short[] rampRisingNorth(int base) {
        short[] h = new short[W * H];
        for (int y = 0; y < H; y++) {
            int v = base + 2 * (H - 1 - y);
            for (int x = 0; x < W; x++) {
                h[y * W + x] = (short) v;
            }
        }
        return h;
    }

    private int shadeAtCenter(HillshadeRenderer renderer, short[] field) {
        byte[] shade = renderer.render(field, W, H, 4f);
        return shade[CENTER] & 0xFF;
    }

    @Test
    void flatFieldShadesUniformly() {
        HillshadeRenderer renderer = new HillshadeRenderer(315f, 45f, 0.3f, 1.0f, 0.5f);
        byte[] shade = renderer.render(flatField(), W, H, 4f);
        int first = shade[0] & 0xFF;
        for (int i = 1; i < shade.length; i++) {
            assertTrue(Math.abs((shade[i] & 0xFF) - first) <= 1,
                    "flat field must shade uniformly, saw " + first + " vs " + (shade[i] & 0xFF));
        }
    }

    @Test
    void defaultNorthWestLightIlluminatesNorthFacingSlopes() {
        HillshadeRenderer renderer = new HillshadeRenderer();
        int northFacing = shadeAtCenter(renderer, rampRisingSouth(128));
        int southFacing = shadeAtCenter(renderer, rampRisingNorth(128));
        assertTrue(northFacing - southFacing >= 5,
                "under default NW light the north-facing slope ("
                        + northFacing + ") must be brighter than the south-facing one ("
                        + southFacing + ")");
    }

    @Test
    void azimuthZeroMeansLightFromNorth() {
        HillshadeRenderer renderer = new HillshadeRenderer(0f, 45f, 0.3f, 1.0f, 0.5f);
        int northFacing = shadeAtCenter(renderer, rampRisingSouth(128));
        int southFacing = shadeAtCenter(renderer, rampRisingNorth(128));
        assertTrue(northFacing - southFacing >= 5,
                "azimuth 0 = north light: north-facing slope (" + northFacing
                        + ") must be brighter than south-facing (" + southFacing + ")");
    }

    @Test
    void azimuth180MeansLightFromSouth() {
        HillshadeRenderer renderer = new HillshadeRenderer(180f, 45f, 0.3f, 1.0f, 0.5f);
        int northFacing = shadeAtCenter(renderer, rampRisingSouth(128));
        int southFacing = shadeAtCenter(renderer, rampRisingNorth(128));
        assertTrue(southFacing - northFacing >= 5,
                "azimuth 180 = south light: south-facing slope (" + southFacing
                        + ") must be brighter than north-facing (" + northFacing + ")");
    }

    @Test
    void reliefSurvivesAboveLegacyByteRange() {
        // Terrain between y=300 and y=426: the old byte field clamped all of it flat
        HillshadeRenderer renderer = new HillshadeRenderer(0f, 45f, 0.3f, 1.0f, 0.5f);
        int northFacing = shadeAtCenter(renderer, rampRisingSouth(300));
        int southFacing = shadeAtCenter(renderer, rampRisingNorth(300));
        assertTrue(northFacing - southFacing >= 5,
                "slopes above y=255 must still shade directionally, north-facing ("
                        + northFacing + ") vs south-facing (" + southFacing + ")");
    }
}
