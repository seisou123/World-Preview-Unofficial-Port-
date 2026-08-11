package caeruleusTait.world.preview.backend.storage;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.backend.analysis.Region;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewStorageRegionTest {
    @BeforeEach
    void initializeWorldPreview() throws Exception {
        WorldPreview preview = new WorldPreview();
        Field instance = WorldPreview.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, preview);
        Field cfg = WorldPreview.class.getDeclaredField("cfg");
        cfg.setAccessible(true);
        WorldPreviewConfig config = WorldPreviewConfig.defaults();
        config.enableCompression = false;
        cfg.set(preview, config);
        Field settings = WorldPreview.class.getDeclaredField("renderSettings");
        settings.setAccessible(true);
        settings.set(preview, RenderSettings.defaults());
    }

    @Test
    void regionReadsExposePresentAndMissingSamples() {
        PreviewStorage storage = new PreviewStorage(0, 64);
        storage.section4(0, 0, 0, PreviewStorage.FLAG_BIOME).set(0, 0, (short) 42);
        Region region = Region.of(0, 0, 8, 8);

        PreviewStorage.RegionSamples samples = storage.readRegion(region, 0, PreviewStorage.FLAG_BIOME, 4);

        assertEquals(9, samples.expectedPointCount());
        assertEquals(9, samples.points().size());
        assertEquals(1, samples.presentPointCount());
        assertEquals(42, samples.points().get(0).value());
        assertEquals(Short.MIN_VALUE, samples.points().get(1).value());
    }

    @Test
    void profileIncludesBothEndpointsAndValidatesStep() {
        PreviewStorage storage = new PreviewStorage(0, 64);
        List<PreviewStorage.SamplePoint> profile = storage.readProfile(0, 0, 10, 0, 0,
                PreviewStorage.FLAG_BIOME, 4);

        assertEquals(4, profile.size());
        assertEquals(List.of(0, 4, 8, 10), profile.stream().map(PreviewStorage.SamplePoint::x).toList());
        assertEquals(List.of(0, 0, 0, 0), profile.stream().map(PreviewStorage.SamplePoint::z).toList());
        assertThrows(IllegalArgumentException.class,
                () -> storage.readRegion(Region.of(0, 0, 1, 1), 0, PreviewStorage.FLAG_BIOME, 0));
    }
}
