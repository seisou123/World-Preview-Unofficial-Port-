package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

/**
 * Dedicated seed search service.
 * <p>
 * Receives an immutable {@link SeedSearchRequest}, creates temporary worldgen context for each candidate seed,
 * evaluates every {@link SearchCriterion} against the sampled viewport and keeps the best
 * {@code maxHits} ranked seeds. Searches with {@code maxHits == 1} stop on the first hit.
 * At most one task holds the search slot at a time; a cancelled task's workers
 * may still be draining while its replacement starts.
 * </p>
 */
public class SeedSearchService implements AutoCloseable {

    /** Abort search early if consecutive failures exceed this threshold */
    private static final int MAX_CONSECUTIVE_FAILURES = 10;

    /** Log interval while the coordinator waits for in-flight worker evaluations */
    private static final long WORKER_WAIT_LOG_INTERVAL_SECONDS = 10;

    private final SplittableRandom random = new SplittableRandom();
    private final ExecutorService executor;
    /** Worker count for candidate evaluation; the fixed pool holds one extra coordinator slot. */
    private final int parallelism;

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
        this.parallelism = Math.max(1, threadCount);
        // One slot of headroom: the coordinator runs on this pool too, so a
        // follow-up search started right after a cancellation must not queue
        // behind the previous task's still-finishing workers (otherwise it
        // degrades to single-threaded and can trip the worker wait diagnostics).
        this.executor = Executors.newFixedThreadPool(this.parallelism + 1);
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

        CompletableFuture.runAsync(() -> executeSearch(task, latch), executor)
                .exceptionally(error -> {
                    LOGGER.error("Seed search failed unexpectedly", error);
                    // Fallback dispatch: the coordinator rethrows any Throwable
                    // escaping its own loop, so without this cleanup the task
                    // handle would linger forever and isSearching() would keep
                    // rejecting every follow-up search. Mirror reportResult()'s
                    // cleanup set (task + fingerprint; the latch was already
                    // counted down by executeSearch's finally).
                    minecraftExecute(() -> {
                        if (currentTask == task) {
                            currentTask = null;
                            currentFingerprint = null;
                            onComplete.accept(cancelled.get()
                                    ? new SeedSearchResult.Cancelled()
                                    : new SeedSearchResult.Miss());
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

    private void executeSearch(SearchTask task, CountDownLatch doneLatch) {
        try {
            var request = task.request;
            var cancelled = task.cancelled;
            var attemptedSeeds = new HashSet<Long>();

            // Generate sample point list (consistent with PreviewDisplay's render grid)
            var samplePoints = generateSamplePoints(request);

            // Candidate-level parallelism: every worker pulls the next candidate
            // slot from the same shared budget and the same unique-seed source,
            // so the union of all evaluations matches the serial loop exactly
            // (maxAttempts unique seeds, no duplicates). Which worker evaluates
            // which seed first is nondeterministic — that only changes WHICH
            // seed is tried k-th, never the result semantics. The coordinator
            // thread participates as one of the workers, keeping the fixed
            // pool fully utilized.
            var stop = new AtomicBoolean(false);
            var hit = new AtomicReference<SeedEvaluation>();
            var workerLatch = new CountDownLatch(parallelism - 1);
            for (int w = 1; w < parallelism; w++) {
                CompletableFuture.runAsync(() -> {
                    try {
                        evaluateLoop(task, attemptedSeeds, samplePoints, hit, stop);
                    } catch (Throwable t) {
                        stop.set(true);
                        LOGGER.error("Seed search worker failed unexpectedly", t);
                    } finally {
                        workerLatch.countDown();
                    }
                }, executor);
            }
            try {
                evaluateLoop(task, attemptedSeeds, samplePoints, hit, stop);
            } catch (Throwable t) {
                stop.set(true);
                // Not logged here: the exceptionally() fallback on the
                // runAsync future is the single record point for coordinator
                // escapes, including generateSamplePoints and the dispatch
                // chain that this catch does not wrap.
                throw t;
            }
            try {
                // No hard timeout: a single evaluation can legitimately take
                // minutes (a structure probe with a large radius scans up to
                // ~16k placement cells) and the serial implementation waited
                // for the whole budget too. Dispatching before the latch hits
                // zero would race in-flight evaluations and can silently drop
                // a slow hit, so keep waiting and only log periodically.
                long waitedSeconds = 0;
                while (!workerLatch.await(WORKER_WAIT_LOG_INTERVAL_SECONDS, TimeUnit.SECONDS)) {
                    waitedSeconds += WORKER_WAIT_LOG_INTERVAL_SECONDS;
                    LOGGER.warn("Seed search workers still running after {}s ({} pending)",
                            waitedSeconds, workerLatch.getCount());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop.set(true);
            }

            SeedEvaluation evaluation = hit.get();
            if (evaluation != null) {
                // Hit!
                reportHit(task, evaluation.seed(), evaluation.score(), evaluation.structurePos());
            } else if (cancelled.get()) {
                reportResult(task, new SeedSearchResult.Cancelled());
            } else {
                // Exhausted max attempts (or aborted after repeated failures)
                reportResult(task, finish(task));
            }
        } finally {
            // Count down this task's own latch, never whatever currentLatch
            // points at by now (a follow-up search may already have replaced it).
            doneLatch.countDown();
        }
    }

    /**
     * Serial worker loop shared by the coordinator thread and the executor
     * workers: claims the next attempt slot from the shared budget, evaluates
     * that candidate and records single-hit / abort stop signals.
     */
    private void evaluateLoop(SearchTask task, Set<Long> attemptedSeeds, BlockPos[] samplePoints,
                              AtomicReference<SeedEvaluation> hit, AtomicBoolean stop) {
        var request = task.request;
        var cancelled = task.cancelled;
        while (!cancelled.get() && !stop.get()) {
            // Claim the next attempt slot from the shared budget (CAS keeps
            // task.attempts exact and <= maxAttempts under concurrency).
            int attempt = claimAttemptSlot(task, request.maxAttempts());
            if (attempt < 0) {
                return;
            }
            long candidateSeed = nextUniqueSeed(attemptedSeeds);

            // Notify UI of progress (first attempt and every 2nd attempt)
            if (task.onProgress != null && (attempt == 1 || attempt % 2 == 0)) {
                final int attempts = attempt;
                minecraftExecute(() -> task.onProgress.accept(attempts));
            }

            try {
                SeedEvaluation evaluation = evaluateSeed(candidateSeed, request, samplePoints, task);
                if (cancelled.get()) {
                    // Cancellation discards the in-flight result; the
                    // coordinator reports the explicit Cancelled outcome.
                    return;
                }
                if (evaluation != null) {
                    // Sampled successfully; reset consecutive failure count
                    task.consecutiveFailures.set(0);
                    if (request.maxHits() == 1) {
                        // First worker to land a hit wins the report; every
                        // other worker loses the CAS and returns. The CAS alone
                        // guarantees exactly-once, so this record must happen
                        // even when stop is already set (an abort racing this
                        // evaluation must not drop a computed hit).
                        if (hit.compareAndSet(null, evaluation)) {
                            stop.set(true);
                        }
                        return;
                    }
                    // Record before any stop check: an abort (too many
                    // consecutive failures elsewhere) must not discard a hit
                    // this worker has already computed. The coordinator only
                    // snapshots task.hits after all workers exited.
                    task.hits.add(new SeedSearchResult.Ranked(candidateSeed, evaluation.score(), evaluation.structurePos()));
                } else {
                    // Sampled successfully but no hit, reset consecutive failure count
                    task.consecutiveFailures.set(0);
                }
            } catch (Exception e) {
                LOGGER.warn("Seed {} threw exception during sampling, skipping", candidateSeed, e);
                if (task.consecutiveFailures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES) {
                    LOGGER.error("Too many consecutive failures ({}), aborting search", MAX_CONSECUTIVE_FAILURES);
                    stop.set(true);
                    return;
                }
            }
        }
    }

    /**
     * Atomically claims the next attempt slot; returns the 1-based attempt
     * number, or -1 when the budget of {@code maxAttempts} is exhausted.
     */
    private static int claimAttemptSlot(SearchTask task, int maxAttempts) {
        for (;;) {
            int seen = task.attempts.get();
            if (seen >= maxAttempts) {
                return -1;
            }
            if (task.attempts.compareAndSet(seen, seen + 1)) {
                return seen + 1;
            }
        }
    }

    /**
     * Draws the next never-tried seed. Synchronized because the draw itself
     * costs nanoseconds next to the millisecond-scale per-seed evaluation.
     */
    private synchronized long nextUniqueSeed(Set<Long> attemptedSeeds) {
        long candidateSeed;
        do {
            candidateSeed = random.nextLong();
        } while (!attemptedSeeds.add(candidateSeed));
        return candidateSeed;
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
     * <p>
     * Biome criteria are compiled up front and their match statistics are
     * collected in a single pass over the sample grid (one biome lookup per
     * point) whenever the sampler supports holder sampling; string-only
     * samplers fall back to one grid pass per criterion. The criteria are then
     * scored in request order: the first failure aborts the seed and skips
     * every remaining criterion (including structure probes).
     * </p>
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

            List<CompiledBiome> compiled = compileBiomeCriteria(request.criteria(), sampler);
            collectBiomeMatches(compiled, request, samplePoints, sampler, task.cancelled::get);

            // Score in request order; contributions are summed in the same
            // order once every criterion has passed (float addition is not
            // commutative, so the order is part of the semantics).
            List<SearchCriterion> criteria = request.criteria();
            double[] contributions = new double[criteria.size()];
            BlockPos structurePos = null;
            int biomeIdx = 0;

            for (int i = 0; i < criteria.size(); i++) {
                if (task.cancelled.get()) return null;
                switch (criteria.get(i)) {
                    case SearchCriterion.Biome biome -> {
                        Double criterionScore = scoreBiome(compiled.get(biomeIdx++), samplePoints.length);
                        if (criterionScore == null) return null;
                        contributions[i] = criterionScore;
                    }
                    case SearchCriterion.BiomeGroup biomeGroup -> {
                        Double criterionScore = scoreBiome(compiled.get(biomeIdx++), samplePoints.length);
                        if (criterionScore == null) return null;
                        contributions[i] = criterionScore;
                    }
                    case SearchCriterion.Structure structure -> {
                        StructureEvaluation evaluation = evaluateStructure(structure, request, sampler);
                        if (evaluation == null) return null;
                        contributions[i] = evaluation.score();
                        structurePos = evaluation.position();
                    }
                }
            }

            double score = 0.0;
            for (double contribution : contributions) {
                score += contribution;
            }
            return new SeedEvaluation(seed, score, structurePos);
        }
    }

    /**
     * Compiles every biome/biome-group criterion of the request into a
     * matcher over biome holders. When all targets of a criterion resolve
     * against the sampler's possible biomes, an identity set of holders is
     * used (fast path); otherwise the matcher compares the resolved key
     * identifier against the target set (the same semantics as the
     * identifier-based sampling).
     */
    private static List<CompiledBiome> compileBiomeCriteria(List<SearchCriterion> criteria,
                                                            BiomeSampler sampler) throws Exception {
        List<CompiledBiome> compiled = new ArrayList<>();
        int order = 0;
        for (SearchCriterion criterion : criteria) {
            switch (criterion) {
                case SearchCriterion.Biome biome ->
                        compiled.add(compileBiome(order++, Set.of(biome.biome()), false,
                                biome.minAreaPercent(), biome.maxDistance(), sampler));
                case SearchCriterion.BiomeGroup biomeGroup ->
                        compiled.add(compileBiome(order++, Set.copyOf(biomeGroup.biomes()), true,
                                biomeGroup.minAreaPercent(), biomeGroup.maxDistance(), sampler));
                case SearchCriterion.Structure structure -> { }
            }
        }
        return compiled;
    }

    private static CompiledBiome compileBiome(int order, Set<Identifier> targets, boolean anyOf,
                                              int minAreaPercent, int maxDistance,
                                              BiomeSampler sampler) throws Exception {
        Identifier singleTarget = anyOf ? null : targets.iterator().next();
        Predicate<Holder<Biome>> matcher = null;
        if (sampler.supportsHolderSampling()) {
            Set<Holder<Biome>> identityTargets = new HashSet<>();
            boolean allResolved = true;
            for (Identifier target : targets) {
                Holder<Biome> holder = findPossibleBiome(sampler.possibleBiomes(), target);
                if (holder == null) {
                    allResolved = false;
                    break;
                }
                identityTargets.add(holder);
            }
            if (allResolved) {
                matcher = identityTargets::contains;
            }
        }
        if (matcher == null) {
            // Identifier fallback: a point matches when the sampled holder's
            // key is one of the targets (null holders never match).
            matcher = holder -> holder != null
                    && holder.unwrapKey().map(key -> targets.contains(key.identifier())).orElse(false);
        }
        return new CompiledBiome(order, matcher, anyOf, targets, singleTarget, minAreaPercent, maxDistance);
    }

    /** Finds the possible-biomes holder whose key matches {@code target}, or null. */
    @Nullable
    private static Holder<Biome> findPossibleBiome(Collection<Holder<Biome>> possible, Identifier target) {
        for (Holder<Biome> holder : possible) {
            if (holder != null
                    && holder.unwrapKey().map(key -> key.identifier().equals(target)).orElse(false)) {
                return holder;
            }
        }
        return null;
    }

    /**
     * Collects the match statistics (match count and nearest-match distance)
     * of every compiled biome criterion. Holder-capable samplers run one pass
     * over the grid with a single {@link BiomeSampler#biomeHolderAt} lookup
     * per point; identifier-only samplers run one pass per criterion via
     * {@link BiomeSampler#sampleContains}/{@link BiomeSampler#sampleContainsAny}.
     */
    private static void collectBiomeMatches(List<CompiledBiome> compiled, SeedSearchRequest request,
                                            BlockPos[] samplePoints, BiomeSampler sampler,
                                            BooleanSupplier cancelled) throws Exception {
        if (compiled.isEmpty()) {
            return;
        }
        BlockPos center = request.center();
        if (sampler.supportsHolderSampling()) {
            for (CompiledBiome c : compiled) {
                c.matchCount = 0;
                c.minDistance = Double.MAX_VALUE;
            }
            for (BlockPos pos : samplePoints) {
                if (cancelled.getAsBoolean()) return;
                Holder<Biome> holder = sampler.biomeHolderAt(pos.getX(), pos.getY(), pos.getZ());
                for (CompiledBiome c : compiled) {
                    if (c.matcher.test(holder)) {
                        c.matchCount++;
                        // Calculate distance from screen center
                        double dx = pos.getX() - center.getX();
                        double dz = pos.getZ() - center.getZ();
                        double distance = Math.sqrt(dx * dx + dz * dz);
                        c.minDistance = Math.min(c.minDistance, distance);
                    }
                }
            }
        } else {
            for (CompiledBiome c : compiled) {
                if (cancelled.getAsBoolean()) return;
                c.matchCount = 0;
                c.minDistance = Double.MAX_VALUE;
                for (BlockPos pos : samplePoints) {
                    boolean matches = c.anyOf
                            ? sampler.sampleContainsAny(pos.getX(), pos.getY(), pos.getZ(), c.targets)
                            : sampler.sampleContains(pos.getX(), pos.getY(), pos.getZ(), c.singleTarget);
                    if (matches) {
                        c.matchCount++;
                        // Calculate distance from screen center
                        double dx = pos.getX() - center.getX();
                        double dz = pos.getZ() - center.getZ();
                        double distance = Math.sqrt(dx * dx + dz * dz);
                        c.minDistance = Math.min(c.minDistance, distance);
                    }
                }
            }
        }
    }

    /**
     * Applies a compiled biome criterion's thresholds and scoring to the
     * statistics collected by {@link #collectBiomeMatches}. Mirrors the
     * previous per-criterion evaluation exactly: area check, then distance
     * check (strict {@code >}), then the at-least-one-match requirement.
     *
     * @return score contribution, or {@code null} when the criterion fails
     */
    @Nullable
    private static Double scoreBiome(CompiledBiome c, int samplePointCount) {
        // Check area percentage
        double areaPercent = (samplePointCount > 0) ? (c.matchCount * 100.0 / samplePointCount) : 0;
        if (areaPercent < c.minAreaPercent) {
            return null;
        }

        // Check distance requirement
        if (c.maxDistance > 0 && c.minDistance > c.maxDistance) {
            return null;
        }

        // At least one matching point is required for a hit
        if (c.matchCount == 0) {
            return null;
        }

        // Base score: coverage. When a distance cap is set, reward proximity to the center.
        double score = areaPercent;
        if (c.maxDistance > 0) {
            score += 50.0 * (1.0 - Math.min(1.0, c.minDistance / c.maxDistance));
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
     * A biome/biome-group criterion compiled for evaluation: a matcher over
     * biome holders plus the mutable per-candidate statistics (match count and
     * nearest-match distance) filled in by {@link #collectBiomeMatches}.
     */
    private static final class CompiledBiome {
        /** Position of the criterion within the request's criteria list. */
        final int order;
        final Predicate<Holder<Biome>> matcher;
        /** true: any-of group matching; false: exact single-biome matching. */
        final boolean anyOf;
        final Set<Identifier> targets;
        @Nullable final Identifier singleTarget;
        final int minAreaPercent;
        final int maxDistance;
        int matchCount;
        double minDistance = Double.MAX_VALUE;

        CompiledBiome(int order, Predicate<Holder<Biome>> matcher, boolean anyOf,
                      Set<Identifier> targets, @Nullable Identifier singleTarget,
                      int minAreaPercent, int maxDistance) {
            this.order = order;
            this.matcher = matcher;
            this.anyOf = anyOf;
            this.targets = targets;
            this.singleTarget = singleTarget;
            this.minAreaPercent = minAreaPercent;
            this.maxDistance = maxDistance;
        }
    }

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
     * Report hit result on the main thread and validate fingerprint. A task
     * that was cancelled while its hit was in dispatch still receives exactly
     * one terminal {@link SeedSearchResult.Cancelled} callback.
     */
    private void reportHit(SearchTask task, long seed, double score, @Nullable BlockPos structurePos) {
        minecraftExecute(() -> {
            // Validate: is the task token still valid?
            if (currentTask != task) {
                LOGGER.info("Search result discarded: task no longer current");
                return;
            }
            if (task.cancelled.get()) {
                // Cancellation raced the hit dispatch (it landed after the
                // winning worker's cancelled check in evaluateLoop but before
                // this guard): the hit is discarded as before, but the caller
                // still gets exactly one terminal callback instead of waiting
                // for a result that never arrives.
                LOGGER.info("Search hit discarded: task cancelled during dispatch");
                currentTask = null;
                currentFingerprint = null;
                task.onComplete.accept(new SeedSearchResult.Cancelled());
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

        /**
         * Whether this sampler exposes biome holders directly
         * ({@link #biomeHolderAt}). That lets the search service evaluate every
         * biome criterion in one pass with a single biome lookup per sample
         * point; otherwise the per-criterion identifier-based path is used.
         */
        default boolean supportsHolderSampling() {
            return false;
        }

        /**
         * Return the biome holder at the given coordinates, or {@code null}
         * when the biome cannot be resolved. Only called when
         * {@link #supportsHolderSampling()} is true.
         */
        @Nullable
        default Holder<Biome> biomeHolderAt(int x, int y, int z) throws Exception {
            return null;
        }

        /**
         * Return every biome this sampler can ever produce. Used to build
         * identity matchers for the single-pass path; the collection must
         * contain the same holder instances that {@link #biomeHolderAt}
         * returns. Samplers without holder support may return an empty
         * collection.
         */
        default Collection<Holder<Biome>> possibleBiomes() {
            return List.of();
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
