package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreviewConfig;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the settings screen's Reset button.
 *
 * <p>{@link SettingsScreen#resetPage(AbstractSettingsPage)} is the entry point the
 * footer button uses. It previously called {@code PageRegistry.resetAll()}, which
 * only resets {@code ConfigBinding}s — and no page registers any — so Reset was a
 * no-op.
 */
class SettingsResetTest {

    /** T1: Reset restores at least one field of the page to its default. */
    @Test
    void resetRestoresPageFieldsToDefaults() {
        WorldPreviewConfig cfg = new WorldPreviewConfig();
        cfg.showBiomeCounts = true;
        cfg.showStatistics = true;
        cfg.buildFullVertChunk = true;
        GeneralSettingsPage page = new GeneralSettingsPage(cfg, null);

        SettingsScreen.resetPage(page);

        assertFalse(cfg.showBiomeCounts, "showBiomeCounts should be reset to its default (false)");
        assertFalse(cfg.showStatistics, "showStatistics should be reset to its default (false)");
        assertFalse(cfg.buildFullVertChunk, "buildFullVertChunk should be reset to its default (false)");
    }

    /** T2: Reset restores scrollWheelZooms to the WorldPreviewConfig field default. */
    @Test
    void resetRestoresScrollWheelZoomsToConfigDefault() {
        WorldPreviewConfig cfg = new WorldPreviewConfig();
        cfg.scrollWheelZooms = !WorldPreviewConfig.defaults().scrollWheelZooms;
        assertNotEquals(WorldPreviewConfig.defaults().scrollWheelZooms, cfg.scrollWheelZooms,
                "precondition: field must start away from its default");
        GeneralSettingsPage page = new GeneralSettingsPage(cfg, null);

        SettingsScreen.resetPage(page);

        assertEquals(WorldPreviewConfig.defaults().scrollWheelZooms, cfg.scrollWheelZooms,
                "reset() must agree with the WorldPreviewConfig field default");
    }

    @Test
    void resetRestoresThreadCountToDefault() {
        WorldPreviewConfig cfg = new WorldPreviewConfig();
        cfg.setNumThreads(1);
        GeneralSettingsPage page = new GeneralSettingsPage(cfg, null);

        SettingsScreen.resetPage(page);

        assertEquals(WorldPreviewConfig.defaults().numThreads(), cfg.numThreads());
    }

    @Test
    void resetIsNullSafe() {
        SettingsScreen.resetPage(null);
    }

    /** Cache page's override restores defaults rather than throwing. */
    @Test
    void cachePageResetRestoresDefaults() {
        WorldPreviewConfig cfg = new WorldPreviewConfig();
        cfg.cacheInGame = false;
        cfg.cacheInNew = true;
        cfg.enableCompression = false;
        CacheSettingsPage page = new CacheSettingsPage(cfg, null);

        SettingsScreen.resetPage(page);

        assertTrue(cfg.cacheInGame);
        assertFalse(cfg.cacheInNew);
        assertTrue(cfg.enableCompression);
    }

    /** Heightmap page's override restores defaults, including the colormap. */
    @Test
    void heightmapPageResetRestoresDefaults() {
        WorldPreviewConfig cfg = new WorldPreviewConfig();
        cfg.heightmapMinY = 0;
        cfg.heightmapMaxY = 8;
        cfg.onlySampleInVisualRange = false;
        cfg.colorMap = "world_preview:grayscale";
        HeightmapSettingsPage page = new HeightmapSettingsPage(cfg, null);

        SettingsScreen.resetPage(page);

        assertEquals(WorldPreviewConfig.defaults().heightmapMinY, cfg.heightmapMinY);
        assertEquals(WorldPreviewConfig.defaults().heightmapMaxY, cfg.heightmapMaxY);
        assertTrue(cfg.onlySampleInVisualRange);
        assertEquals(WorldPreviewConfig.defaults().colorMap, cfg.colorMap);
    }

    /** Dimension page's reset leaves the pending dimension unset (the default). */
    @Test
    void dimensionPageResetLeavesDimensionUnset() {
        RenderSettings rs = RenderSettings.defaults();
        rs.dimension = Identifier.parse("minecraft:the_nether");
        DimensionSettingsPage page = new DimensionSettingsPage(rs, null);

        SettingsScreen.resetPage(page);

        assertNull(rs.dimension,
                "reset() must restore the 'default' state (null), which the container resolves to the Overworld");
    }

    /** The display fallback keeps a still-valid dimension and mutates nothing. */
    @Test
    void displayDimensionKeepsExplicitValueWithoutMutatingAnything() {
        RenderSettings rs = RenderSettings.defaults();
        rs.dimension = Identifier.parse("minecraft:the_end");
        List<Identifier> keys = List.of(
                Identifier.parse("minecraft:overworld"),
                Identifier.parse("minecraft:the_end"));

        Identifier selected = DimensionSettingsPage.displayDimension(rs.dimension, keys);

        assertEquals(Identifier.parse("minecraft:the_end"), selected);
        assertEquals(Identifier.parse("minecraft:the_end"), rs.dimension,
                "the display fallback must not write back into the pending settings");
    }

    /** A null (default) dimension displays the Overworld when it is a level stem. */
    @Test
    void displayDimensionPrefersOverworldForNull() {
        Identifier overworld = Identifier.parse("minecraft:overworld");
        // "minecraft:aaa" would be the first sorted key — the Overworld must win anyway.
        assertEquals(overworld, DimensionSettingsPage.displayDimension(null,
                List.of(Identifier.parse("minecraft:aaa"), overworld)));
    }

    /** Without an Overworld stem the display falls back to the first sorted key. */
    @Test
    void displayDimensionFallsBackToFirstSortedKeyWithoutOverworld() {
        assertEquals(Identifier.parse("minecraft:aaa"), DimensionSettingsPage.displayDimension(null,
                List.of(Identifier.parse("minecraft:zzz"), Identifier.parse("minecraft:aaa"))));
    }

    /** A stale dimension key (no longer among the stems) displays the fallback. */
    @Test
    void displayDimensionFallsBackWhenCurrentIsUnknown() {
        Identifier overworld = Identifier.parse("minecraft:overworld");

        assertEquals(overworld, DimensionSettingsPage.displayDimension(
                Identifier.parse("minecraft:gone"),
                List.of(overworld)));
    }
}
