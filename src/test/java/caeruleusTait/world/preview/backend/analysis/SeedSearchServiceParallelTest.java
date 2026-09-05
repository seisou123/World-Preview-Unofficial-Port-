package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link SeedSearchService} with several candidate-evaluation
 * workers (unlike {@link SeedSearchServiceTest}, which pins a single thread).
 * Verifies that parallel evaluation preserves the serial semantics: exact
 * attempt budget, unique seeds, first-hit stop and no double reporting.
 */
class SeedSearchServiceParallelTest {

    private static final Identifier PLAINS = Identifier.parse("minecraft:plains");
    private static final Identifier RARE = Identifier.parse("minecraft:rare_biome");

    private SeedSearchService service;

    @BeforeEach
    void setUp() {
        service = new SeedSearchService(null, 4);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private static SeedSearchRequest request(Identifier biome, int maxAttempts, int maxHits) {
        return new SeedSearchRequest(
                "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                0, 16, 0, 16, 16, "test", maxAttempts,
                java.util.List.of(new SearchCriterion.Biome(biome, 0, 0)), maxHits
        );
    }

    @Test
    @DisplayName("Parallel single-hit: exactly one hit is reported and workers stop")
    void parallelSingleHitReportsExactlyOnce() throws Exception {
        var request = request(PLAINS, 100, 1);

        var resultRef = new AtomicReference<SeedSearchResult>();
        var hitCount = new AtomicInteger(0);
        var completeCount = new AtomicInteger(0);

        service.startSearch(request,
                seed -> (x, y, z, target) -> target.equals(PLAINS),
                seed -> hitCount.incrementAndGet(),
                result -> {
                    completeCount.incrementAndGet();
                    resultRef.set(result);
                },
                attempts -> {});
        Thread.sleep(600);

        assertInstanceOf(SeedSearchResult.Hit.class, resultRef.get());
        assertEquals(1, hitCount.get(), "onHit must fire exactly once");
        assertEquals(1, completeCount.get(), "onComplete must fire exactly once");
    }

    @Test
    @DisplayName("Parallel multi-hit: exact attempt budget, unique seeds, ranked result")
    void parallelMultiHitUsesExactBudgetAndUniqueSeeds() throws Exception {
        var request = request(RARE, 24, 5);

        var resultRef = new AtomicReference<SeedSearchResult>();
        var seenSeeds = ConcurrentHashMap.<Long>newKeySet();

        service.startSearch(request,
                seed -> {
                    seenSeeds.add(seed);
                    return (x, y, z, target) -> false; // never matches
                },
                seed -> {},
                result -> resultRef.set(result),
                attempts -> {});
        Thread.sleep(800);

        assertInstanceOf(SeedSearchResult.Miss.class, resultRef.get());
        assertEquals(24, seenSeeds.size(), "exact attempt budget with unique seeds under concurrency");
    }

    @Test
    @DisplayName("Parallel cancel reports Cancelled exactly once")
    void parallelCancelReportsCancelled() throws Exception {
        var request = request(RARE, 100, 3);

        var resultRef = new AtomicReference<SeedSearchResult>();
        var completeCount = new AtomicInteger(0);

        service.startSearch(request,
                seed -> {
                    service.cancel();
                    return (x, y, z, target) -> false;
                },
                seed -> {},
                result -> {
                    completeCount.incrementAndGet();
                    resultRef.set(result);
                },
                attempts -> {});
        Thread.sleep(600);

        assertInstanceOf(SeedSearchResult.Cancelled.class, resultRef.get());
        assertEquals(1, completeCount.get(), "onComplete must fire exactly once");
    }

    @Test
    @DisplayName("Parallel structure probe hit stops the search")
    void parallelStructureHit() throws Exception {
        var request = new SeedSearchRequest(
                "minecraft:overworld", new BlockPos(0, 64, 0), 64,
                0, 32, 0, 32, 16, "test", 50,
                java.util.List.of(new SearchCriterion.Structure(Identifier.parse("minecraft:village_plains"), 256)),
                1
        );

        final class ProbeSampler implements SeedSearchService.BiomeSampler, SeedSearchService.StructureProbe {
            @Override
            public boolean sampleContains(int x, int y, int z, Identifier targetBiome) {
                return false;
            }

            @Override
            public BlockPos nearestStructure(Set<Identifier> structures, BlockPos anchor, int maxDistanceBlocks) {
                return anchor.offset(16, 0, 16);
            }
        }

        var resultRef = new AtomicReference<SeedSearchResult>();
        service.startSearch(request, seed -> new ProbeSampler(), seed -> {}, result -> resultRef.set(result), attempts -> {});
        Thread.sleep(600);

        assertInstanceOf(SeedSearchResult.Hit.class, resultRef.get());
        assertTrue(((SeedSearchResult.Hit) resultRef.get()).score() >= 50.0);
    }

    // === Regression tests for the concurrency hardening fixes ===

    /** Waits for a result with a deadline instead of a fixed sleep. */
    private static void awaitResult(AtomicReference<SeedSearchResult> ref, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (ref.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("Single-hit survives a concurrent consecutive-failure abort")
    void hitSurvivesConcurrentAbort() throws Exception {
        // The first evaluation is slow and returns a hit; all others throw
        // immediately. The 10 fast exceptions trigger the consecutive-failure
        // abort while the slow hit is still in flight - the abort must not
        // discard the already-computed hit.
        var calls = new AtomicInteger(0);
        var request = request(PLAINS, 30, 1);

        var resultRef = new AtomicReference<SeedSearchResult>();
        var hitCount = new AtomicInteger(0);

        service.startSearch(request,
                seed -> {
                    if (calls.incrementAndGet() == 1) {
                        var slept = new java.util.concurrent.atomic.AtomicBoolean(false);
                        return (x, y, z, target) -> {
                            if (slept.compareAndSet(false, true)) {
                                Thread.sleep(500);
                            }
                            return target.equals(PLAINS);
                        };
                    }
                    throw new RuntimeException("sampling failed");
                },
                seed -> hitCount.incrementAndGet(),
                result -> resultRef.set(result),
                attempts -> {});
        awaitResult(resultRef, 5000);

        assertInstanceOf(SeedSearchResult.Hit.class, resultRef.get());
        assertEquals(1, hitCount.get(), "onHit must fire exactly once");
    }

    @Test
    @DisplayName("Multi-hit keeps an in-flight hit when the abort fires")
    void multiHitKeepsInFlightHitAfterAbort() throws Exception {
        var calls = new AtomicInteger(0);
        var request = request(RARE, 30, 5);

        var resultRef = new AtomicReference<SeedSearchResult>();

        service.startSearch(request,
                seed -> {
                    if (calls.incrementAndGet() == 1) {
                        var slept = new java.util.concurrent.atomic.AtomicBoolean(false);
                        return (x, y, z, target) -> {
                            if (slept.compareAndSet(false, true)) {
                                Thread.sleep(500);
                            }
                            return target.equals(RARE);
                        };
                    }
                    throw new RuntimeException("sampling failed");
                },
                seed -> {},
                result -> resultRef.set(result),
                attempts -> {});
        awaitResult(resultRef, 5000);

        // The abort discards nothing that was already computed: the slow hit
        // recorded by the in-flight worker must appear in the final result
        // (the pre-fix code returned Miss here).
        assertInstanceOf(SeedSearchResult.Multiple.class, resultRef.get());
    }

    @Test
    @DisplayName("Coordinator crash reports a result and unblocks the next search")
    void coordinatorCrashReportsMissAndUnblocksNextSearch() throws Exception {
        try (var svc = new SeedSearchService(null, 1)) {
            var resultRef = new AtomicReference<SeedSearchResult>();
            svc.startSearch(request(PLAINS, 10, 1),
                    seed -> {
                        throw new AssertionError("coordinator crash");
                    },
                    seed -> {},
                    result -> resultRef.set(result),
                    attempts -> {});
            awaitResult(resultRef, 5000);

            assertInstanceOf(SeedSearchResult.Miss.class, resultRef.get());
            assertFalse(svc.isSearching(), "crashed task must not keep isSearching() true");

            // The follow-up search must be accepted and complete.
            var secondRef = new AtomicReference<SeedSearchResult>();
            assertTrue(svc.startSearch(request(PLAINS, 10, 1),
                    seed -> (x, y, z, target) -> false,
                    seed -> {},
                    result -> secondRef.set(result),
                    attempts -> {}));
            awaitResult(secondRef, 5000);
            assertInstanceOf(SeedSearchResult.Miss.class, secondRef.get());
        }
    }

    @Test
    @DisplayName("Follow-up search starts while cancelled workers are still parked")
    void restartAfterCancelIsNotBlockedByLingeringWorkers() throws Exception {
        try (var svc = new SeedSearchService(null, 2)) {
            var gate = new CountDownLatch(1);
            var entered = new AtomicInteger(0);
            var secondRef = new AtomicReference<SeedSearchResult>();

            svc.startSearch(request(RARE, 100, 1),
                    seed -> (x, y, z, target) -> {
                        entered.incrementAndGet();
                        gate.await();
                        return false;
                    },
                    seed -> {},
                    result -> {},
                    attempts -> {});

            long deadline = System.currentTimeMillis() + 5000;
            while (entered.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(2, entered.get(), "coordinator and worker must both be parked");

            svc.cancel();
            assertTrue(svc.startSearch(request(PLAINS, 4, 1),
                    seed -> (x, y, z, target) -> false,
                    seed -> {},
                    result -> secondRef.set(result),
                    attempts -> {}), "cancelled search must not block a follow-up search");

            // With coordinator headroom the follow-up search makes progress
            // immediately; the old pool sizing queued it behind the parked pair.
            deadline = System.currentTimeMillis() + 5000;
            while (svc.attemptCount() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(svc.attemptCount() >= 1, "follow-up search must evaluate candidates while the cancelled search lingers");

            gate.countDown();
            awaitResult(secondRef, 5000);
            assertInstanceOf(SeedSearchResult.Miss.class, secondRef.get());
        }
    }

    // === Regression test for the cancel-vs-hit-dispatch race ===

    /** Reflection seam: the current task's consecutiveFailures counter. */
    private static AtomicInteger currentConsecutiveFailures(SeedSearchService svc)
            throws ReflectiveOperationException {
        var taskField = SeedSearchService.class.getDeclaredField("currentTask");
        taskField.setAccessible(true);
        Object task = taskField.get(svc);
        if (task == null) {
            return null;
        }
        var failuresField = task.getClass().getDeclaredField("consecutiveFailures");
        failuresField.setAccessible(true);
        return (AtomicInteger) failuresField.get(task);
    }

    /** Waits until the counter reaches the expected value, with a deadline. */
    private static void awaitValue(AtomicInteger value, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (value.get() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, value.get(), "timed out waiting for consecutiveFailures=" + expected);
    }

    @Test
    @DisplayName("Cancel racing the hit dispatch still delivers exactly one Cancelled")
    void cancelRacingHitDispatchStillReportsCancelled() throws Exception {
        try (var svc = new SeedSearchService(null, 2)) {
            // createSampler ordinal state machine: #1 throws (drives
            // consecutiveFailures 0 -> 1), #2 parks on loserGate (holds the
            // last workerLatch permit so the dispatch cannot run early),
            // #3 parks on winnerGate and then returns the hit.
            var ordinal = new AtomicInteger();
            var winnerGate = new CountDownLatch(1);
            var loserGate = new CountDownLatch(1);
            var resultRef = new AtomicReference<SeedSearchResult>();
            var completeCount = new AtomicInteger(0);
            var hitCount = new AtomicInteger(0);

            svc.startSearch(request(PLAINS, 10, 1),
                    seed -> {
                        int n = ordinal.incrementAndGet();
                        if (n == 1) {
                            throw new RuntimeException("first attempt fails");
                        }
                        boolean winner = n == 3;
                        CountDownLatch gate = winner ? winnerGate : loserGate;
                        return (x, y, z, target) -> {
                            gate.await(10, TimeUnit.SECONDS);
                            return winner && target.equals(PLAINS);
                        };
                    },
                    seed -> hitCount.incrementAndGet(),
                    result -> {
                        completeCount.incrementAndGet();
                        resultRef.set(result);
                    },
                    attempts -> {});

            // The failing attempt ran (0 -> 1).
            var failures = currentConsecutiveFailures(svc);
            assertNotNull(failures);
            awaitValue(failures, 1, 5000);

            // Release the winning evaluation. Observing the reset (1 -> 0)
            // afterwards proves the winner passed evaluateLoop's cancelled
            // check: the reset is the only task state written after that
            // check and before the dispatch guard, so no public API can pin
            // this window without it. If a future change moves the cancelled
            // check after the reset, this test silently loses its power.
            winnerGate.countDown();
            awaitValue(failures, 0, 5000);

            // Now cancel lands inside the dispatch window with certainty,
            // and the parked loser keeps the coordinator from dispatching
            // until the gate below opens.
            svc.cancel();
            loserGate.countDown();

            awaitResult(resultRef, 5000);
            assertInstanceOf(SeedSearchResult.Cancelled.class, resultRef.get());
            assertEquals(1, completeCount.get(), "onComplete must fire exactly once");
            assertEquals(0, hitCount.get(), "the raced hit must not reach onHit");
            assertFalse(svc.isSearching());
        }
    }
}


