package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests the holder-based single-pass evaluation path of
 * {@link SeedSearchService} against the legacy identifier-based path on the
 * same synthetic layouts, plus the single-pass sampling guarantee
 * (one biome lookup per sample point) and the fail-fast rule that a failing
 * biome criterion never triggers structure probing.
 *
 * <p>No real world generation is involved: holders are stand-alone registry
 * references and the samplers replay fixed per-position layouts.</p>
 */
class SeedSearchSinglePassTest {

    private static final Identifier PLAINS = Identifier.parse("minecraft:plains");
    private static final Identifier DESERT = Identifier.parse("minecraft:desert");
    private static final Identifier JUNGLE = Identifier.parse("minecraft:jungle");
    private static final Identifier FOREST = Identifier.parse("minecraft:forest");
    private static final Identifier VILLAGE = Identifier.parse("minecraft:village_plains");

    /** Sample grid: view 0..32 with step 16 -> 3x3 = 9 points around center (16,16). */
    private static final int GRID_POINTS = 9;

    private SeedSearchService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    // ==== synthetic layouts ====

    /**
     * Position -> biome identifier layouts. Variant 3 places the only plains
     * points exactly 16 blocks from the center (16,16): the strict
     * {@code minDistance > maxDistance} boundary.
     */
    private static Identifier layoutBiome(int variant, int x, int z) {
        return switch (variant) {
            case 0 -> x == 0 ? DESERT : PLAINS;
            case 1 -> PLAINS;
            case 2 -> (x == 0 && z == 0) ? JUNGLE : FOREST;
            case 3 -> (x == 16 && (z == 0 || z == 32)) ? PLAINS : DESERT;
            default -> DESERT;
        };
    }

    private static int variantFor(long seed) {
        return (int) Math.floorMod(seed, 5);
    }

    // ==== fakes ====

    /** Identifier-only sampler: takes the legacy per-criterion evaluation path. */
    private static final class LegacySampler implements SeedSearchService.BiomeSampler, SeedSearchService.StructureProbe {
        private final int variant;
        final AtomicInteger structureProbes = new AtomicInteger();

        LegacySampler(int variant) {
            this.variant = variant;
        }

        @Override
        public boolean sampleContains(int x, int y, int z, Identifier targetBiome) {
            return layoutBiome(variant, x, z).equals(targetBiome);
        }

        @Override
        public BlockPos nearestStructure(Set<Identifier> structures, BlockPos anchor, int maxDistanceBlocks) {
            structureProbes.incrementAndGet();
            return anchor.offset(16, 0, 16);
        }
    }

    /** Holder-capable sampler: one {@code biomeHolderAt} lookup per point. */
    private static final class HolderSampler implements SeedSearchService.BiomeSampler, SeedSearchService.StructureProbe {
        private final int variant;
        private final boolean exposePossibleBiomes;
        private final Map<Identifier, Holder<Biome>> holders = new HashMap<>();
        final AtomicInteger holderCalls = new AtomicInteger();
        final AtomicInteger structureProbes = new AtomicInteger();

        HolderSampler(int variant, boolean exposePossibleBiomes) {
            this.variant = variant;
            this.exposePossibleBiomes = exposePossibleBiomes;
            for (Identifier id : List.of(PLAINS, DESERT, JUNGLE, FOREST)) {
                holders.put(id, newHolder(id));
            }
        }

        @Override
        public boolean supportsHolderSampling() {
            return true;
        }

        @Override
        public Holder<Biome> biomeHolderAt(int x, int y, int z) {
            holderCalls.incrementAndGet();
            return holders.get(layoutBiome(variant, x, z));
        }

        @Override
        public Collection<Holder<Biome>> possibleBiomes() {
            // An empty collection forces the identifier fallback matchers,
            // exercising both matcher branches of the holder path.
            return exposePossibleBiomes ? List.copyOf(holders.values()) : List.of();
        }

        @Override
        public boolean sampleContains(int x, int y, int z, Identifier targetBiome) {
            return layoutBiome(variant, x, z).equals(targetBiome);
        }

        @Override
        public BlockPos nearestStructure(Set<Identifier> structures, BlockPos anchor, int maxDistanceBlocks) {
            structureProbes.incrementAndGet();
            return anchor.offset(16, 0, 16);
        }
    }

    /** Stand-alone registry-free reference holder (identity equals, like registry singletons). */
    private static Holder<Biome> newHolder(Identifier id) {
        return Holder.Reference.createStandAlone(
                new HolderOwner<Biome>() {},
                ResourceKey.create(Registries.BIOME, id));
    }

    // ==== helpers ====

    private static SeedSearchRequest request(List<SearchCriterion> criteria, int maxAttempts, int maxHits) {
        return new SeedSearchRequest(
                "minecraft:overworld", new BlockPos(16, 64, 16), 64,
                0, 32, 0, 32, 16, "test", maxAttempts,
                criteria, maxHits
        );
    }

    /** Pins the service's private candidate-seed source so two runs draw identical seeds. */
    private static void pinRandom(SeedSearchService svc, long seed) throws ReflectiveOperationException {
        Field random = SeedSearchService.class.getDeclaredField("random");
        random.setAccessible(true);
        random.set(svc, new SplittableRandom(seed));
    }

    private static void awaitResult(AtomicReference<SeedSearchResult> ref, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (ref.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNotNull(ref.get(), "search did not complete in time");
    }

    /** Normalizes any terminal result into its ranked-hit list for comparison. */
    private static List<SeedSearchResult.Ranked> normalized(SeedSearchResult result) {
        if (result instanceof SeedSearchResult.Miss) {
            return List.of();
        }
        if (result instanceof SeedSearchResult.Hit hit) {
            return List.of(new SeedSearchResult.Ranked(hit.seed(), hit.score(), hit.structurePos()));
        }
        if (result instanceof SeedSearchResult.Multiple multiple) {
            return multiple.hits();
        }
        throw new AssertionError("unexpected terminal result: " + result);
    }

    private static SeedSearchService.SeedContextFactory legacyFactory() {
        return seed -> new LegacySampler(variantFor(seed));
    }

    private static SeedSearchService.SeedContextFactory holderFactory(boolean exposePossibleBiomes) {
        return seed -> new HolderSampler(variantFor(seed), exposePossibleBiomes);
    }

    private static SeedSearchResult runSearch(SeedSearchService svc, SeedSearchRequest request,
                                              SeedSearchService.SeedContextFactory factory,
                                              long randomSeed) throws Exception {
        pinRandom(svc, randomSeed);
        AtomicReference<SeedSearchResult> resultRef = new AtomicReference<>();
        svc.startSearch(request, factory, seed -> {}, result -> resultRef.set(result), attempts -> {});
        awaitResult(resultRef, 10000);
        return resultRef.get();
    }

    // ==== 1) equivalence: legacy path vs holder path ====

    @Test
    @DisplayName("Holder path produces exactly the legacy hits/scores (any-of, AND, fallback matchers)")
    void holderPathMatchesLegacyPathExactly() throws Exception {
        List<List<SearchCriterion>> criterionSets = List.of(
                // any-of group + single biome AND
                List.of(new SearchCriterion.BiomeGroup(List.of(DESERT, JUNGLE), 0, 0),
                        new SearchCriterion.Biome(PLAINS, 0, 0)),
                // biome + group with area and distance thresholds (AND)
                List.of(new SearchCriterion.Biome(PLAINS, 10, 0),
                        new SearchCriterion.BiomeGroup(List.of(DESERT, JUNGLE), 5, 64)),
                // single biome
                List.of(new SearchCriterion.Biome(PLAINS, 0, 0))
        );

        for (int set = 0; set < criterionSets.size(); set++) {
            var request = request(criterionSets.get(set), 40, 5);
            SeedSearchResult legacy;
            try (var svc = new SeedSearchService(null, 1)) {
                legacy = runSearch(svc, request, legacyFactory(), 42L);
            }
            SeedSearchResult holder;
            try (var svc = new SeedSearchService(null, 1)) {
                holder = runSearch(svc, request, holderFactory(true), 42L);
            }
            SeedSearchResult holderFallback;
            try (var svc = new SeedSearchService(null, 1)) {
                holderFallback = runSearch(svc, request, holderFactory(false), 42L);
            }

            assertEquals(normalized(legacy), normalized(holder),
                    "criterion set " + set + ": holder path must match the legacy path");
            assertEquals(normalized(legacy), normalized(holderFallback),
                    "criterion set " + set + ": holder fallback matchers must match the legacy path");
        }
    }

    @Test
    @DisplayName("minDistance == maxDistance passes (strict >), maxDistance - 1 fails")
    void boundaryDistanceSemanticsMatch() throws Exception {
        // Fixed layout variant 3: the nearest plains points are exactly 16
        // blocks from the center, matching 2 of 9 points.
        var passRequest = request(List.of(new SearchCriterion.Biome(PLAINS, 0, 16)), 1, 1);
        var failRequest = request(List.of(new SearchCriterion.Biome(PLAINS, 0, 15)), 1, 1);

        try (var legacySvc = new SeedSearchService(null, 1);
             var holderSvc = new SeedSearchService(null, 1)) {
            SeedSearchResult legacyPass = runSearch(legacySvc, passRequest, seed -> new LegacySampler(3), 7L);
            SeedSearchResult holderPass = runSearch(holderSvc, passRequest, seed -> new HolderSampler(3, true), 7L);

            var legacyPassHits = normalized(legacyPass);
            var holderPassHits = normalized(holderPass);
            assertEquals(legacyPassHits, holderPassHits);
            assertEquals(1, holderPassHits.size(), "boundary case must hit");
            // score = 2 * 100 / 9 (area) + 50 * (1 - min(1, 16/16)) = area + 0
            double expected = 2 * 100.0 / GRID_POINTS + 50.0 * (1.0 - Math.min(1.0, 16.0 / 16.0));
            assertEquals(expected, holderPassHits.get(0).score(), 0.0);

            SeedSearchResult legacyFail = runSearch(legacySvc, failRequest, seed -> new LegacySampler(3), 7L);
            SeedSearchResult holderFail = runSearch(holderSvc, failRequest, seed -> new HolderSampler(3, true), 7L);
            assertEquals(normalized(legacyFail), normalized(holderFail));
            assertInstanceOf(SeedSearchResult.Miss.class, holderFail, "16 > 15 must fail the distance check");
        }
    }

    // ==== 2) single-pass proof ====

    @Test
    @DisplayName("Holder path calls biomeHolderAt exactly once per sample point, even for multiple criteria")
    void holderPathSamplesEachPointExactlyOnce() throws Exception {
        var request = request(List.of(
                new SearchCriterion.Biome(PLAINS, 0, 0),
                new SearchCriterion.BiomeGroup(List.of(DESERT, JUNGLE), 0, 0)
        ), 1, 1);

        List<HolderSampler> created = new CopyOnWriteArrayList<>();
        SeedSearchResult result;
        try (var svc = new SeedSearchService(null, 1)) {
            result = runSearch(svc, request, seed -> {
                HolderSampler sampler = new HolderSampler(variantFor(seed), true);
                created.add(sampler);
                return sampler;
            }, 99L);
        }

        assertNotNull(result);
        assertEquals(1, created.size(), "maxAttempts=1 must evaluate exactly one candidate");
        assertEquals(GRID_POINTS, created.get(0).holderCalls.get(),
                "one biome lookup per sample point for the whole criteria set");
    }

    // ==== 3) fail-fast: a failing biome criterion never probes structures ====

    @Test
    @DisplayName("Failing biome criterion skips structure probing (probe count 0)")
    void failingBiomeCriterionSkipsStructureProbe() throws Exception {
        // Variant 4: all desert -> plains area 0% < 50% -> always fails.
        var request = request(List.of(
                new SearchCriterion.Biome(PLAINS, 50, 0),
                new SearchCriterion.Structure(VILLAGE, 256)
        ), 1, 1);

        List<HolderSampler> created = new CopyOnWriteArrayList<>();
        SeedSearchResult result;
        try (var svc = new SeedSearchService(null, 1)) {
            result = runSearch(svc, request, seed -> {
                HolderSampler sampler = new HolderSampler(4, true);
                created.add(sampler);
                return sampler;
            }, 5L);
        }

        assertInstanceOf(SeedSearchResult.Miss.class, result);
        assertEquals(0, created.get(0).structureProbes.get(),
                "a failing biome criterion must prevent structure probing");
    }

    @Test
    @DisplayName("Positive control: passing biome criteria let the structure criterion run")
    void passingBiomeCriteriaRunStructureProbe() throws Exception {
        var request = request(List.of(
                new SearchCriterion.Biome(PLAINS, 0, 0),
                new SearchCriterion.Structure(VILLAGE, 256)
        ), 1, 1);

        List<HolderSampler> created = new CopyOnWriteArrayList<>();
        SeedSearchResult result;
        try (var svc = new SeedSearchService(null, 1)) {
            result = runSearch(svc, request, seed -> {
                HolderSampler sampler = new HolderSampler(1, true);
                created.add(sampler);
                return sampler;
            }, 5L);
        }

        assertInstanceOf(SeedSearchResult.Hit.class, result);
        assertEquals(1, created.get(0).structureProbes.get());
        assertEquals(GRID_POINTS, created.get(0).holderCalls.get(),
                "biome pass stays single-pass with structure criteria present");
    }
}
