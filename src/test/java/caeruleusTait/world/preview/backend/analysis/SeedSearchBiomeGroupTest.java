package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@link SearchCriterion.BiomeGroup} record (validation + request
 * accessors) and the any-of group evaluation path of {@link SeedSearchService}
 * using fake samplers (no real world generation).
 */
class SeedSearchBiomeGroupTest {

    private static final Identifier JUNGLE = Identifier.parse("minecraft:jungle");
    private static final Identifier DESERT = Identifier.parse("minecraft:desert");
    private static final Identifier PLAINS = Identifier.parse("minecraft:plains");
    private static final Identifier VILLAGE = Identifier.parse("minecraft:village_plains");

    private SeedSearchService service;

    @BeforeEach
    void setUp() {
        service = new SeedSearchService(null, 1);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    /**
     * Sampler with seed-dependent, position-dependent semantics:
     * even seeds match {@code jungle}, odd seeds match {@code desert} — but only
     * on the thin sliver of sample points at {@code x == 0}. {@code plains}
     * never matches for any seed.
     */
    private static class GroupFakeSampler implements SeedSearchService.BiomeSampler {
        private final long seed;

        GroupFakeSampler(long seed) {
            this.seed = seed;
        }

        private Identifier matchBiome() {
            return (seed % 2 == 0) ? JUNGLE : DESERT;
        }

        @Override
        public boolean sampleContains(int x, int y, int z, Identifier targetBiome) {
            return x == 0 && matchBiome().equals(targetBiome);
        }

        @Override
        public boolean sampleContainsAny(int x, int y, int z, Set<Identifier> biomes) {
            for (Identifier id : biomes) {
                if (sampleContains(x, y, z, id)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static SeedSearchService.SeedContextFactory factory() {
        return GroupFakeSampler::new;
    }

    private static SeedSearchRequest request(int maxHits, SearchCriterion... criteria) {
        return new SeedSearchRequest(
                "minecraft:overworld", new BlockPos(16, 64, 16), 64,
                0, 32, 0, 32, 16, "test", 60,
                java.util.List.of(criteria), maxHits
        );
    }

    @Test
    @DisplayName("BiomeGroup validation: empty list, more than 8 biomes, bad percent, bad distance, null element")
    void biomeGroupValidation() {
        assertThrows(IllegalArgumentException.class, () -> new SearchCriterion.BiomeGroup(List.of(), 0, 0));
        var nine = List.of(JUNGLE, DESERT, PLAINS, JUNGLE, DESERT, PLAINS, JUNGLE, DESERT, PLAINS);
        assertThrows(IllegalArgumentException.class, () -> new SearchCriterion.BiomeGroup(nine, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCriterion.BiomeGroup(List.of(JUNGLE), -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCriterion.BiomeGroup(List.of(JUNGLE), 101, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCriterion.BiomeGroup(List.of(JUNGLE), 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCriterion.BiomeGroup(java.util.Arrays.asList(JUNGLE, null), 0, 0));
        var ok = new SearchCriterion.BiomeGroup(List.of(JUNGLE, DESERT), 50, 128);
        assertEquals(List.of(JUNGLE, DESERT), ok.biomes());
        assertEquals(50, ok.minAreaPercent());
        assertEquals(128, ok.maxDistance());
        // The compact constructor must copy the list: later mutation of the
        // source list must not leak into the criterion.
        var mutable = new java.util.ArrayList<>(List.of(JUNGLE));
        var copied = new SearchCriterion.BiomeGroup(mutable, 0, 0);
        mutable.clear();
        assertEquals(List.of(JUNGLE), copied.biomes());
    }

    @Test
    @DisplayName("Request accessor biomeGroupCriteria() mirrors biomeCriteria()")
    void requestAccessor() {
        var request = new SeedSearchRequest(
                "minecraft:overworld", new BlockPos(0, 64, 0), 64,
                -100, 100, -100, 100, 4, "fp", 100,
                List.of(
                        new SearchCriterion.BiomeGroup(List.of(JUNGLE, DESERT), 10, 256),
                        new SearchCriterion.Structure(VILLAGE, 256)
                ),
                5
        );
        assertEquals(1, request.biomeGroupCriteria().size());
        assertEquals(List.of(JUNGLE, DESERT), request.biomeGroupCriteria().get(0).biomes());
        assertEquals(256, request.biomeGroupCriteria().get(0).maxDistance());
        assertEquals(1, request.structureCriteria().size());
        // Legacy single-biome accessor must not pick up group criteria
        assertNull(request.targetBiome());
        assertTrue(request.biomeCriteria().isEmpty());
    }

    @Test
    @DisplayName("ANY-of group (jungle+desert) hits every seed regardless of parity")
    void groupHitsEverySeed() throws Exception {
        var request = request(3, new SearchCriterion.BiomeGroup(List.of(JUNGLE, DESERT), 0, 0));

        AtomicReference<SeedSearchResult> resultRef = new AtomicReference<>();
        service.startSearch(request, factory(), seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(400);

        assertInstanceOf(SeedSearchResult.Multiple.class, resultRef.get());
        var multiple = (SeedSearchResult.Multiple) resultRef.get();
        assertEquals(3, multiple.hits().size());
    }

    @Test
    @DisplayName("Group of a never-matching biome misses all seeds")
    void groupWithNoMatchesMisses() throws Exception {
        var request = request(3, new SearchCriterion.BiomeGroup(List.of(PLAINS), 0, 0));

        AtomicReference<SeedSearchResult> resultRef = new AtomicReference<>();
        service.startSearch(request, factory(), seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(400);

        assertInstanceOf(SeedSearchResult.Miss.class, resultRef.get());
    }

    @Test
    @DisplayName("Group with a high minAreaPercent misses: the sliver cannot reach full coverage")
    void groupWithHighMinAreaMisses() throws Exception {
        // The fake sampler matches only points at x == 0 (1 of 9 points, ~11%),
        // so minAreaPercent 100 fails for every seed.
        var request = request(3, new SearchCriterion.BiomeGroup(List.of(JUNGLE, DESERT), 100, 0));

        AtomicReference<SeedSearchResult> resultRef = new AtomicReference<>();
        service.startSearch(request, factory(), seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(400);

        assertInstanceOf(SeedSearchResult.Miss.class, resultRef.get());
    }

    @Test
    @DisplayName("AND logic: group plus structure criterion fails when the sampler has no StructureProbe")
    void groupAndStructureWithoutProbeMisses() throws Exception {
        var request = request(2,
                new SearchCriterion.BiomeGroup(List.of(JUNGLE, DESERT), 0, 0),
                new SearchCriterion.Structure(VILLAGE, 256));

        AtomicReference<SeedSearchResult> resultRef = new AtomicReference<>();
        service.startSearch(request, factory(), seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(400);

        assertInstanceOf(SeedSearchResult.Miss.class, resultRef.get());
    }

    @Test
    @DisplayName("Single-hit request with a group stops at the first hit with a positive score")
    void singleHitStopsAtFirstWithScore() throws Exception {
        var request = request(1, new SearchCriterion.BiomeGroup(List.of(JUNGLE, DESERT), 0, 0));

        AtomicReference<SeedSearchResult> resultRef = new AtomicReference<>();
        service.startSearch(request, factory(), seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(300);

        assertInstanceOf(SeedSearchResult.Hit.class, resultRef.get());
        var hit = (SeedSearchResult.Hit) resultRef.get();
        assertTrue(hit.score() > 0, "group hit must score above zero");
    }
}
