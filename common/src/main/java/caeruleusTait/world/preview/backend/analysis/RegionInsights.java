package caeruleusTait.world.preview.backend.analysis;

import caeruleusTait.world.preview.backend.export.TerrainCategory;

import java.util.LinkedHashMap;

/**
 * Pure analytics snapshot for one region's biome distribution.
 *
 * @param shannonDiversity    Shannon entropy H = -Σ p·ln(p) over biome counts (0.0 when no samples)
 * @param effectiveBiomeCount exp(H) -- the number of equally-common biomes that would match the
 *                            observed diversity (0.0 when no samples)
 * @param terrainCounts       per-{@link TerrainCategory} sample totals, every enum constant present
 *                            in declaration order, defensively copied
 * @param classifiedSamples   total samples classified (sum of the input counts)
 */
public record RegionInsights(double shannonDiversity, double effectiveBiomeCount,
                             LinkedHashMap<TerrainCategory, Long> terrainCounts, long classifiedSamples) {
    public RegionInsights {
        terrainCounts = terrainCounts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(terrainCounts);
    }
}
