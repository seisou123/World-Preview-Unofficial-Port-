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
        // holder resolver: id1 → plains(PLAINS), id2 → ocean(OCEAN)；null → UNKNOWN
        RegionInsights ins = RegionAnalyzer.fromBiomeCounts(counts, id -> null); // resolver 返回 null 也必须不抛
        double expectedH = -(0.75 * Math.log(0.75) + 0.25 * Math.log(0.25));
        // effectiveBiomeCount = exp(H)（brief 实现定义的语义）；2.0 只在均分时成立，75/25 时为 exp(H)≈1.7548
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
        counts.put((short) 3, 0L); // 0·ln 0 = NaN；必须被忽略
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
