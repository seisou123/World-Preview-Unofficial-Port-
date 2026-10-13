package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Immutable search result representing the final state of a seed search.
 *
 * <p>Hits carry result lineage: the originating {@link SeedSearchRequest}
 * (original criteria, dimension, viewport, sampling step and context
 * fingerprint) and, for structure criteria, the located structure position.
 * Consumers can chain a hit into comparison / waypoints / analysis without
 * depending on transient UI fields, and must reject hits whose request
 * fingerprint no longer matches the live worldgen context.</p>
 */
public sealed interface SeedSearchResult {

    /**
     * Hit: found a seed satisfying all criteria.
     */
    record Hit(long seed, double score, @Nullable SeedSearchRequest request,
               @Nullable BlockPos structurePos) implements SeedSearchResult {
        /** Legacy constructor for single-biome searches without scoring. */
        public Hit(long seed) {
            this(seed, 0.0);
        }

        /** Legacy constructor without lineage. */
        public Hit(long seed, double score) {
            this(seed, score, null, null);
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
    record Multiple(List<Ranked> hits, @Nullable SeedSearchRequest request) implements SeedSearchResult {
        public Multiple {
            hits = List.copyOf(hits);
        }

        /** Legacy constructor without lineage. */
        public Multiple(List<Ranked> hits) {
            this(hits, null);
        }

        public boolean isEmpty() {
            return hits.isEmpty();
        }
    }

    /** A seed together with the score that ranked it and, when known, the structure position. */
    record Ranked(long seed, double score, @Nullable BlockPos structurePos) {
        /** Legacy constructor without lineage. */
        public Ranked(long seed, double score) {
            this(seed, score, null);
        }

        public SeedSearchResult.Hit toHit() {
            return new SeedSearchResult.Hit(seed, score);
        }
    }
}
