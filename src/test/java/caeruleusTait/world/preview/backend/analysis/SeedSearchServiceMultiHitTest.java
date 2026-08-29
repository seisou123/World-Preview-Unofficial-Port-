package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the multi-criteria and multi-hit paths of {@link SeedSearchService}
 * using fake samplers and structure probes (no real world generation).
 */
class SeedSearchServiceMultiHitTest {

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

    /** Sampler that matches the target biome on a seed-dependent share of points. */
    private static class FakeSampler implements SeedSearchService.BiomeSampler {
        private final long seed;
        private final boolean evenSeedsRich;

        FakeSampler(long seed, boolean evenSeedsRich) {
            this.seed = seed;
            this.evenSeedsRich = evenSeedsRich;
        }

        private boolean rich() {
            return (seed % 2 == 0) == evenSeedsRich;
        }

        @Override
        public boolean sampleContains(int x, int y, int z, Identifier targetBiome) {
            // Deterministic share: rich seeds match 100% of points, poor seeds
            // match only points at x == 0 (a thin sliver).
            return rich() || x == 0;
        }
    }

    private static SeedSearchService.SeedContextFactory factory(boolean evenSeedsRich) {
        return seed -> new FakeSampler(seed, evenSeedsRich);
    }

    private static SeedSearchRequest request(int maxHits, SearchCriterion... criteria) {
        return new SeedSearchRequest(
                "minecraft:overworld", new BlockPos(0, 64, 0), 64,
                0, 32, 0, 32, 16, "test", 60,
                java.util.List.of(criteria), maxHits
        );
    }

    @Test
    @DisplayName("Multi-hit search collects ranked hits sorted by score")
    void collectsRankedHits() throws Exception {
        // Even seeds: 100% coverage (high score). Odd seeds: sliver coverage
        // (low score but still >= minAreaPercent 0), so both hit.
        var request = request(3,
                new SearchCriterion.Biome(PLAINS, 0, 0));

        AtomicReference<SeedSearchResult> resultRef = new AtomicReference<>();
        service.startSearch(request, factory(true), seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(400);

        assertInstanceOf(SeedSearchResult.Multiple.class, resultRef.get());
        var multiple = (SeedSearchResult.Multiple) resultRef.get();
        assertEquals(3, multiple.hits().size());
        for (int i = 1; i < multiple.hits().size(); i++) {
            assertTrue(multiple.hits().get(i - 1).score() >= multiple.hits().get(i).score(),
                    "hits must be sorted by score descending");
        }
    }

    @Test
    @DisplayName("AND logic: a failing criterion rejects the seed")
    void andLogicRejectsSeed() throws Exception {
        // minAreaPercent 100 requires full coverage; odd seeds only cover a
        // sliver, so with enough attempts there will be misses — but even
        // seeds still pass. Instead use a structure criterion the sampler
        // cannot satisfy (no probe support) to force every seed to fail.
        var request = request(2,
                new SearchCriterion.Biome(PLAINS, 0, 0),
                new SearchCriterion.Structure(VILLAGE, 256));

        AtomicReference<SeedSearchResult> resultRef = new AtomicReference<>();
        service.startSearch(request, factory(true), seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(400);

        assertInstanceOf(SeedSearchResult.Miss.class, resultRef.get());
    }

    @Test
    @DisplayName("Structure criterion passes when the probe finds a structure")
    void structureCriterionHit() throws Exception {
        var request = request(1,
                new SearchCriterion.Structure(VILLAGE, 256));

        final class ProbeSampler extends FakeSampler implements SeedSearchService.StructureProbe {
            ProbeSampler(long seed) {
                super(seed, true);
            }

            @Override
            public BlockPos nearestStructure(Set<Identifier> structures, BlockPos anchor, int maxDistanceBlocks) {
                assertTrue(structures.contains(VILLAGE));
                return anchor.offset(16, 0, 16);
            }
        }

        SeedSearchService.SeedContextFactory factory2 = ProbeSampler::new;

        AtomicReference<SeedSearchResult> resultRef = new AtomicReference<>();
        service.startSearch(request, factory2, seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(400);

        assertInstanceOf(SeedSearchResult.Hit.class, resultRef.get());
        var hit = (SeedSearchResult.Hit) resultRef.get();
        assertTrue(hit.score() >= 50.0, "structure-only hit scores at least the base 50 points");
    }

    @Test
    @DisplayName("Single-hit request still stops at the first hit (legacy behaviour)")
    void singleHitStopsAtFirst() throws Exception {
        var request = request(1, new SearchCriterion.Biome(PLAINS, 0, 0));

        AtomicReference<SeedSearchResult> resultRef = new AtomicReference<>();
        service.startSearch(request, factory(true), seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(300);

        assertInstanceOf(SeedSearchResult.Hit.class, resultRef.get());
        assertTrue(((SeedSearchResult.Hit) resultRef.get()).score() > 0);
    }
}
