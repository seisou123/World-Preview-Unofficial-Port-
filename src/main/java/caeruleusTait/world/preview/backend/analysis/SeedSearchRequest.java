package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Immutable seed search request snapshot.
 * Created by the main thread when clicking "Find this Biome". Records all parameters needed for the search.
 * Player dragging/scrolling/settings changes during search do not affect the current search scope.
 * <p>
 * Since v1.5 a request may carry multiple {@link SearchCriterion}s (all must pass)
 * and may collect up to {@code maxHits} ranked hits instead of stopping at the first one.
 * </p>
 */
public record SeedSearchRequest(
    /** Current dimension */
    @NotNull String dimension,
    /** Current center coordinates */
    @NotNull BlockPos center,
    /** Current Y layer (block coordinates) */
    int yLevel,
    /** Min X bound of visible screen area (block coords) */
    int viewMinX,
    /** Max X bound of visible screen area (block coords) */
    int viewMaxX,
    /** Min Z bound of visible screen area (block coords) */
    int viewMinZ,
    /** Max Z bound of visible screen area (block coords) */
    int viewMaxZ,
    /** Sampling step (block coords, consistent with PreviewDisplay quartStride) */
    int sampleStep,
    /** Worldgen config fingerprint for validating context on search result callback */
    @NotNull String contextFingerprint,
    /** Maximum number of attempts */
    int maxAttempts,
    /** Criteria every candidate seed must satisfy (logical AND) */
    @NotNull List<SearchCriterion> criteria,
    /** Number of ranked hits to collect before stopping (1 = stop at first hit) */
    int maxHits
) {
    /** Default max attempts */
    public static final int DEFAULT_MAX_ATTEMPTS = 100;

    /** Default number of ranked hits collected by the advanced search. */
    public static final int DEFAULT_MAX_HITS = 5;

    /** Hard cap on collected hits to bound memory and search time. */
    public static final int MAX_HITS_LIMIT = 32;

    public SeedSearchRequest {
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be > 0");
        if (sampleStep <= 0) throw new IllegalArgumentException("sampleStep must be > 0");
        if (viewMinX > viewMaxX) throw new IllegalArgumentException("viewMinX must be <= viewMaxX");
        if (viewMinZ > viewMaxZ) throw new IllegalArgumentException("viewMinZ must be <= viewMaxZ");
        Objects.requireNonNull(criteria, "criteria");
        if (criteria.isEmpty()) throw new IllegalArgumentException("criteria must not be empty");
        criteria = List.copyOf(criteria);
        if (maxHits < 1 || maxHits > MAX_HITS_LIMIT) {
            throw new IllegalArgumentException("maxHits must be 1-" + MAX_HITS_LIMIT);
        }
    }

    /**
     * Legacy single-biome constructor. Wraps the biome parameters into a
     * {@link SearchCriterion.Biome} and stops at the first hit.
     */
    public SeedSearchRequest(
            @NotNull Identifier targetBiome,
            @NotNull String dimension,
            @NotNull BlockPos center,
            int yLevel,
            int viewMinX, int viewMaxX, int viewMinZ, int viewMaxZ,
            int sampleStep,
            @NotNull String contextFingerprint,
            int maxAttempts,
            int minAreaPercent,
            int maxDistance
    ) {
        this(dimension, center, yLevel, viewMinX, viewMaxX, viewMinZ, viewMaxZ,
                sampleStep, contextFingerprint, maxAttempts,
                List.of(new SearchCriterion.Biome(targetBiome, minAreaPercent, maxDistance)),
                1);
    }

    /**
     * The biome criterion of this request when one is present, otherwise null.
     * Kept for the legacy "find this biome" flow.
     */
    @Nullable
    public Identifier targetBiome() {
        for (SearchCriterion criterion : criteria) {
            if (criterion instanceof SearchCriterion.Biome biome) {
                return biome.biome();
            }
        }
        return null;
    }

    /** All structure criteria of this request. */
    public List<SearchCriterion.Structure> structureCriteria() {
        return criteria.stream()
                .filter(SearchCriterion.Structure.class::isInstance)
                .map(SearchCriterion.Structure.class::cast)
                .toList();
    }

    /** All biome criteria of this request. */
    public List<SearchCriterion.Biome> biomeCriteria() {
        return criteria.stream()
                .filter(SearchCriterion.Biome.class::isInstance)
                .map(SearchCriterion.Biome.class::cast)
                .toList();
    }

    /** All biome group criteria of this request. */
    public List<SearchCriterion.BiomeGroup> biomeGroupCriteria() {
        return criteria.stream()
                .filter(SearchCriterion.BiomeGroup.class::isInstance)
                .map(SearchCriterion.BiomeGroup.class::cast)
                .toList();
    }
}
