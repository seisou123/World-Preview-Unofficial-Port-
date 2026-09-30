package caeruleusTait.world.preview;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Headless semantics of the zoom-level plumbing behind the settings
 * round-trip: {@link RenderSettings#setPixelsPerChunk(int)} maps each ladder
 * level onto quartExpand/quartStride and round-trips, {@link RenderSettings#apply(RenderSettings)}
 * mutates the destination in place (live instances observe new values through
 * the same reference, as PreviewDisplay does), and {@link RenderSettings#normalized()}
 * keeps valid values untouched.
 */
class RenderSettingsTest {

    @Test
    void renderOnlyLevelsKeepQuartStrideAtOne() {
        for (int ppc : new int[]{16, 8, 4}) {
            RenderSettings rs = RenderSettings.defaults();
            rs.setPixelsPerChunk(ppc);
            assertEquals(1, rs.quartStride(), "quartStride at " + ppc + " px/chunk");
            assertEquals(1, RenderSettings.samplerStrideFor(ppc),
                    "sampler stride at " + ppc + " px/chunk must stay render-only");
            assertEquals(ppc, rs.pixelsPerChunk(), "pixelsPerChunk must round-trip");
        }
    }

    @Test
    void strideLevelsWidenQuartStride() {
        RenderSettings two = RenderSettings.defaults();
        two.setPixelsPerChunk(2);
        assertEquals(1, two.quartExpand());
        assertEquals(2, two.quartStride());
        assertEquals(2, two.pixelsPerChunk());

        RenderSettings one = RenderSettings.defaults();
        one.setPixelsPerChunk(1);
        assertEquals(1, one.quartExpand());
        assertEquals(4, one.quartStride());
        assertEquals(1, one.pixelsPerChunk());
    }

    @Test
    void applyMutatesTheDestinationInPlace() {
        RenderSettings live = RenderSettings.defaults();
        RenderSettings observed = live; // same reference a PreviewDisplay captured
        RenderSettings pending = RenderSettings.defaults();
        pending.setPixelsPerChunk(16);
        pending.samplerType = RenderSettings.SamplerType.FULL;

        live.apply(pending);

        assertSame(live, observed);
        assertEquals(16, observed.pixelsPerChunk(),
                "a same-reference observer must see the applied pixels-per-chunk");
        assertEquals(RenderSettings.SamplerType.FULL, observed.samplerType);
    }

    @Test
    void applyCopiesTheDimension() {
        RenderSettings live = RenderSettings.defaults();
        RenderSettings pending = RenderSettings.defaults();
        pending.dimension = Identifier.parse("minecraft:the_nether");

        live.apply(pending);

        assertEquals(Identifier.parse("minecraft:the_nether"), live.dimension);
    }

    @Test
    void normalizedKeepsValidPixelLevels() {
        for (int ppc : new int[]{16, 8, 4, 2, 1}) {
            RenderSettings rs = RenderSettings.defaults();
            rs.setPixelsPerChunk(ppc);
            assertEquals(ppc, rs.normalized().pixelsPerChunk(),
                    "normalized() must not alter a valid level");
        }
    }

    @Test
    void normalizedKeepsAValidDimension() {
        RenderSettings rs = RenderSettings.defaults();
        rs.dimension = Identifier.parse("minecraft:the_end");

        assertEquals(rs.dimension, rs.normalized().dimension);
    }
}
