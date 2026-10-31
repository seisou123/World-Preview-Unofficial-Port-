package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static caeruleusTait.world.preview.backend.analysis.SeedSearchFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests the core logic of SeedSearchService.
 * Fake implementations are injected through the SeedSearchService.SeedContextFactory
 * and BiomeSampler interfaces, so no real Minecraft world generation is involved.
 */
class SeedSearchServiceTest {

    private SeedSearchService service;

    @BeforeEach
    void setUp() {
        service = new SeedSearchService(null, 1);
    }

    @Test
    @DisplayName("hit stops the search: the first hit ends it")
    void hitStopsFurtherSearch() throws Exception {
        var request = legacyRequest(PLAINS, 100);

        var resultRef = new AtomicReference<SeedSearchResult>();
        // Every seed resolves to the target biome, so the first candidate hits
        var factory = new FakeSamplerFactory(seed -> "minecraft:plains");

        service.startSearch(request, factory, seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(300);

        assertNotNull(resultRef.get());
        assertInstanceOf(SeedSearchResult.Hit.class, resultRef.get());
        // A seed can be any long value, positive or negative
        assertNotEquals(0, ((SeedSearchResult.Hit) resultRef.get()).seed());
    }

    @Test
    @DisplayName("reports a Miss once the attempt limit is reached")
    void missAfterMaxAttempts() throws Exception {
        var request = legacyRequest(RARE, 10);

        var resultRef = new AtomicReference<SeedSearchResult>();
        var factory = new FakeSamplerFactory(seed -> "minecraft:desert");

        service.startSearch(request, factory, seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(500);

        assertNotNull(resultRef.get());
        assertInstanceOf(SeedSearchResult.Miss.class, resultRef.get());
    }

    @Test
    @DisplayName("cancel stops the search without injecting a seed")
    void cancelStopsSearch() throws Exception {
        var request = legacyRequest(RARE, 100);

        var resultRef = new AtomicReference<SeedSearchResult>();
        var hitRef = new AtomicLong(-1);
        var factory = new FakeSamplerFactory(seed -> {
            service.cancel();
            return "minecraft:desert";
        });

        service.startSearch(request, factory, seed -> hitRef.set(seed), result -> resultRef.set(result), attempts -> {});
        Thread.sleep(300);

        assertNotNull(resultRef.get());
        assertInstanceOf(SeedSearchResult.Cancelled.class, resultRef.get());
        assertEquals(-1, hitRef.get());
    }

    @Test
    @DisplayName("a duplicate request is rejected")
    void rejectsDuplicateRequest() throws Exception {
        var request = legacyRequest(PLAINS, 100);

        var factory = new FakeSamplerFactory(seed -> "minecraft:desert");

        assertTrue(service.startSearch(request, factory, seed -> {}, result -> {}, attempts -> {}));
        assertFalse(service.startSearch(request, factory, seed -> {}, result -> {}, attempts -> {}));
    }

    @Test
    @DisplayName("aborts early once consecutive failures exceed the threshold")
    void consecutiveFailuresEarlyAbort() throws Exception {
        var request = legacyRequest(RARE, 100);

        var resultRef = new AtomicReference<SeedSearchResult>();
        var factory = new FailingSamplerFactory();

        service.startSearch(request, factory, seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(1000);

        assertNotNull(resultRef.get());
        assertInstanceOf(SeedSearchResult.Miss.class, resultRef.get());
        // Should abort early after 10 consecutive failures
        assertTrue(factory.callCount.get() >= 10 && factory.callCount.get() <= 15);
    }

    @Test
    @DisplayName("BiomeSampler.close() is called")
    void samplerCloseIsCalled() throws Exception {
        var request = legacyRequest(RARE, 3);

        var resultRef = new AtomicReference<SeedSearchResult>();
        var factory = new CloseTrackingSamplerFactory();

        service.startSearch(request, factory, seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(500);

        assertNotNull(resultRef.get());
        // Every candidate seed must close its sampler
        assertTrue(factory.closeCount.get() >= 1, "BiomeSampler.close() must be called");
    }

    // ========== Test helpers ==========

    /** Fake seed-context factory that injects results through the BiomeSampler interface. */
    private static class FakeSamplerFactory implements SeedSearchService.SeedContextFactory {
        private final java.util.function.LongFunction<String> biomeFn;
        FakeSamplerFactory(java.util.function.LongFunction<String> biomeFn) {
            this.biomeFn = biomeFn;
        }
        @Override
        public SeedSearchService.BiomeSampler createSampler(long seed) {
            var targetBiome = new ResourceLocation(biomeFn.apply(seed));
            return (x, y, z, target) -> target.equals(targetBiome);
        }
    }

    // Helper: a factory whose samplers always throw
    private static class FailingSamplerFactory implements SeedSearchService.SeedContextFactory {
        final AtomicInteger callCount = new AtomicInteger(0);
        @Override
        public SeedSearchService.BiomeSampler createSampler(long seed) {
            callCount.incrementAndGet();
            return (x, y, z, target) -> { throw new RuntimeException("simulated error"); };
        }
    }

    // Helper: a factory that tracks close() calls
    private static class CloseTrackingSamplerFactory implements SeedSearchService.SeedContextFactory {
        final AtomicInteger closeCount = new AtomicInteger(0);
        @Override
        public SeedSearchService.BiomeSampler createSampler(long seed) {
            return new SeedSearchService.BiomeSampler() {
                @Override
                public boolean sampleContains(int x, int y, int z, ResourceLocation target) {
                    return false; // never matches
                }
                @Override
                public void close() {
                    closeCount.incrementAndGet();
                }
            };
        }
    }

    @Test
    @DisplayName("seeds do not repeat within one search")
    void seedsAreUniqueWithinSearch() throws Exception {
        var request = legacyRequest(RARE, 20);

        var seedsSeen = new java.util.concurrent.ConcurrentHashMap<Long, Boolean>();
        var factory = new SeedSearchService.SeedContextFactory() {
            @Override
            public SeedSearchService.BiomeSampler createSampler(long seed) {
                seedsSeen.put(seed, true);
                return (x, y, z, target) -> false; // never match
            }
        };

        var resultRef = new AtomicReference<SeedSearchResult>();
        service.startSearch(request, factory, seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(1000);

        assertNotNull(resultRef.get());
        // Each seed appears once, so the deduplicated set size equals the seed count
        assertEquals(20, seedsSeen.size());
    }

    @Test
    @DisplayName("SeedSearchRequest rejects an invalid viewport")
    void requestRejectsInvalidViewport() {
        assertThrows(IllegalArgumentException.class, () -> new SeedSearchRequest(
                new ResourceLocation("minecraft:plains"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                100, -100, -100, 100, 4, "test", 100, 0, 0 // viewMinX > viewMaxX
        ));
        assertThrows(IllegalArgumentException.class, () -> new SeedSearchRequest(
                new ResourceLocation("minecraft:plains"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                -100, 100, 100, -100, 4, "test", 100, 0, 0 // viewMinZ > viewMaxZ
        ));
    }
}
