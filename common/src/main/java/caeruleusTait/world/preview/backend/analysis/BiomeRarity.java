package caeruleusTait.world.preview.backend.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Pure helper for biome rarity presentation: star ratings for share percentages
 * and a ranked "top biomes" table built from {@link RegionMetrics#biomeCounts()}.
 *
 * <p>Star thresholds on the share percentage of a biome:</p>
 * <ul>
 *   <li>{@code >= 15%} → 0 stars (common)</li>
 *   <li>{@code >= 5%} → 1 star</li>
 *   <li>{@code >= 1%} → 2 stars</li>
 *   <li>{@code < 1%} → 3 stars (rare)</li>
 * </ul>
 */
public final class BiomeRarity {

    private BiomeRarity() {}

    /**
     * Star rating for a biome's share percentage (0 = common, 3 = rare).
     *
     * @param sharePercent share of the biome in percent (may be negative/NaN → 3 stars)
     * @return 0..3 stars
     */
    public static int stars(double sharePercent) {
        if (sharePercent >= 15.0) {
            return 0;
        }
        if (sharePercent >= 5.0) {
            return 1;
        }
        if (sharePercent >= 1.0) {
            return 2;
        }
        return 3;
    }

    /** One row of the "top biomes" table. */
    public record RarityRow(String name, long count, double sharePercent, int stars) {
        public RarityRow {
            name = name == null ? "?" : name;
        }
    }

    /**
     * Build the top {@code limit} biome rows sorted by count (descending; ties
     * broken by name for determinism). The share is
     * {@code count * 100.0 / presentSamples}; when {@code presentSamples == 0}
     * all shares are 0.0. Unknown biome ids (null/blank resolver results) fall
     * back to {@code "biome_" + id}.
     *
     * @param counts          biome id → sample count
     * @param nameResolver    maps a biome id to a display name (may return null)
     * @param presentSamples  total number of present samples the counts were built from
     * @param limit           maximum number of rows to return (non-positive → empty)
     */
    public static List<RarityRow> topBiomes(Map<Short, Long> counts, IntFunction<String> nameResolver,
                                            long presentSamples, int limit) {
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(nameResolver, "nameResolver");
        if (counts.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<RarityRow> rows = new ArrayList<>(counts.size());
        for (Map.Entry<Short, Long> entry : counts.entrySet()) {
            long count = entry.getValue();
            double share = presentSamples <= 0 ? 0.0 : count * 100.0 / (double) presentSamples;
            String resolved = nameResolver.apply(entry.getKey());
            if (resolved == null || resolved.isBlank()) {
                resolved = "biome_" + entry.getKey();
            }
            rows.add(new RarityRow(resolved, count, share, stars(share)));
        }
        rows.sort(Comparator.comparingLong(RarityRow::count).reversed().thenComparing(RarityRow::name));
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return List.copyOf(rows);
    }
}
