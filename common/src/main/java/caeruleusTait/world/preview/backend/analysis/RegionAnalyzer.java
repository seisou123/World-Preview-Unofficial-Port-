package caeruleusTait.world.preview.backend.analysis;

import caeruleusTait.world.preview.backend.export.TerrainCategory;
import caeruleusTait.world.preview.backend.export.TerrainClassifier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure analytics over biome id → sample-count maps; no world or screen state involved.
 */
public final class RegionAnalyzer {
    private RegionAnalyzer() {}

    /** 纯函数：unique biome 数量级（几百），渲染线程可安全调用。零/负计数条目被忽略（0·ln 0 会产生 NaN）。 */
    public static RegionInsights fromBiomeCounts(Map<Short, Long> counts,
                                                 java.util.function.IntFunction<Holder<Biome>> holderResolver) {
        Objects.requireNonNull(counts, "counts");
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        double shannon = 0.0;
        LinkedHashMap<TerrainCategory, Long> terrain = new LinkedHashMap<>();
        for (TerrainCategory c : TerrainCategory.values()) terrain.put(c, 0L);
        for (Map.Entry<Short, Long> e : counts.entrySet()) {
            Long count = e.getValue();
            if (count == null || count <= 0) {
                continue; // contributes nothing to shannon or terrain counts; 0 * ln 0 would be NaN
            }
            if (total > 0) {
                double p = count / (double) total;
                shannon -= p * Math.log(p);
            }
            Holder<Biome> holder = holderResolver.apply(e.getKey());
            TerrainCategory cat = holder != null ? TerrainClassifier.classify(holder) : TerrainCategory.UNKNOWN;
            terrain.merge(cat, count, Long::sum);
        }
        return new RegionInsights(shannon, total > 0 ? Math.exp(shannon) : 0.0, terrain, total);
    }
}
