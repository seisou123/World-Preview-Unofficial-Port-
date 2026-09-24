package caeruleusTait.world.preview.backend.storage;

import caeruleusTait.world.preview.WorldPreviewConfig;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Injectivity and layout-stability tests for
 * {@link PreviewStorageCacheManager#configSamplingFlags} (no Minecraft
 * bootstrap needed; the helper only reads plain config fields).
 *
 * <p>Regression: the v3 cache key clamped {@code heightmapMinY/MaxY} to 0..255
 * (colliding -64 with 0 and anything above 255 with 255) and did not encode
 * {@code onlySampleInVisualRange} at all, so two different height-sampling
 * configs could share one cache filename and silently reuse height data
 * sampled with a different Y range/mode.
 */
class PreviewStorageCacheKeyFlagsTest {

    private static WorldPreviewConfig cfg(int minY, int maxY, boolean onlyVisual, boolean storeNoise, boolean compression) {
        WorldPreviewConfig config = new WorldPreviewConfig();
        config.heightmapMinY = minY;
        config.heightmapMaxY = maxY;
        config.onlySampleInVisualRange = onlyVisual;
        config.storeNoiseSamples = storeNoise;
        config.enableCompression = compression;
        return config;
    }

    @Test
    void defaultConfigHasStableFlags() {
        // Defaults: heightmapMinY 32 -> (32+64)=96 at bit 18, heightmapMaxY 255
        // -> (255+64)=319 at bit 28, onlySampleInVisualRange true -> bit 38,
        // enableCompression true -> bit 16, storeNoiseSamples false.
        // 0x53F1810000 = 360534048768.
        assertEquals(360534048768L,
                PreviewStorageCacheManager.configSamplingFlags(new WorldPreviewConfig()));
        assertEquals((1L << 16) | (96L << 18) | (319L << 28) | (1L << 38),
                PreviewStorageCacheManager.configSamplingFlags(new WorldPreviewConfig()));
    }

    @Test
    void configMatrixIsInjective() {
        final int[] mins = {-64, 0, 32, 200};
        final int[] maxs = {255, 512};
        Set<Long> seen = new HashSet<>();
        int combos = 0;
        for (int min : mins) {
            for (int max : maxs) {
                for (boolean onlyVisual : new boolean[] {false, true}) {
                    for (boolean storeNoise : new boolean[] {false, true}) {
                        for (boolean compression : new boolean[] {false, true}) {
                            long flags = PreviewStorageCacheManager.configSamplingFlags(
                                    cfg(min, max, onlyVisual, storeNoise, compression));
                            combos++;
                            assertFalse(seen.contains(flags),
                                    "flag collision for config min=" + min + " max=" + max
                                            + " onlyVisual=" + onlyVisual
                                            + " storeNoise=" + storeNoise
                                            + " compression=" + compression);
                            seen.add(flags);
                        }
                    }
                }
            }
        }
        assertEquals(4 * 2 * 2 * 2 * 2, combos);
        assertEquals(combos, seen.size(), "every distinct height-sampling config must yield distinct flags");
    }

    @Test
    void negativeMinNoLongerCollidesWithZero() {
        assertNotEquals(
                PreviewStorageCacheManager.configSamplingFlags(cfg(-64, 255, true, false, true)),
                PreviewStorageCacheManager.configSamplingFlags(cfg(0, 255, true, false, true)),
                "v3 clamped -64 and 0 to the same key component");
    }

    @Test
    void maxAbove255NoLongerCollidesWith255() {
        assertNotEquals(
                PreviewStorageCacheManager.configSamplingFlags(cfg(32, 512, true, false, true)),
                PreviewStorageCacheManager.configSamplingFlags(cfg(32, 255, true, false, true)),
                "v3 clamped anything above 255 down to 255");
    }

    @Test
    void visualModeIsPartOfTheKey() {
        assertNotEquals(
                PreviewStorageCacheManager.configSamplingFlags(cfg(32, 255, true, false, true)),
                PreviewStorageCacheManager.configSamplingFlags(cfg(32, 255, false, false, true)),
                "v3 did not encode onlySampleInVisualRange at all");

        // Flipping ONLY the visual flag must toggle exactly bit 38.
        long on = PreviewStorageCacheManager.configSamplingFlags(cfg(32, 255, true, true, false));
        long off = PreviewStorageCacheManager.configSamplingFlags(cfg(32, 255, false, true, false));
        assertEquals(1L << 38, on ^ off);
    }

    @Test
    void booleanFlagsEachToggleExactlyTheirOwnBit() {
        long base = PreviewStorageCacheManager.configSamplingFlags(cfg(32, 255, true, false, false));
        assertEquals(1L << 17, base
                ^ PreviewStorageCacheManager.configSamplingFlags(cfg(32, 255, true, true, false)));
        assertEquals(1L << 16, base
                ^ PreviewStorageCacheManager.configSamplingFlags(cfg(32, 255, true, false, true)));
    }

    @Test
    void rangeFieldsOccupyTheirOwnBits() {
        final long minField = 0x3FFL << 18;
        final long maxField = 0x3FFL << 28;

        long base = PreviewStorageCacheManager.configSamplingFlags(cfg(32, 255, true, false, false));
        long otherMin = PreviewStorageCacheManager.configSamplingFlags(cfg(31, 255, true, false, false));
        long otherMax = PreviewStorageCacheManager.configSamplingFlags(cfg(32, 256, true, false, false));

        long minDiff = base ^ otherMin;
        assertTrue(minDiff != 0 && minDiff == (minDiff & minField),
                "a heightmapMinY change must only affect bits 18..27, got " + Long.toHexString(minDiff));
        long maxDiff = base ^ otherMax;
        assertTrue(maxDiff != 0 && maxDiff == (maxDiff & maxField),
                "a heightmapMaxY change must only affect bits 28..37, got " + Long.toHexString(maxDiff));

        // Outside their fields the two configs are indistinguishable from the
        // base config: the fields do not bleed into each other or the flags.
        assertEquals(base & ~(minField | maxField), otherMin & ~(minField | maxField));
        assertEquals(base & ~(minField | maxField), otherMax & ~(minField | maxField));
    }

    @Test
    void valuesOutsideTheWindowSaturateDeterministically() {
        // The settings UI accepts -64..512 (HeightmapSettingsPage); values
        // beyond it saturate at the window edges instead of wrapping.
        assertEquals(
                PreviewStorageCacheManager.configSamplingFlags(cfg(-64, 512, true, false, true)),
                PreviewStorageCacheManager.configSamplingFlags(cfg(-10_000, 10_000, true, false, true)));
    }

    @Test
    void unrelatedConfigFieldsDoNotAffectTheFlags() {
        WorldPreviewConfig base = cfg(32, 255, true, false, true);
        WorldPreviewConfig touched = cfg(32, 255, true, false, true);
        touched.sampleHeightmap = !base.sampleHeightmap;
        touched.scrollWheelZooms = !base.scrollWheelZooms;
        touched.sampleStructures = !base.sampleStructures;
        assertEquals(
                PreviewStorageCacheManager.configSamplingFlags(base),
                PreviewStorageCacheManager.configSamplingFlags(touched));
    }
}
