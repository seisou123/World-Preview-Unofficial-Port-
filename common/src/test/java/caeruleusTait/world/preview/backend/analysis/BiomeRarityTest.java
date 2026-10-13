package caeruleusTait.world.preview.backend.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure tests for {@link BiomeRarity}: star thresholds at the boundaries,
 * ordering + limit, zero present samples and the name resolver fallback.
 */
class BiomeRarityTest {

    @Test
    @DisplayName("star thresholds at boundaries: 0, 0.99, 1, 4.99, 5, 14.99, 15")
    void starsAtBoundaries() {
        assertEquals(3, BiomeRarity.stars(0.0));
        assertEquals(3, BiomeRarity.stars(0.99));
        assertEquals(2, BiomeRarity.stars(1.0));
        assertEquals(2, BiomeRarity.stars(4.99));
        assertEquals(1, BiomeRarity.stars(5.0));
        assertEquals(1, BiomeRarity.stars(14.99));
        assertEquals(0, BiomeRarity.stars(15.0));
    }

    @Test
    @DisplayName("rows are sorted by count descending and limited")
    void orderingAndLimit() {
        Map<Short, Long> counts = new LinkedHashMap<>();
        counts.put((short) 1, 50L);
        counts.put((short) 2, 10L);
        counts.put((short) 3, 7L);
        counts.put((short) 4, 2L);

        List<BiomeRarity.RarityRow> rows = BiomeRarity.topBiomes(counts,
                id -> "biome" + id, 100L, 3);

        assertEquals(3, rows.size());
        assertEquals("biome1", rows.get(0).name());
        assertEquals(50L, rows.get(0).count());
        assertEquals(50.0, rows.get(0).sharePercent(), 1e-9);
        assertEquals(0, rows.get(0).stars());
        assertEquals("biome2", rows.get(1).name());
        assertEquals(10.0, rows.get(1).sharePercent(), 1e-9);
        assertEquals(1, rows.get(1).stars());
        assertEquals("biome3", rows.get(2).name());
        assertEquals(7.0, rows.get(2).sharePercent(), 1e-9);
        assertEquals(1, rows.get(2).stars());

        // limit above the row count returns everything.
        List<BiomeRarity.RarityRow> all = BiomeRarity.topBiomes(counts, id -> "biome" + id, 100L, 10);
        assertEquals(4, all.size());
        // Shares in the 1..5% band rate 2 stars.
        assertEquals(2.0, all.get(3).sharePercent(), 1e-9);
        assertEquals(2, all.get(3).stars());
    }

    @Test
    @DisplayName("equal counts break ties by name for determinism")
    void tieBreakByName() {
        Map<Short, Long> counts = new LinkedHashMap<>();
        counts.put((short) 9, 5L);
        counts.put((short) 2, 5L);
        counts.put((short) 4, 1L);

        List<BiomeRarity.RarityRow> rows = BiomeRarity.topBiomes(counts,
                id -> "biome" + id, 11L, 10);

        assertEquals("biome2", rows.get(0).name());
        assertEquals("biome9", rows.get(1).name());
        assertEquals("biome4", rows.get(2).name());
    }

    @Test
    @DisplayName("zero present samples: all shares are 0.0 and everything is rare")
    void zeroPresentSamples() {
        Map<Short, Long> counts = Map.of((short) 1, 42L, (short) 2, 7L);
        List<BiomeRarity.RarityRow> rows = BiomeRarity.topBiomes(counts,
                id -> "biome" + id, 0L, 10);

        assertEquals(2, rows.size());
        for (BiomeRarity.RarityRow row : rows) {
            assertEquals(0.0, row.sharePercent(), 0.0);
            assertEquals(3, row.stars());
        }
        // Sorted by count despite equal shares.
        assertEquals(42L, rows.get(0).count());
    }

    @Test
    @DisplayName("null resolver results fall back to biome_<id>")
    void nameResolverFallback() {
        Map<Short, Long> counts = Map.of((short) 99, 10L, (short) 3, 5L);
        List<BiomeRarity.RarityRow> rows = BiomeRarity.topBiomes(counts,
                id -> id == 99 ? null : "named", 15L, 10);

        assertEquals("biome_99", rows.get(0).name());
        assertEquals("named", rows.get(1).name());
    }

    @Test
    @DisplayName("empty counts yield an empty list")
    void emptyCounts() {
        assertTrue(BiomeRarity.topBiomes(Map.of(), id -> "x", 100L, 5).isEmpty());
        assertTrue(BiomeRarity.topBiomes(Map.of((short) 1, 1L), id -> "x", 1L, 0).isEmpty());
    }
}
