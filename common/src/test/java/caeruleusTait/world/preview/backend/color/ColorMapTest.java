package caeruleusTait.world.preview.backend.color;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Endpoint / midpoint assertions for {@link ColorMap#get(float)} against the
 * shipped {@code viridis.json}, which pins the interpolation direction: a
 * reversed lerp still returns the correct endpoints but swaps the midpoint
 * neighbourhood.
 */
class ColorMapTest {

    private static final float EPS = 1.0e-4f;

    private static ColorMap viridis() {
        try (InputStream in = ColorMapTest.class.getResourceAsStream(
                "/data/world_preview/colormap_preview/viridis.json")) {
            assertNotNull(in, "viridis.json must ship with the mod resources");
            Gson gson = new GsonBuilder().create();
            ColorMap.RawColorMap raw = gson.fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), ColorMap.RawColorMap.class);
            assertEquals(256, raw.data().size(), "viridis.json has 256 sample points");
            return new ColorMap(null, raw);
        } catch (Exception e) {
            throw new AssertionError("failed to load viridis.json", e);
        }
    }

    @Test
    void endpointsMatchTheColormapData() {
        ColorMap map = viridis();

        ColorMap.Color first = map.get(0.0f);
        assertEquals(0.267004f, first.r(), EPS);
        assertEquals(0.004874f, first.g(), EPS);
        assertEquals(0.329415f, first.b(), EPS);

        ColorMap.Color last = map.get(1.0f);
        assertEquals(0.993248f, last.r(), EPS);
        assertEquals(0.906157f, last.g(), EPS);
        assertEquals(0.143936f, last.b(), EPS);
    }

    @Test
    void midpointSitsBetweenTheTwoCentralEntries() {
        ColorMap map = viridis();

        ColorMap.Color mid = map.get(0.5f);
        // Entries 127 and 128 straddle the midpoint; the interpolated value must
        // lie between them channel-wise.
        assertChannelBetween(mid.r(), 0.127568f, 0.128729f);
        assertChannelBetween(mid.g(), 0.563265f, 0.566949f);
        assertChannelBetween(mid.b(), 0.550556f, 0.551229f);

        // A reversed interpolation would collapse onto the other neighbour.
        assertEquals(0.128160f, mid.r(), EPS);
        assertEquals(0.565106f, mid.g(), EPS);
        assertEquals(0.550893f, mid.b(), EPS);
    }

    @Test
    void interpolationMovesTowardTheUpperEntry() {
        ColorMap map = viridis();

        // viridis' green channel rises monotonically from 0 to 1, so a correct
        // interpolation makes the quarter points strictly ordered.
        float quarter = map.get(0.25f).g();
        float half = map.get(0.5f).g();
        float threeQuarters = map.get(0.75f).g();
        assertTrue(quarter < half && half < threeQuarters,
                "green channel must increase with position: " + quarter + " / " + half + " / " + threeQuarters);

        // Just past entry 63 the result must sit next to entry 63, not entry 64.
        ColorMap.Color nearLower = map.get(63.05f / 255f);
        ColorMap.Color nearUpper = map.get(63.95f / 255f);
        assertEquals(0.231674f, nearLower.r(), 1.0e-3f);
        assertEquals(0.229739f, nearUpper.r(), 1.0e-3f);
        assertTrue(distanceTo(nearLower, 0.231674f, 0.318106f, 0.544834f)
                        < distanceTo(nearLower, 0.229739f, 0.322361f, 0.545706f),
                "a position just above an entry must interpolate from that entry, not toward it");
    }

    @Test
    void argbMatchesTheInterpolatedColor() {
        ColorMap map = viridis();
        assertEquals(0xFF440154, map.getARGB(0.0f));
        assertEquals(0xFFFDE724, map.getARGB(1.0f));
        ColorMap.Color mid = map.get(0.5f);
        int expected = 0xFF000000
                | (((int) (mid.r() * 255f)) & 0xFF) << 16
                | (((int) (mid.g() * 255f)) & 0xFF) << 8
                | (((int) (mid.b() * 255f)) & 0xFF);
        assertEquals(expected, map.getARGB(0.5f));
    }

    private static void assertChannelBetween(float value, float a, float b) {
        float low = Math.min(a, b);
        float high = Math.max(a, b);
        assertTrue(value >= low - EPS && value <= high + EPS,
                value + " must lie between " + low + " and " + high);
    }

    private static float distanceTo(ColorMap.Color c, float r, float g, float b) {
        float dr = c.r() - r;
        float dg = c.g() - g;
        float db = c.b() - b;
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
