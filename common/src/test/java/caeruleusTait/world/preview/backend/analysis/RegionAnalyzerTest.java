package caeruleusTait.world.preview.backend.analysis;

import caeruleusTait.world.preview.backend.export.TerrainCategory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

class RegionAnalyzerTest {

    @Test
    void shannonAndTerrainShares() {
        Map<Short, Long> counts = new HashMap<>();
        counts.put((short) 1, 75L);
        counts.put((short) 2, 25L);
        // holder resolver: id1 -> plains, id2 -> ocean; null -> UNKNOWN
        RegionInsights ins = RegionAnalyzer.fromBiomeCounts(counts, id -> null); // a null resolver must not throw
        double expectedH = -(0.75 * Math.log(0.75) + 0.25 * Math.log(0.25));
        // effectiveBiomeCount = exp(H), which is only 2.0 for an even split; at
        // 75/25 it is exp(H) ~= 1.7548.
        org.junit.jupiter.api.Assertions.assertEquals(Math.exp(expectedH), ins.effectiveBiomeCount(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(expectedH, ins.shannonDiversity(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(100L, ins.terrainCounts().get(TerrainCategory.UNKNOWN));
        org.junit.jupiter.api.Assertions.assertEquals(100L, ins.classifiedSamples());
    }

    @Test
    void zeroCountEntriesDoNotPoisonShannon() {
        Map<Short, Long> counts = new HashMap<>();
        counts.put((short) 1, 75L);
        counts.put((short) 2, 25L);
        counts.put((short) 3, 0L); // 0 * ln 0 = NaN, so it must be skipped
        RegionInsights ins = RegionAnalyzer.fromBiomeCounts(counts, id -> null);
        double expectedH = -(0.75 * Math.log(0.75) + 0.25 * Math.log(0.25));
        org.junit.jupiter.api.Assertions.assertFalse(Double.isNaN(ins.shannonDiversity()));
        org.junit.jupiter.api.Assertions.assertFalse(Double.isNaN(ins.effectiveBiomeCount()));
        org.junit.jupiter.api.Assertions.assertEquals(expectedH, ins.shannonDiversity(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(Math.exp(expectedH), ins.effectiveBiomeCount(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(100L, ins.classifiedSamples());
        org.junit.jupiter.api.Assertions.assertEquals(100L, ins.terrainCounts().get(TerrainCategory.UNKNOWN));
    }
}
