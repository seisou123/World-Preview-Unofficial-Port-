package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

/**
 * Dedicated seed search service.
 * <p>
 * Receives an immutable {@link SeedSearchRequest}, creates temporary worldgen context for each candidate seed,
 * evaluates every {@link SearchCriterion} against the sampled viewport and keeps the best
 * {@code maxHits} ranked seeds. Searches with {@code maxHits == 1} stop on the first hit.
 * Only one search task may run at a time.
 * </p>
 */
public class SeedSearchService implements AutoCloseable {

    /** Abort search early if consecutive failures exceed this threshold */
    private static final int MAX_CONSECUTIVE_FAILURES = 10;

    private final SplittableRandom random = new SplittableRandom();
    private final ExecutorService executor;

    /** Handle to the currently running search task, used for cancellation */
    @Nullable private volatile SearchTask currentTask;

    /** Fingerprint of the current search task, used to validate callbacks */
    @Nullable private volatile String currentFingerprint;

    /** Latch for the current search task, used to wait for background thread exit */
    @Nullable private volatile CountDownLatch currentLatch;

    /** Executor for switching search result callbacks back to the main thread */
    @Nullable private final Minecraft minecraft;

    public SeedSearchService(@Nullable Minecraft minecraft, int threadCount) {
        this.minecraft = minecraft;
        this.executor = Executors.newFixedThreadPool(Math.max(1, threadCount));
    }

    /**
     * Start a search. Returns false if a search is already running.
     *
     * @param request         Search request (immutable snapshot)
     * @param contextFactory  Seed-specific worldgen context factory
     * @param onHit           Hit callback (main thread, receives the hit seed; only used for single-hit requests)
     * @param onComplete      Completion callback (main thread, receives final result)
     * @return true if search started, false if a search is already running
     */
    public synchronized boolean startSearch(
            SeedSearchRequest request,
            SeedContextFactory contextFactory,
            Consumer<Long> onHit,
            Consumer<SeedSearchResult> onComplete,
            Consumer<Integer> onProgress
    ) {
        if (currentTask != null && !currentTask.cancelled.get()) {
            LOGGER.warn("Search already in progress, rejecting new request");
            return false;
        }

        var cancelled = new AtomicBoolean(false);
        var task = new SearchTask(cancelled, request, contextFactory, onHit, onComplete, onProgress);
        var latch = new CountDownLatch(1);
        currentTask = task;
        currentFingerprint = request.contextFingerprint();
        currentLatch = latch;

        CompletableFuture.runAsync(() -> executeSearch(task), executor)
                .exceptionally(error -> {
                    LOGGER.error("Seed search failed unexpectedly", error);
                    minecraftExecute(() -> {
                        if (currentTask == task && !cancelled.get()) {
                            onComplete.accept(new SeedSearchResult.Miss());
                        }
                    });
                    return null;
                });

        return true;
    }

    /**
     * Cancel the current search task.
     */
    public void cancel() {
        var task = currentTask;
        if (task != null) {
            task.cancelled.set(true);
        }
    }

    /**
     * Cancel and wait for the background thread to exit.
     */
    public void cancelAndAwait() {
        var latch = currentLatch;
        cancel();
        if (latch != null) {
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Whether a search task is currently running.
     */
    public boolean isSearching() {
        var task = currentTask;
        return task != null && !task.cancelled.get();
    }

    /**
     * Number of attempts made in the current search, or -1 if no search is active.
     */
    public int attemptCount() {
        var task = currentTask;
        return task != null ? task.attempts.get() : -1;
    }

    /**
     * Number of hits collected so far in the current search, or -1 if no search is active.
     */
    public int hitCount() {
        var task = currentTask;
        return task != null ? task.hits.size() : -1;
    }

    /**
     * Stop the current search and reset state (used when closing the preview tab).
     */
    @Override
    public void close() {
        cancelAndAwait();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("SeedSearchService executor did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========== Internal search logic ==========

    private void executeSearch(SearchTask task) {
        try {
            var request = task.request;
            var cancelled = task.cancelled;
            var attemptedSeeds = new HashSet<Long>();

            // Generate sample point list (consistent with PreviewDisplay's render grid)
            var samplePoints = generateSamplePoints(request);

            for (int i = 0; i < request.maxAttempts(); i++) {
                if (cancelled.get()) {
                    reportResult(task, new SeedSearchResult.Cancelled());
                    return;
                }

                // Generate unique random seeds
                long candidateSeed;
                do {
                    candidateSeed = random.nextLong();
                } while (!attemptedSeeds.add(candidateSeed));

                task.attempts.incrementAndGet();

                // Notify UI of progress (first attempt and every 2nd attempt)
                int currentAttempts = task.attempts.get();
                if (task.onProgress != null && (currentAttempts == 1 || currentAttempts % 2 == 0)) {
                    final int attempts = currentAttempts;
                    minecraftExecute(() -> task.onProgress.accept(attempts));
                }

                try {
                    SeedEvaluation evaluation = evaluateSeed(candidateSeed, request, samplePoints, task);
                    if (evaluation != null) {
                        // Sampled successfully; reset consecutive failure count
                        task.consecutiveFailures.set(0);
                        if (request.maxHits() == 1) {
                            // Hit!
                            reportHit(task, candidateSeed, evaluation.score(), evaluation.structurePos());
                            return;
                        }
                        task.hits.add(new SeedSearchResult.Ranked(candidateSeed, evaluation.score(), evaluation.structurePos()));
                    } else {
                        // Sampled successfully but no hit, reset consecutive failure count
                        task.consecutiveFailures.set(0);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Seed {} threw exception during sampling, skipping", candidateSeed, e);
                    task.consecutiveFailures.incrementAndGet();
                    if (task.consecutiveFailures.get() >= MAX_CONSECUTIVE_FAILURES) {
                        LOGGER.error("Too many consecutive failures ({}), aborting search", MAX_CONSECUTIVE_FAILURES);
                        reportResult(task, finish(task));
                        return;
                    }
                }
            }

            // Exhausted max attempts
            reportResult(task, finish(task));
        } finally {
            var latch = currentLatch;
            if (latch != null) latch.countDown();
        }
    }

    /**
     * Builds the final result for multi-hit searches: the best
     * {@code maxHits} ranked hits (best first), or a Miss when nothing matched.
     */
    private static SeedSearchResult finish(SearchTask task) {
        List<SeedSearchResult.Ranked> hits = new ArrayList<>(task.hits);
        if (hits.isEmpty()) {
            return new SeedSearchResult.Miss();
        }
        hits.sort(Comparator.comparingDouble(SeedSearchResult.Ranked::score).reversed());
        int limit = Math.min(hits.size(), task.request.maxHits());
        // Lineage: multi-hit results carry the originating request so consumers
        // can verify the context fingerprint and reuse the search parameters.
        return new SeedSearchResult.Multiple(hits.subList(0, limit), task.request);
    }

    /**
     * Evaluates every criterion of the request against the candidate seed.
     *
     * @return the seed's score when all criteria pass, or {@code null} when any fails
     */
    @Nullable
    private SeedEvaluation evaluateSeed(long seed, SeedSearchRequest request,
                                        BlockPos[] samplePoints, SearchTask task) throws Exception {
        // Check cancellation flag
        if (task.cancelled.get()) return null;

        // Create seed-specific sampler, use try-with-resources to ensure cleanup
        try (var sampler = task.contextFactory.createSampler(seed)) {
            if (task.cancelled.get()) return null;

            double score = 0.0;
            BlockPos structurePos = null;

            for (SearchCriterion criterion : request.criteria()) {
                if (task.cancelled.get()) return null;
                switch (criterion) {
                    case SearchCriterion.Biome biome -> {
                        Double criterionScore = evaluateBiome(biome, request, samplePoints, sampler);
                        if (criterionScore == null) return null;
                        score += criterionScore;
                    }
                    case SearchCriterion.BiomeGroup biomeGroup -> {
                        Double criterionScore = evaluateBiomeGroup(biomeGroup, request, samplePoints, sampler);
                        if (criterionScore == null) return null;
                        score += criterionScore;
                    }
                    case SearchCriterion.Structure structure -> {
                        StructureEvaluation evaluation = evaluateStructure(structure, request, sampler);
                        if (evaluation == null) return null;
                        score += evaluation.score();
                        structurePos = evaluation.position();
                    }
                }
            }

            return new SeedEvaluation(seed, score, structurePos);
        }
    }

    /**
     * Checks a biome criterion: area coverage and center distance.
     *
     * @return score contribution, or {@code null} when the criterion fails
     */
    @Nullable
    private static Double evaluateBiome(SearchCriterion.Biome criterion, SeedSearchRequest request,
                                        BlockPos[] samplePoints, BiomeSampler sampler) throws Exception {
        return evaluateBiomeMatches(Set.of(criterion.biome()), false,
                criterion.minAreaPercent(), criterion.maxDistance(), request, samplePoints, sampler);
    }

    /**
     * Checks a biome group criterion: any-of matching across the group, with
     * area coverage and center distance computed over group matches.
     *
     * @return score contribution, or {@code null} when the criterion fails
     */
    @Nullable
    private static Double evaluateBiomeGroup(SearchCriterion.BiomeGroup criterion, SeedSearchRequest request,
                                             BlockPos[] samplePoints, BiomeSampler sampler) throws Exception {
        return evaluateBiomeMatches(Set.copyOf(criterion.biomes()), true,
                criterion.minAreaPercent(), criterion.maxDistance(), request, samplePoints, sampler);
    }

    /**
     * Shared biome evaluation for single-biome and biome-group criteria:
     * counts matching sample points, checks the area percentage and distance
     * requirement and scores coverage plus a proximity bonus.
     *
     * @param matchTargets   biome identifiers considered matching
     * @param anyOf          true: a point matches when any target matches
     *                       ({@link BiomeSampler#sampleContainsAny});
     *                       false: the single target must match exactly
     * @param minAreaPercent minimum required coverage of the viewport (0-100)
     * @param maxDistance    distance cap from the anchor for the nearest match (0 = unlimited)
     * @return score contribution, or {@code null} when the criterion fails
     */
    @Nullable
    private static Double evaluateBiomeMatches(Set<Identifier> matchTargets, boolean anyOf,
                                               int minAreaPercent, int maxDistance,
                                               SeedSearchRequest request,
                                               BlockPos[] samplePoints, BiomeSampler sampler) throws Exception {
        int matchCount = 0;
        double minDistance = Double.MAX_VALUE;
        BlockPos center = request.center();
        Identifier singleTarget = anyOf ? null : matchTargets.iterator().next();

        for (int i = 0; i < samplePoints.length; i++) {
            var pos = samplePoints[i];
            boolean matches = anyOf
                    ? sampler.sampleContainsAny(pos.getX(), pos.getY(), pos.getZ(), matchTargets)
                    : sampler.sampleContains(pos.getX(), pos.getY(), pos.getZ(), singleTarget);
            if (matches) {
                matchCount++;
                // Calculate distance from screen center
                double dx = pos.getX() - center.getX();
                double dz = pos.getZ() - center.getZ();
                double distance = Math.sqrt(dx * dx + dz * dz);
                minDistance = Math.min(minDistance, distance);
            }
        }

        // Check area percentage
        double areaPercent = (samplePoints.length > 0) ? (matchCount * 100.0 / samplePoints.length) : 0;
        if (areaPercent < minAreaPercent) {
            return null;
        }

        // Check distance requirement
        if (maxDistance > 0 && minDistance > maxDistance) {
            return null;
        }

        // At least one matching point is required for a hit
        if (matchCount == 0) {
            return null;
        }

        // Base score: coverage. When a distance cap is set, reward proximity to the center.
        double score = areaPercent;
        if (maxDistance > 0) {
            score += 50.0 * (1.0 - Math.min(1.0, minDistance / maxDistance));
        }
        return score;
    }

    /**
     * Checks a structure criterion via the sampler's {@link StructureProbe} capability.
     *
     * @return score contribution plus the located structure position, or {@code null} when the criterion fails or cannot be probed
     */
    @Nullable
    private static StructureEvaluation evaluateStructure(SearchCriterion.Structure criterion, SeedSearchRequest request,
                                                         BiomeSampler sampler) throws Exception {
        if (!(sampler instanceof StructureProbe probe)) {
            LOGGER.warn("Structure criterion {} ignored: sampler does not support structure probing", criterion.structure());
            return null;
        }
        BlockPos found = probe.nearestStructure(Set.of(criterion.structure()), request.center(), criterion.maxDistanceBlocks());
        if (found == null) {
            return null;
        }
        double dx = found.getX() - request.center().getX();
        double dz = found.getZ() - request.center().getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        // Score 50..100: closer structures rank higher
        double score = 50.0 + 50.0 * (1.0 - Math.min(1.0, distance / criterion.maxDistanceBlocks()));
        return new StructureEvaluation(score, found);
    }

    /** Structure criterion outcome: score contribution plus located position. */
    private record StructureEvaluation(double score, BlockPos position) {}

    /**
     * Generate sample point list based on the current viewport.
     * Sampling grid is consistent with PreviewDisplay's quartStride.
     */
    private static BlockPos[] generateSamplePoints(SeedSearchRequest request) {
        int step = request.sampleStep();
        int xMin = request.viewMinX();
        int xMax = request.viewMaxX();
        int zMin = request.viewMinZ();
        int zMax = request.viewMaxZ();
        int yLevel = request.yLevel();

        // Calculate sample point count
        int xCount = ((xMax - xMin) / step) + 1;
        int zCount = ((zMax - zMin) / step) + 1;
        var points = new BlockPos[xCount * zCount];
        int idx = 0;
        for (int x = xMin; x <= xMax; x += step) {
            for (int z = zMin; z <= zMax; z += step) {
                points[idx++] = new BlockPos(x, yLevel, z);
            }
        }
        return points;
    }

    /**
     * Report hit result on the main thread and validate fingerprint.
     */
    private void reportHit(SearchTask task, long seed, double score, @Nullable BlockPos structurePos) {
        minecraftExecute(() -> {
            // Validate: is the task token still valid?
            if (currentTask != task || task.cancelled.get()) {
                LOGGER.info("Search result discarded: task no longer current");
                return;
            }
            // Validate: does the config fingerprint match?
            if (!Objects.equals(currentFingerprint, task.request.contextFingerprint())) {
                LOGGER.info("Search result discarded: context fingerprint changed");
                return;
            }
            currentTask = null;
            currentFingerprint = null;
            if (task.onHit != null) {
                task.onHit.accept(seed);
            }
            // Lineage: the hit keeps the originating request + structure position.
            task.onComplete.accept(new SeedSearchResult.Hit(seed, score, task.request, structurePos));
        });
    }

    private void reportResult(SearchTask task, SeedSearchResult result) {
        minecraftExecute(() -> {
            if (currentTask != task) {
                return;
            }
            currentTask = null;
            currentFingerprint = null;
            task.onComplete.accept(result);
        });
    }

    /**
     * Execute on the main thread if Minecraft instance is available, otherwise execute directly.
     */
    private void minecraftExecute(Runnable runnable) {
        if (minecraft != null) {
            minecraft.execute(runnable);
        } else {
            runnable.run();
        }
    }

    // ========== Internal types ==========

    /** Seed evaluation outcome: non-null when all criteria passed. */
    private record SeedEvaluation(long seed, double score, @Nullable BlockPos structurePos) {}

    /** Search task state */
    private static class SearchTask {
        final AtomicBoolean cancelled;
        final SeedSearchRequest request;
        final SeedContextFactory contextFactory;
        final @Nullable Consumer<Long> onHit;
        final Consumer<SeedSearchResult> onComplete;
        final @Nullable Consumer<Integer> onProgress;
        final AtomicInteger attempts = new AtomicInteger(0);
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        final List<SeedSearchResult.Ranked> hits = new CopyOnWriteArrayList<>();

        SearchTask(
                AtomicBoolean cancelled,
                SeedSearchRequest request,
                SeedContextFactory contextFactory,
                @Nullable Consumer<Long> onHit,
                Consumer<SeedSearchResult> onComplete,
                @Nullable Consumer<Integer> onProgress
        ) {
            this.cancelled = cancelled;
            this.request = request;
            this.contextFactory = contextFactory;
            this.onHit = onHit;
            this.onComplete = onComplete;
            this.onProgress = onProgress;
        }
    }

    /**
     * Seed-specific sampler interface for checking if a candidate seed has the target biome at given coordinates.
     * Implements AutoCloseable for automatic resource cleanup in try-with-resources.
     */
    public interface BiomeSampler extends AutoCloseable {
        /**
         * Check if the biome at the given coordinates equals the target biome.
         *
         * @param x            Block X coordinate
         * @param y            Block Y coordinate
         * @param z            Block Z coordinate
         * @param targetBiome  Target biome Identifier
         * @return true if the biome at this coordinate equals the target biome
         * @throws Exception Exceptions that may occur during sampling
         */
        boolean sampleContains(int x, int y, int z, Identifier targetBiome) throws Exception;

        /**
         * Check whether any biome of the given group matches at the given
         * coordinates (logical OR within the group). Used by
         * {@link SearchCriterion.BiomeGroup}; backed by {@link #sampleContains}
         * unless a sampler provides a faster group probe.
         *
         * @param x       Block X coordinate
         * @param y       Block Y coordinate
         * @param z       Block Z coordinate
         * @param biomes  Biome identifiers of the group (non-empty)
         * @return true if the biome at this coordinate is any of the given biomes
         * @throws Exception Exceptions that may occur during sampling
         */
        default boolean sampleContainsAny(int x, int y, int z, Set<Identifier> biomes) throws Exception {
            for (Identifier id : biomes) {
                if (sampleContains(x, y, z, id)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Return the biome identifier at the given coordinates, or {@code null}
         * when the sampler cannot resolve biome identifiers. Only samplers with
         * real worldgen context (e.g. the lightweight probe) support this; plain
         * samplers return null and callers (the seed comparison screen) treat
         * null as "unavailable". The search service itself never calls this.
         */
        @Nullable
        default Identifier biomeAt(int x, int y, int z) throws Exception {
            return null;
        }

        @Override
        default void close() throws Exception {}
    }

    /**
     * Optional capability of a {@link BiomeSampler}: locate the nearest valid
     * generation point of a structure for the candidate seed. Implemented by
     * the lightweight worldgen probe; plain biome samplers do not support it.
     */
    public interface StructureProbe {
        /**
         * Find the nearest structure of any of the given types within
         * {@code maxDistanceBlocks} of {@code anchor} for the probed seed.
         *
         * @return the structure's locate position, or {@code null} when none is in range
         */
        @Nullable BlockPos nearestStructure(Set<Identifier> structures, BlockPos anchor, int maxDistanceBlocks) throws Exception;
    }

    /**
     * Seed-specific context factory for creating and closing temporary sampling contexts.
     * createSampler() is called once per candidate seed; the returned BiomeSampler is closed by the caller.
     */
    @FunctionalInterface
    public interface SeedContextFactory extends AutoCloseable {
        /**
         * Create a BiomeSampler for the given seed.
         * The caller is responsible for closing the returned BiomeSampler.
         */
        BiomeSampler createSampler(long seed) throws Exception;

        @Override
        default void close() throws Exception {}
    }
}
