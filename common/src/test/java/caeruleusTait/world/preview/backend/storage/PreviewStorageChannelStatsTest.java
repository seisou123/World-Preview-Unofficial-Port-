package caeruleusTait.world.preview.backend.storage;

import caeruleusTait.world.preview.backend.sampler.SampleQuery;
import net.minecraft.core.QuartPos;
import org.junit.jupiter.api.Test;

import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_BIOME;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_HEIGHT;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_NOISE_TEMPERATURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Channel-level statistics on the shared sampling storage: bounds/count must
 * be tracked per channel (biome/height/noise) so consumers never mistake
 * "biome sampled here" for "height sampled here".
 */
class PreviewStorageChannelStatsTest {

    private static final int Y = 63;
    private static final int QY = QuartPos.fromBlock(Y);

    @Test
    void channelBoundsAreIndependentPerChannel() {
        PreviewStorage storage = new PreviewStorage(-64, 320);

        // Biome channel: one section starting at quart 0 (block 0..255).
        PreviewSection biomeSec = storage.section4(0, QY, 0, FLAG_BIOME);
        biomeSec.set(0 - biomeSec.quartX(), 0 - biomeSec.quartZ(), (short) 7);

        // Height channel: a section in a different X region, stored on the y=0
        // layer (the heightmap convention).
        final int heightQuartX = 1 << (PreviewSection.SHIFT + 2); // quart 256 -> block 1024
        PreviewSection heightSec = storage.section4(heightQuartX, 0, 0, FLAG_HEIGHT);
        heightSec.set(heightQuartX - heightSec.quartX(), 0 - heightSec.quartZ(), (short) 100);

        // Biome bounds reflect only the biome section.
        int[] biomeBounds = storage.sampledBounds(Y, FLAG_BIOME);
        assertNotNull(biomeBounds);
        assertEquals(0, biomeBounds[0]);
        assertEquals(0, biomeBounds[1]);
        assertEquals(256, biomeBounds[2]);
        assertEquals(256, biomeBounds[3]);

        // Height bounds reflect only the height section, on the y=0 layer.
        int[] heightBounds = storage.sampledBounds(0, FLAG_HEIGHT);
        assertNotNull(heightBounds);
        assertEquals(1024, heightBounds[0]);
        assertEquals(0, heightBounds[1]);
        assertEquals(1280, heightBounds[2]);
        assertEquals(256, heightBounds[3]);

        // Cross-channel reads on the same layer stay empty.
        assertNull(storage.sampledBounds(Y, FLAG_HEIGHT), "height channel was never written on the biome layer");
        assertNull(storage.sampledBounds(0, FLAG_BIOME), "biome channel was never written on the y=0 layer");
    }

    @Test
    void channelCountIsIndependentPerChannel() {
        PreviewStorage storage = new PreviewStorage(-64, 320);

        PreviewSection biomeSec = storage.section4(0, QY, 0, FLAG_BIOME);
        biomeSec.set(0 - biomeSec.quartX(), 0 - biomeSec.quartZ(), (short) 7);

        // A full uncompressed section counts 64*64 quarts.
        assertEquals(64 * 64, storage.sampledCount(Y, FLAG_BIOME));
        assertEquals(0, storage.sampledCount(Y, FLAG_HEIGHT), "height not written on this layer");
        assertEquals(0, storage.sampledCount(0, FLAG_BIOME), "biome not written on y=0");
    }

    @Test
    void legacySignaturesDelegateToTheBiomeChannel() {
        PreviewStorage storage = new PreviewStorage(-64, 320);
        PreviewSection biomeSec = storage.section4(0, QY, 0, FLAG_BIOME);
        biomeSec.set(0 - biomeSec.quartX(), 0 - biomeSec.quartZ(), (short) 3);

        int[] legacyBounds = storage.sampledBounds(Y);
        int[] channelBounds = storage.sampledBounds(Y, FLAG_BIOME);
        assertNotNull(legacyBounds);
        assertNotNull(channelBounds);
        assertEquals(channelBounds[0], legacyBounds[0]);
        assertEquals(channelBounds[3], legacyBounds[3]);
        assertEquals(storage.sampledCount(Y, FLAG_BIOME), storage.sampledCount(Y));
    }

    @Test
    void outOfRangeLayerYieldsNullAndZero() {
        PreviewStorage storage = new PreviewStorage(-64, 320);
        assertNull(storage.sampledBounds(-1000, FLAG_BIOME));
        assertNull(storage.sampledBounds(10_000, FLAG_BIOME));
        assertEquals(0, storage.sampledCount(-1000, FLAG_BIOME));
        assertEquals(0, storage.sampledCount(10_000, FLAG_BIOME));
    }

    @Test
    void sampleQueryExposesSharedFactsWithTriStateAvailability() {
        PreviewStorage storage = new PreviewStorage(-64, 320);
        SampleQuery query = SampleQuery.of(storage, true);

        // Untouched layer and channel: explicit NOT_SAMPLED.
        SampleQuery.BiomeSample missing = query.biomeAt(100_000, Y, 100_000);
        assertEquals(SampleQuery.Availability.NOT_SAMPLED, missing.availability());

        // Written positions become PRESENT with the stored value.
        PreviewSection biomeSec = storage.section4(0, QY, 0, FLAG_BIOME);
        biomeSec.set(0 - biomeSec.quartX(), 0 - biomeSec.quartZ(), (short) 12);
        SampleQuery.BiomeSample present = query.biomeAt(0, Y, 0);
        assertTrue(present.present());
        assertEquals(12, present.id());

        // Real heights: empty until the heightmap channel is written.
        assertTrue(query.realHeightAt(0, 0).isEmpty());
        PreviewSection heightSec = storage.section4(0, 0, 0, FLAG_HEIGHT);
        heightSec.set(0 - heightSec.quartX(), 0 - heightSec.quartZ(), (short) 71);
        assertEquals(71, query.realHeightAt(0, 0).getAsInt());

        // Noise: unsupported reports empty without touching storage.
        SampleQuery noNoise = SampleQuery.of(storage, false);
        assertFalse(noNoise.noiseSupported());
        assertTrue(noNoise.noiseAt(0, Y, 0, FLAG_NOISE_TEMPERATURE).isEmpty());
    }
}
