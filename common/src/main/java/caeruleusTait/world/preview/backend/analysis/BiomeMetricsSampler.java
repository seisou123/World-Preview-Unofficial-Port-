package caeruleusTait.world.preview.backend.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts biome sampling metrics from a region.
 *
 * <p>Previously embedded in {@code PreviewContainer.sampleBiomeMetrics()},
 * this class provides testable biome metric computation without GUI dependencies.
 */
public final class BiomeMetricsSampler {

    /**
     * Computes biome metrics from raw biome sample data.
     *
     * <p>Each sample is a (biome, x, z) tuple. The method computes:
     * <ul>
     *   <li>Per-biome counts and ratios</li>
     *   <li>Nearest distance from region center for each target biome</li>
     * </ul>
     *
     * @param samples       the list of biome samples with explicit coordinates
     * @param targets       the set of target biome identifiers to track distances for
     * @param regionCenterX the region center X coordinate
     * @param regionCenterZ the region center Z coordinate
     * @return the computed biome metrics
     */
    public static BiomeSampleResult compute(
            List<BiomeSample> samples,
            Set<String> targets,
            long regionCenterX,
            long regionCenterZ
    ) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Double> nearestDistance = new HashMap<>();
        long total = 0;

        for (BiomeSample sample : samples) {
            String biome = sample.biome();
            counts.merge(biome, 1, Integer::sum);

            if (targets.contains(biome)) {
                double dx = (double) sample.x() - regionCenterX;
                double dz = (double) sample.z() - regionCenterZ;
                double distance = Math.sqrt(dx * dx + dz * dz);
                nearestDistance.merge(biome, distance, Math::min);
            }
            total++;
        }

        final long sampleCount = total;
        Map<String, Double> ratios = new HashMap<>();
        counts.forEach((biome, count) -> ratios.put(biome, (double) count / sampleCount));

        return new BiomeSampleResult(counts, ratios, nearestDistance, sampleCount);
    }

    /** A single biome sample at a specific coordinate. */
    public record BiomeSample(String biome, int x, int z) {}

    /** Immutable result of biome sampling. */
    public record BiomeSampleResult(
            Map<String, Integer> counts,
            Map<String, Double> ratios,
            Map<String, Double> nearestDistances,
            long totalSamples
    ) {}
}
