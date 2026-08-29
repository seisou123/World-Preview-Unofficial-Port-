package caeruleusTait.world.preview.backend.analysis;

import java.util.List;

/**
 * Immutable search result representing the final state of a seed search.
 */
public sealed interface SeedSearchResult {

    /**
     * Hit: found a seed satisfying all criteria.
     */
    record Hit(long seed, double score) implements SeedSearchResult {
        /** Legacy constructor for single-biome searches without scoring. */
        public Hit(long seed) {
            this(seed, 0.0);
        }
    }

    /**
     * Miss: exhausted all candidate seeds without finding a matching seed.
     */
    record Miss() implements SeedSearchResult {}

    /**
     * Cancelled: search was manually cancelled by the player.
     */
    record Cancelled() implements SeedSearchResult {}

    /**
     * Multiple: the search collected {@code hits} ranked hits (best first).
     * Only returned by requests with {@code maxHits > 1}.
     */
    record Multiple(List<Ranked> hits) implements SeedSearchResult {
        public Multiple {
            hits = List.copyOf(hits);
        }

        public boolean isEmpty() {
            return hits.isEmpty();
        }
    }

    /** A seed together with the score that ranked it. */
    record Ranked(long seed, double score) {
        public SeedSearchResult.Hit toHit() {
            return new SeedSearchResult.Hit(seed, score);
        }
    }
}
