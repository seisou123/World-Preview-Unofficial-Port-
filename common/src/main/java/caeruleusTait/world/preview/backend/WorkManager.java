// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.analysis.AnalysisRequest;
import caeruleusTait.world.preview.backend.analysis.AnalysisSession;
import caeruleusTait.world.preview.backend.analysis.Region;
import caeruleusTait.world.preview.backend.analysis.WorldgenContext;
import caeruleusTait.world.preview.domain.task.TaskScheduler;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.backend.storage.PreviewStorageCacheManager;
import caeruleusTait.world.preview.backend.worker.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public class WorkManager {
    public static final int Y_BLOCK_SHIFT = 3;
    public static final int Y_BLOCK_STRIDE = 1 << Y_BLOCK_SHIFT;

    /**
     * Set on the constructing thread while {@link SampleUtils} builds dummy/real preview
     * sampling infrastructure, so invasive mixins can run before {@link #isSetup()} is true.
     * Cleared in a finally block after construction finishes.
     */
    public static final ThreadLocal<Boolean> PREVIEW_GENERATION = ThreadLocal.withInitial(() -> false);

    /**
     * Whether invasive preview mixins should apply on the current thread / world-gen path.
     * True during SampleUtils construction or while the live preview WorkManager is set up.
     */
    public static boolean isPreviewGeneration() {
        if (Boolean.TRUE.equals(PREVIEW_GENERATION.get())) {
            return true;
        }
        WorldPreview preview = WorldPreview.get();
        return preview != null && preview.workManager().isSetup();
    }

    private final Object completedSynchro = new Object();

    private WorldOptions worldOptions;
    private LevelStem levelStem;
    private DimensionType dimensionType;
    private ChunkGenerator chunkGenerator;
    private BiomeSource biomeSource;
    // Volatile: the current sampling tier (see SamplingTier) as ONE immutable
    // snapshot, swapped on the render thread (drag edges, analysis entry,
    // worldgen rebuild) while queue-thread factories read it.  A single
    // volatile read binds a work unit's sampler and its coarse classification
    // to the same snapshot, so a drag edge can never tear the pair apart.
    private volatile SamplingTier samplingTier = null;

    /**
     * Immutable sampling-tier snapshot: the sampler a new work unit must use,
     * whether that sampler is the drag-tier coarse sampler, and the tier epoch
     * the unit freezes so its batch is discarded if the tier moves before the
     * results are applied.
     */
    private record SamplingTier(ChunkSampler sampler, boolean coarse, long epoch) {}
    // Volatile: nulled by cancel() on the render thread while the queue thread
    // (queueForLevel's storage probe) and worker threads may still read it.
    private volatile SampleUtils sampleUtils;
    private WorldgenContext worldgenContext;

    private PreviewData previewData;
    /**
     * Identity cache from preview biome holders to their biome2Id ids for the
     * current previewData. Holder.Reference uses identity equals (registry
     * singletons), so this is a pure lookup accelerator: it is rebuilt
     * whenever the worldgen state changes and never shares entries across
     * previewData instances.
     */
    private volatile Map<Holder<Biome>, Short> biomeHolderIdCache = null;
    // Volatile: read by the queue thread in the isChunkSampled probe and
    // nulled by cancel() on the render thread.
    private volatile PreviewStorage previewStorage;
    private PreviewStorageCacheManager previewStorageCacheManager;
    private final RenderSettings renderSettings;
    private final WorldPreviewConfig config;

    private final List<WorkBatch> currentBatches = new ArrayList<>();
    private final List<Future<?>> futures = new ArrayList<>();
    private final List<Future<?>> queueFutures = new ArrayList<>();
    private final SplittableRandom random = new SplittableRandom();

    // Volatile: written by the render thread (setup/cancel/suspend/resume) and
    // read by the queue thread (queueRange's accept check); without it a stale
    // read can submit into an executor that was already shut down.
    private volatile ExecutorService executorService;
    private volatile ExecutorService queueChunksService;

    // Guards the queueIsRunning / pending-range handshake between the render
    // thread (queueRange) and the queue thread (queueRangeWrapper finish path).
    private final Object queueStateLock = new Object();

    // Volatile: written by the render thread / drain thread and read by both
    // for dedup; a stale value would silently swallow a range request.
    private volatile ChunkPos lastQueuedTopLeft;
    private volatile ChunkPos lastQueuedBotRight;
    private volatile int lastY;

    // BUG FIX: These fields are accessed from both the render thread (queueRange)
    // and the queueChunksService thread (queueRangeWrapper/queueRangeReal).
    // Without volatile, changes made by one thread may not be visible to the other,
    // causing: (1) duplicate queuing when queueIsRunning=false is not seen by the
    // render thread, (2) wasted work when shouldEarlyAbortQueuing=true is not seen
    // by the queue thread.
    private volatile boolean queueIsRunning = false;
    private volatile boolean shouldEarlyAbortQueuing = false;

    // Deferred detail-layer queuing while the map is being dragged (see
    // setDeferredLayersWhileDragging).  Volatile: flipped by the render
    // thread on drag state changes, read by the queue thread at the head
    // of every queue pass.
    private volatile boolean deferLayersWhileDragging = false;

    // --- Drag-tier coarse sampling ---
    // While the map is dragged, the biome layer samples one quart-column per
    // chunk into the SAME storage; drag-tier units record their completion on
    // separate marks that drag release clears synchronously, so the coarse
    // fill never blocks the full-resolution re-sampling.  The sampler, the
    // coarse flag and the tier epoch travel together as one immutable
    // snapshot: a work unit reads the snapshot once and binds both its
    // sampler and its coarse marking to that single read, so a concurrent
    // drag edge can never produce a coarse sampler with a fine
    // classification.
    /** Monotonic drag-tier sequence: snapshot epochs are drawn from it, coarse batches are discarded when it moves. */
    private final AtomicLong dragTierEpoch = new AtomicLong(0);

    /** True while the current sampling tier is the drag-tier coarse tier. */
    private boolean isDragTierActive() {
        final SamplingTier tier = samplingTier;
        return tier != null && tier.coarse();
    }

    /**
     * True while sampling is suspended (worker pools torn down but the worldgen
     * state -- previewStorage/worldgenContext/sampleUtils/previewData/epoch --
     * preserved for a cheap resume).  See {@link #suspend()}.
     */
    private volatile boolean suspended = false;

    public boolean isSuspended() {
        return suspended;
    }

    /** Bumped on cancel/shutdown so in-flight queue work can detect obsolescence. */
    private final AtomicLong sessionEpoch = new AtomicLong(0);

    /** Monotonic submit sequence: FIFO within a layer rank for the priority worker queue. */
    private final AtomicLong workerSubmitSeq = new AtomicLong();

    /**
     * Current worldgen session epoch. Incremented whenever the world generation
     * state is torn down (world/dimension/generator/seed switch or shutdown).
     * Background tasks capture this value when they start and MUST re-check it
     * before publishing results or writing to storage; a mismatch means the
     * captured worldgen context is stale and its results must be discarded.
     */
    public long epoch() {
        return sessionEpoch.get();
    }

    /** Latest viewport range requested while a queue pass was already running. */
    private volatile BlockPos pendingTopLeft = null;
    private volatile BlockPos pendingBottomRight = null;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    public WorkManager(RenderSettings renderSettings, WorldPreviewConfig config) {
        this.config = config;
        this.renderSettings = renderSettings;
    }

    public synchronized void changeWorldGenState(
            LevelStem _levelStem,
            LayeredRegistryAccess<RegistryLayer> _registryAccess,
            PreviewData _previewData,
            WorldOptions _worldOptions,
            WorldDataConfiguration _worldDataConfiguration,
            PreviewStorageCacheManager _previewStorageCacheManager,
            Proxy proxy,
            @Nullable Path tempDataPackDir,
            @Nullable MinecraftServer server
    ) {
        cancel();
        worldOptions = _worldOptions;
        levelStem = _levelStem;
        dimensionType = levelStem.type().value();
        chunkGenerator = levelStem.generator();
        biomeSource = chunkGenerator.getBiomeSource();
        previewStorageCacheManager = _previewStorageCacheManager;
        samplingTier = new SamplingTier(
                renderSettings.samplerType.create(renderSettings.quartStride()), false, dragTierEpoch.get());
        previewData = _previewData;
        biomeHolderIdCache = new ConcurrentHashMap<>();

        try {
            worldgenContext = new WorldgenContext(
                    worldOptions,
                    levelStem,
                    _registryAccess,
                    _worldDataConfiguration,
                    proxy,
                    tempDataPackDir,
                    server
            );
            sampleUtils = worldgenContext.createSampleUtils();
            // The WorkManager owns the lifecycle of this shared preview sampler.
            // Prevent WorldgenContext.close() from closing it out from under us
            // when an analysis session that borrows this context is closed.
            worldgenContext.disownSampleUtils();
            LOGGER.info("SampleUtils created successfully");
        } catch (IOException e) {
            LOGGER.error("SampleUtils creation failed", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * MUST be called in the render thread and AFTER {@link #changeWorldGenState} has finished
     */
    public void postChangeWorldGenState() {
        // This call MAY change screens and MUST thus be called in the render thread!
        previewStorage = previewStorageCacheManager.loadPreviewStorage(worldOptions.seed(), yMin(), yMax());

        // Only create the executors at the end to ensure that there are no
        // null pointer exceptions
        executorService = createWorkerExecutor();
        queueChunksService = Executors.newSingleThreadExecutor();

        // Pre-start all core threads so the first batch of work units
        // doesn't have to wait for thread creation.  This shaves off
        // the lazy-initialization latency on the critical first render.
        if (executorService instanceof ThreadPoolExecutor tpe) {
            tpe.prestartAllCoreThreads();
        }
    }

    /**
     * Worker pool with layer-aware dequeue order.  All batches share one
     * executor and each queue pass submits its batches in layer order (biomes,
     * then structures, then heightmap) — but the batches of the NEXT pass land
     * behind every background batch of the earlier passes, because a plain
     * FIFO never reorders.  The background layers are far more expensive per
     * chunk than biome sampling (structure placement runs the whole placement
     * pipeline; the heightmap fast path even batches one region per batch), so
     * on large viewports the freshly exposed biome work — the only layer the
     * visible map is waiting for — could sit behind tens of seconds of queued
     * background batches: the map appeared frozen until the next drag happened
     * to cancel enough of the backlog for it to surface.  Tasks therefore
     * carry a rank (biome 0, heightmap 1, everything else 2) and the executor
     * dequeues the lowest rank first, FIFO within a rank, so a freshly queued
     * biome batch always jumps ahead of not-yet-started background batches.
     * Already-running batches are untouched (cancellation stays cooperative),
     * and background layers cannot starve: once the viewport's biome layer is
     * sampled, no new rank-0 work is queued and the background ranks drain.
     */
    private ExecutorService createWorkerExecutor() {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                config.numThreads(),
                config.numThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                Executors.defaultThreadFactory()
        ) {
            @Override
            protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
                if (runnable instanceof PrioritizedWorkTask task) {
                    return new PrioritizedFutureTask<>(task.runnable(), task.rank(), task.seq());
                }
                return super.newTaskFor(runnable, value);
            }
        };
        executor.prestartAllCoreThreads();
        return executor;
    }

    /**
     * Layer rank for the worker queue: the biome layer is what every render
     * mode displays and what the viewport fill waits on; the heightmap feeds
     * the hillshade overlay and comes next; everything else (structures,
     * intersections) is pure background.
     */
    private static int workLayerRank(long flags) {
        if (flags == PreviewStorage.FLAG_BIOME) {
            return 0;
        }
        if (flags == PreviewStorage.FLAG_HEIGHT) {
            return 1;
        }
        return 2;
    }

    /**
     * Submit wrapper carrying the layer rank and a monotonic sequence number;
     * the executor's queue orders by (rank, seq).
     */
    private record PrioritizedWorkTask(int rank, long seq, Runnable runnable) implements Runnable {
        @Override
        public void run() {
            runnable.run();
        }
    }

    /**
     * Future ordered by (rank, seq): a lower rank dequeues first and equal
     * ranks keep their submission order, so the viewport-center spiral order
     * survives within a layer.
     */
    private static final class PrioritizedFutureTask<T> extends FutureTask<T> implements Comparable<PrioritizedFutureTask<?>> {
        private final int rank;
        private final long seq;

        PrioritizedFutureTask(Runnable runnable, int rank, long seq) {
            super(runnable, null);
            this.rank = rank;
            this.seq = seq;
        }

        @Override
        public int compareTo(PrioritizedFutureTask<?> o) {
            final int c = Integer.compare(rank, o.rank);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    /**
     * Rebuilds only the stride-dependent sampling infrastructure (chunk
     * sampler and preview storage) after a zoom step crossed a sampling-stride
     * boundary, keeping everything the stride does not parameterize alive: the
     * worldgen context with its registries and noise infrastructure (by far
     * the most expensive part of a setup), the preview color data, the worker
     * pools and any world-scoped task borrowing them.
     *
     * <p>In-flight batches sample through the old sampler and write into the
     * old-density storage, so they cannot survive the swap: the epoch bump
     * makes every queued batch's write-side guard drop its results, cancelled
     * tasks exit cooperatively at their next check, and the abort flag stops a
     * mid-flight queue pass at its next layer boundary.  The flag is left set
     * on purpose — queueRangeWrapper clears it when the next pass starts.  The
     * queue handshake and dedup guards are reset so the render loop re-queues
     * the viewport against the fresh storage (empty, or disk-cached for this
     * zoom level — the cache file name is per pixelsPerChunk, so the density
     * of any restored data always matches the new stride).
     *
     * <p>Must run on the render thread (the storage load may touch screens,
     * same contract as postChangeWorldGenState).  Returns {@code false} when
     * there is nothing to rebuild in place; the caller then falls back to the
     * full teardown path.
     */
    public synchronized boolean rebuildSamplerAndStorage() {
        if (suspended || !isSetup() || sampleUtils == null || previewStorageCacheManager == null
                || worldOptions == null || dimensionType == null
                || executorService == null || queueChunksService == null) {
            return false;
        }

        sessionEpoch.incrementAndGet();
        synchronized (currentBatches) {
            currentBatches.forEach(WorkBatch::cancel);
            currentBatches.clear();
        }
        List<Future<?>> allFutures = new ArrayList<>();
        synchronized (futures) {
            allFutures.addAll(queueFutures);
            allFutures.addAll(futures);
            queueFutures.clear();
            futures.clear();
        }
        for (Future<?> f : allFutures) {
            f.cancel(false);
        }
        synchronized (queueStateLock) {
            pendingTopLeft = null;
            pendingBottomRight = null;
            queueIsRunning = false;
        }
        lastQueuedTopLeft = null;
        lastQueuedBotRight = null;

        // The storage swap invalidates any drag-tier state: the fresh
        // storage carries no coarse marks and the sampler below is
        // full-resolution.
        dragTierEpoch.incrementAndGet();
        samplingTier = new SamplingTier(
                renderSettings.samplerType.create(renderSettings.quartStride()), false, dragTierEpoch.get());

        previewStorage = previewStorageCacheManager.loadPreviewStorage(worldOptions.seed(), yMin(), yMax());
        return true;
    }

    private void shutdownExecutors() {
        if (executorService == null) {
            return;
        }

        shouldEarlyAbortQueuing = true;
        sessionEpoch.incrementAndGet();
        pendingTopLeft = null;
        pendingBottomRight = null;

        synchronized (currentBatches) {
            currentBatches.forEach(WorkBatch::cancel);
            currentBatches.clear();
        }

        List<Future<?>> allFutures = new ArrayList<>();
        synchronized (futures) {
            allFutures.addAll(queueFutures);
            allFutures.addAll(futures);
            queueFutures.clear();
            futures.clear();
        }
        for (Future<?> f : allFutures) {
            f.cancel(false);
        }

        executorService.shutdown();
        queueChunksService.shutdown();

        boolean interrupted = false;
        try {
            if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("executorService did not terminate within {}s", SHUTDOWN_TIMEOUT_SECONDS);
            }
            if (!queueChunksService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("queueChunksService did not terminate within {}s", SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            LOGGER.warn("Interrupted while awaiting executor termination");
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void cancel() {
        // Resuming from a suspended state must go through the full teardown
        // (this method), so clear the flag before anything else.
        suspended = false;
        // End any active drag session before the teardown: the sweep drops the
        // coarse marks (with the fine marks of the chunks the drag touched) so
        // the storage persisted below cannot carry coarse completions into the
        // next session, and the coarse-touched chunks re-sample on first sight.
        exitDragTier();
        shutdownExecutors();

        RuntimeException closeError = null;
        try {
            final Executor serverThreadPoolExecutor = WorldPreview.get().serverThreadPoolExecutor();
            if (worldgenContext != null) {
                try {
                    if (serverThreadPoolExecutor != null) {
                        CompletableFuture.runAsync(() -> {
                            try {
                                worldgenContext.close();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }, serverThreadPoolExecutor).get();
                    } else {
                        worldgenContext.close();
                    }
                } catch (Exception e) {
                    closeError = new RuntimeException(e);
                }
            }

            // Never persist while the drag tier is active: the storage holds
            // coarse values whose completion marks are not serialized, so a
            // reload would show blocky fill until each chunk is re-sampled.
            // After an exit the marks are already swept (synchronously, in
            // exitDragTier), so whatever the storage holds is self-healing on
            // the next viewport pass and safe to persist.
            // Also skip the store entirely when nothing was written since the
            // storage was loaded (or last saved): the on-disk file already
            // holds exactly these bytes, and rewriting it costs a full zip
            // pass plus a "Saving preview" screen flash.  The dirty snapshot
            // is advanced by writeCacheFile() only after a successful store,
            // so a skipped/failed write simply retries on the next close.
            if (previewStorageCacheManager != null && worldOptions != null
                    && !isDragTierActive()
                    && previewStorage != null && previewStorage.isDirtySinceSaved()) {
                previewStorageCacheManager.storePreviewStorage(worldOptions.seed(), previewStorage);
            }
        } finally {
            // WorkManager owns the shared preview sampler lifecycle (the context
            // relinquished ownership at setup time), so close it explicitly here
            // even if worldgenContext.close() failed.
            if (sampleUtils != null) {
                try {
                    sampleUtils.close();
                } catch (Exception e) {
                    if (closeError == null) {
                        closeError = new RuntimeException(e);
                    } else {
                        closeError.addSuppressed(e);
                    }
                }
            }

            worldOptions = null;
            levelStem = null;
            dimensionType = null;
            chunkGenerator = null;
            sampleUtils = null;
            worldgenContext = null;
            previewStorage = null;
            biomeHolderIdCache = null;
            lastQueuedTopLeft = null;
            lastQueuedBotRight = null;
            lastY = Integer.MIN_VALUE;
            // Reset drag-session state with the worldgen teardown: a fresh
            // setup must start full-resolution regardless of how the screen
            // was closed.  The display's drag-state flip only fires on
            // transitions, so a drag still held at close time would otherwise
            // leave the next session in deferred mode with no edge to clear it.
            deferLayersWhileDragging = false;
            // The teardown invalidates any drag session with it: bump the tier
            // epoch (so no in-flight coarse batch can pass a stale check) and
            // install a fresh full-resolution snapshot.
            dragTierEpoch.incrementAndGet();
            samplingTier = new SamplingTier(
                    renderSettings.samplerType.create(renderSettings.quartStride()), false, dragTierEpoch.get());
            queueIsRunning = false;
            pendingTopLeft = null;
            pendingBottomRight = null;
            synchronized (futures) {
                futures.clear();
                queueFutures.clear();
            }
            executorService = null;
            queueChunksService = null;
            previewStorageCacheManager = null;
        }

        if (closeError != null) {
            throw closeError;
        }
    }

    /**
     * Suspend sampling: stop new work, kill the worker pools, but keep the
     * worldgen state (previewStorage / worldgenContext / sampleUtils /
     * previewData / biomeHolderIdCache / sessionEpoch) intact so the preview can
     * be resumed cheaply via {@link #resumeAfterSuspend()} instead of going
     * through a full {@link #cancel()} + worldgen rebuild.
     *
     * <p>Deliberately does NOT bump {@link #sessionEpoch()}: analysis session
     * lineage, terrain export provenance and cache fingerprints stay valid
     * across the suspension.  While suspended, {@link #queueRange} is a no-op
     * (no executor), so nothing writes to the retained previewStorage.</p>
     */
    public synchronized void suspend() {
        if (executorService == null) {
            // Already in the torn-down state (cancel() ran, or a previous
            // suspend).  Just mark suspended so resume paths know to check
            // the worldgen state.
            suspended = true;
            return;
        }

        shouldEarlyAbortQueuing = true;
        pendingTopLeft = null;
        pendingBottomRight = null;

        // Cooperative early-out for units still running: they observe
        // WorkUnit#isPaused() and abort at the next boundary.
        pause();

        synchronized (currentBatches) {
            currentBatches.forEach(WorkBatch::cancel);
            currentBatches.clear();
        }

        List<Future<?>> allFutures = new ArrayList<>();
        synchronized (futures) {
            allFutures.addAll(queueFutures);
            allFutures.addAll(futures);
            queueFutures.clear();
            futures.clear();
        }
        for (Future<?> f : allFutures) {
            f.cancel(false);
        }

        executorService.shutdown();
        queueChunksService.shutdown();

        boolean interrupted = false;
        try {
            if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("suspend: executorService did not terminate within {}s", SHUTDOWN_TIMEOUT_SECONDS);
            }
            if (!queueChunksService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("suspend: queueChunksService did not terminate within {}s", SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            LOGGER.warn("Interrupted while awaiting executor termination during suspend");
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        // The queue thread is dead: reset the queueIsRunning handshake so the
        // render thread never waits on a pass that can never finish.
        queueIsRunning = false;
        executorService = null;
        queueChunksService = null;
        // Leave the drag tier with the teardown: restore the
        // full-resolution sampler and sweep the coarse marks SYNCHRONOUSLY
        // (exitDragTier) so a resumed session refines whatever the drag
        // sampled coarsely.  The display-side drag state dies with the screen
        // instance; carrying the flag over would leave the resumed session in
        // deferred mode with no edge to clear it.
        exitDragTier();
        deferLayersWhileDragging = false;
        suspended = true;
    }

    /**
     * Resume sampling after {@link #suspend()}.  The worker pools are rebuilt
     * exactly like {@link #postChangeWorldGenState()} does; the retained
     * previewStorage and worldgen context are reused as-is.
     *
     * @return false when there is no resumable worldgen state (the caller
     *         should fall back to a full settings update / rebuild).
     */
    public synchronized boolean resumeAfterSuspend() {
        suspended = false;
        if (sampleUtils == null || worldgenContext == null) {
            return false;
        }

        // Pool construction mirrors postChangeWorldGenState (fixed pool +
        // single-thread queue service, core threads pre-started).
        executorService = createWorkerExecutor();
        queueChunksService = Executors.newSingleThreadExecutor();
        if (executorService instanceof ThreadPoolExecutor tpe) {
            tpe.prestartAllCoreThreads();
        }

        shouldEarlyAbortQueuing = false;
        queueIsRunning = false;
        lastQueuedTopLeft = null;
        lastQueuedBotRight = null;
        return true;
    }

    private boolean requeueOnYOnlyChange() {
        if (config.buildFullVertChunk) {
            return false;
        }
        return true;
    }

    public void queueRange(BlockPos topLeftBlock, BlockPos bottomRightBlock) {
        final ChunkPos topLeft = new ChunkPos(topLeftBlock);
        final ChunkPos bottomRight = new ChunkPos(bottomRightBlock);
        if (executorService == null || sampleUtils == null ||
                (
                        topLeft.equals(lastQueuedTopLeft)
                        && bottomRight.equals(lastQueuedBotRight)
                        && (topLeftBlock.getY() == lastY || !requeueOnYOnlyChange())
                )
        ) {
            return;
        }

        // Only have one in queue; keep the latest requested range for drain-after-finish.
        // BUG FIX: the queueIsRunning check and the pending write must be atomic
        // against the finish path in queueRangeWrapper.  Previously the render
        // thread could observe queueIsRunning==true and then write the pending
        // range AFTER the queue thread had set queueIsRunning=false and drained
        // pending — the new range was orphaned with nobody left to run it, and
        // the display-side dedup guard made the loss sticky (the map never
        // loads at that drag position until the user pans far away).
        synchronized (queueStateLock) {
            if (queueIsRunning) {
                // Signal the current queue algorithm to hurry up and skip
                // queueing more work units / batches since they will be canceled
                // the next run anyway.
                shouldEarlyAbortQueuing = true;
                pendingTopLeft = topLeftBlock;
                pendingBottomRight = bottomRightBlock;
                return;
            }

            // Accepting a new queue pass -- clear any stale pending range.
            pendingTopLeft = null;
            pendingBottomRight = null;
        }

        // Now, that we are definitely queueing, remember the last values
        lastQueuedTopLeft = topLeft;
        lastQueuedBotRight = bottomRight;
        lastY = topLeftBlock.getY();
        synchronized (futures) {
            if (queueChunksService == null || queueChunksService.isShutdown()) {
                // Executor gone (cancel/shutdown ran): drop the dedup guard set
                // above so a later re-issue of this range is not silently
                // swallowed, and bail out before the submit can throw
                // RejectedExecutionException.
                lastQueuedTopLeft = null;
                lastQueuedBotRight = null;
                return;
            }
            try {
                queueFutures.add(queueChunksService.submit(() -> queueRangeWrapper(topLeftBlock, bottomRightBlock)));
            } catch (RejectedExecutionException e) {
                // The accept check above lost the race against the shutdown;
                // same recovery as the guard-clear path so the range can be
                // re-issued later instead of being silently swallowed.
                lastQueuedTopLeft = null;
                lastQueuedBotRight = null;
            }
        }
    }

    /**
     * Re-issues a range bypassing the last-queued dedup guard.  Used by the
     * display's viewport safety net when visible chunks are missing completed
     * sampling (lost pending handoff, failed batch, ...).
     */
    public void forceQueueRange(BlockPos topLeftBlock, BlockPos bottomRightBlock) {
        lastQueuedTopLeft = null;
        lastQueuedBotRight = null;
        queueRange(topLeftBlock, bottomRightBlock);
    }

    /**
     * Clears the last-queued dedup guard so the next {@link #queueRange} call
     * re-issues the range even when it is unchanged.  Used when the render mode
     * changes to a storage layer that may never have been sampled for the
     * current viewport: the biome layer is queued by every pass, but e.g. the
     * y-intersection layer is only written while its feature toggle is enabled.
     */
    public void resetQueuedRange() {
        lastQueuedTopLeft = null;
        lastQueuedBotRight = null;
    }

    /**
     * Deferred detail-layer queuing while the user is dragging the map: drag
     * passes queue only the viewport biome layer (the cheapest layer, and the
     * one the map renders first), so newly revealed area appears almost
     * synchronously with the cursor; the heightmap/structure layers wait for
     * drag release.  Release flips the flag back and clears the last-queued
     * dedup guard, because the drag's biome-only passes already recorded the
     * viewport range and the unchanged range must still be re-queued for its
     * deferred layers.
     */
    public void setDeferredLayersWhileDragging(boolean dragging) {
        // The drag fast-mode (deferred detail layers + coarse biome sampling)
        // is a single feature behind one switch: with dragDownsamples off,
        // drag frames behave like any other frame.
        final boolean deferred = dragging && config.dragDownsamples;
        if (deferLayersWhileDragging == deferred) {
            return;
        }
        deferLayersWhileDragging = deferred;
        if (deferred) {
            enterDragTier();
        } else {
            exitDragTier();
            resetQueuedRange();
        }
    }

    /**
     * Enters the drag-tier coarse sampling mode: one immutable snapshot swaps
     * in a single-quart sampler (one biome sample per chunk instead of
     * sixteen) marked coarse with a fresh tier epoch, so every unit the queue
     * thread binds from this snapshot is classified coarse off the SAME read
     * that handed it the coarse sampler.  In-flight batches are cancelled so
     * they cannot mix fine results into the coarse pass (chunks they already
     * completed keep their fine marks and are skipped by the queue probe).
     */
    private void enterDragTier() {
        final SamplingTier current = samplingTier;
        if (!config.dragDownsamples || !isSetup() || (current != null && current.coarse())) {
            return;
        }
        dragTierEpoch.incrementAndGet();
        synchronized (currentBatches) {
            currentBatches.forEach(WorkBatch::cancel);
        }
        samplingTier = new SamplingTier(
                RenderSettings.SamplerType.SINGLE.create(4), true, dragTierEpoch.get());
        // Stop an in-flight pass at its next layer boundary; the display's
        // next throttled drag frame re-issues the viewport with the new flags.
        shouldEarlyAbortQueuing = true;
    }

    /**
     * Leaves the drag-tier coarse sampling mode: one immutable snapshot swaps
     * the full-resolution sampler back in, every batch of the drag session is
     * invalidated (cancel + tier-epoch bump), and the coarse completion marks
     * are dropped SYNCHRONOUSLY under {@link #completedSynchro} — the same
     * lock every completion grant takes — so the sweep cannot miss a mark and
     * the queue probe re-issues the coarse-touched area for full-resolution
     * sampling on the very next pass.  A batch whose results were computed
     * for the old tier re-checks the epoch under that lock and discards
     * itself instead.  Idempotent no-op when the tier was never entered
     * (dragDownsamples disabled, or sampling not set up).
     */
    private void exitDragTier() {
        final SamplingTier current = samplingTier;
        if (current == null || !current.coarse()) {
            return;
        }
        samplingTier = new SamplingTier(
                renderSettings.samplerType.create(renderSettings.quartStride()), false, dragTierEpoch.get());
        dragTierEpoch.incrementAndGet();
        synchronized (currentBatches) {
            currentBatches.forEach(WorkBatch::cancel);
        }
        shouldEarlyAbortQueuing = true;
        final PreviewStorage storage = previewStorage;
        if (storage != null) {
            synchronized (completedSynchro) {
                storage.clearCoarseCompletions();
            }
        }
    }

    /**
     * True when no queue pass is running and every submitted batch has finished
     * (successfully, exceptionally or cancelled).  More accurate than
     * {@link #activeBatchCount()} for "is anything running": finished batches
     * stay in {@code currentBatches} until the next pass prunes them, so the
     * batch-list count conflates retained-but-done work with live work.
     */
    public boolean isIdle() {
        if (isQueueRunning()) {
            return false;
        }
        synchronized (futures) {
            for (Future<?> f : futures) {
                if (!f.isDone()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Pause in-flight preview sampling. Workers cooperatively honor {@link WorkUnit#isPaused()}.
     */
    public void pause() {
        synchronized (currentBatches) {
            for (WorkBatch batch : currentBatches) {
                for (WorkUnit unit : batch.workUnits) {
                    unit.pause();
                }
            }
        }
    }

    /**
     * Resume previously paused preview sampling.
     */
    public void resume() {
        synchronized (currentBatches) {
            for (WorkBatch batch : currentBatches) {
                for (WorkUnit unit : batch.workUnits) {
                    unit.resume();
                }
            }
        }
    }

    private void queueRangeWrapper(BlockPos topLeftBlock, BlockPos bottomRightBlock) {
        final long epochAtStart = sessionEpoch.get();
        queueIsRunning = true;
        shouldEarlyAbortQueuing = false;
        try {
            if (epochAtStart != sessionEpoch.get()) {
                return;
            }
            queueRangeReal(topLeftBlock, bottomRightBlock, epochAtStart);
        } catch (Throwable e) {
            LOGGER.error("Queue pass failed for {}, {}", topLeftBlock, bottomRightBlock, e);
            // BUG FIX: clear the dedup guard so the failed range can be
            // re-queued later.  Without this, lastQueuedTopLeft/BotRight keep
            // pointing at a range whose work never completed, and every
            // identical request is silently dropped (map never loads there).
            lastQueuedTopLeft = null;
            lastQueuedBotRight = null;
        } finally {
            // BUG FIX: clearing queueIsRunning and draining pending must be
            // atomic against queueRange's pending write (see queueStateLock).
            final BlockPos pMin;
            final BlockPos pMax;
            synchronized (queueStateLock) {
                queueIsRunning = false;
                pMin = pendingTopLeft;
                pMax = pendingBottomRight;
                pendingTopLeft = null;
                pendingBottomRight = null;
            }
            // Drain the latest pending viewport if cancel/shutdown did not bump the epoch.
            if (epochAtStart == sessionEpoch.get() && pMin != null && pMax != null) {
                queueRange(pMin, pMax);
            }
        }
    }

    public void queueRangeReal(BlockPos topLeftBlock, BlockPos bottomRightBlock) {
        queueRangeReal(topLeftBlock, bottomRightBlock, sessionEpoch.get());
    }

    /**
     * Prunes batches that have run to any exit (success, failure, cancellation
     * or stale discard) from the tracked set, and cancels + removes the
     * in-flight batches whose work units all target chunks outside the given
     * range.  Batches with at least one unit inside the range keep running —
     * their unfinished chunks are re-issued by the next queue pass through the
     * usual isChunkSampled probe, while their finished chunks simply complete.
     *
     * <p>Retained batches are additionally trimmed at unit granularity: units
     * whose chunks (or covered area) left the range, or whose y level is not
     * queued by this pass, are cancelled so the worker skips them at the next
     * unit boundary.  Without the trim, a batch kept alive by a single
     * still-visible chunk would run all of its off-screen units to completion,
     * and since the executor is FIFO those stale batches occupy the worker
     * pool ahead of the freshly queued viewport batches — a fast drag across
     * uncharted area then takes seconds longer to fill than the initial load,
     * which starts from an empty tracked set.
     *
     * <p>Pruning finished batches is what keeps the differential-cancel scheme
     * closed: a finished batch would otherwise stay in the list forever
     * (counting as active for the preload busy-check) and its chunks would
     * poison the in-flight set (a failed batch must be re-queueable; a
     * successful batch's chunks are covered by the completed-bit probe).
     */
    private void cancelBatchesOutsideRange(ChunkPos topLeft, ChunkPos bottomRight, List<Integer> queuedYs) {
        final List<WorkBatch> toCancel = new ArrayList<>();
        synchronized (currentBatches) {
            final List<WorkBatch> retained = new ArrayList<>(currentBatches.size());
            for (WorkBatch batch : currentBatches) {
                if (batch.isFinished()) {
                    continue;
                }
                if (batch.isCanceled()) {
                    // Canceled but still draining: let the next pass forget it.
                    continue;
                }
                if (batchOverlapsRange(batch, topLeft, bottomRight)) {
                    retained.add(batch);
                    for (WorkUnit unit : batch.workUnits) {
                        if (unit.isCanceled()) {
                            continue;
                        }
                        if (!unitOverlapsRange(unit, topLeft, bottomRight)
                                || (unit.flags() == PreviewStorage.FLAG_BIOME
                                    && !queuedYs.contains(unit.y()))) {
                            unit.cancel();
                        }
                    }
                } else {
                    toCancel.add(batch);
                }
            }
            if (toCancel.isEmpty() && retained.size() == currentBatches.size()) {
                return;
            }
            currentBatches.clear();
            currentBatches.addAll(retained);
        }
        // Cancel and drop futures outside the batch locks: WorkBatch.cancel and
        // Future.cancel are safe to call unlocked (flags are volatile), and the
        // workers observe them at their next cooperative check.
        for (WorkBatch batch : toCancel) {
            batch.cancel();
        }
        synchronized (futures) {
            // Cancelled batches' futures simply run out cooperatively (their
            // process() sees isCanceled and returns early); overlapping batches'
            // futures must survive. Pruning done futures keeps the tracked set
            // bounded across passes.
            futures.removeIf(Future::isDone);
        }
    }

    /**
     * True when the unit's sampled chunk area intersects the range.  Region
     * units (fast heightmap/intersection) anchor their chunk at a 16-aligned
     * corner but sample a whole {@code coverageChunks x coverageChunks} area
     * from it, so the overlap test must expand their anchor by that span — an
     * anchor just outside the viewport with most of its region inside must
     * count as overlapping, or every drag pass would cancel and re-queue the
     * region from scratch.
     */
    private static boolean unitOverlapsRange(WorkUnit unit, ChunkPos topLeft, ChunkPos bottomRight) {
        final ChunkPos c = unit.chunk();
        final int cover = unit.coverageChunks() - 1;
        return c.x + cover >= topLeft.x && c.x <= bottomRight.x
                && c.z + cover >= topLeft.z && c.z <= bottomRight.z;
    }

    private static boolean batchOverlapsRange(WorkBatch batch, ChunkPos topLeft, ChunkPos bottomRight) {
        for (WorkUnit unit : batch.workUnits) {
            if (unitOverlapsRange(unit, topLeft, bottomRight)) {
                return true;
            }
        }
        return false;
    }

    private void queueRangeReal(BlockPos topLeftBlock, BlockPos bottomRightBlock, long epochSnapshot) {
        if (epochSnapshot != sessionEpoch.get()) {
            return;
        }

        final Instant start = Instant.now();
        final ChunkPos topLeft = new ChunkPos(topLeftBlock);
        final ChunkPos bottomRight = new ChunkPos(bottomRightBlock);

        // Differential cancel: a pan only moves the viewport by a few chunks, so
        // most in-flight batches still overlap the new range. Cancelling those
        // threw away their in-progress sampling (units only receive the
        // completed bit when they run uninterrupted), so every re-queue during
        // a drag restarted nearly the whole viewport from scratch. Instead,
        // cancel only the batches whose chunks all fall outside the new range;
        // overlapping batches keep running, and the queueForLevel probe below
        // re-issues exactly the chunks those batches have not completed yet.
        // Retained batches are also trimmed at unit granularity (see
        // cancelBatchesOutsideRange): without it, batches kept alive by one
        // still-visible chunk finish their off-screen units first, and the
        // FIFO executor runs them ahead of the freshly queued viewport work.
        cancelBatchesOutsideRange(topLeft, bottomRight, collectQueuedYs(topLeftBlock.getY()));

        if (epochSnapshot != sessionEpoch.get()) {
            return;
        }

        // Calculate new batches
        final List<ChunkPos> chunks = new ArrayList<>(ChunkPos.rangeClosed(topLeft, bottomRight).toList());
        // Drag deferral: while dragging, this pass queues only the viewport
        // biome layer (the detail layers wait for drag release, see
        // setDeferredLayersWhileDragging), and the fill order runs from the
        // viewport center outward so the area under the cursor materializes
        // first instead of trailing the drag direction row by row.
        final boolean deferDetailLayers = deferLayersWhileDragging;
        if (deferDetailLayers) {
            final int centerChunkX = (topLeft.x + bottomRight.x) >> 1;
            final int centerChunkZ = (topLeft.z + bottomRight.z) >> 1;
            chunks.sort(java.util.Comparator.comparingInt((ChunkPos c) ->
                    Math.max(Math.abs(centerChunkX - c.x), Math.abs(centerChunkZ - c.z))));
        }
        int units = 0;
        // Batches created by THIS pass (for the queue log): currentBatches also
        // holds retained in-flight batches from earlier passes.
        final int batchCountBefore;
        synchronized (currentBatches) {
            batchCountBefore = currentBatches.size();
        }

        // Capability flags of the adapter the compat registry selected for this
        // chunk generator.  Consulting them is what makes mod detection
        // observable: a mod-specific adapter is only selected while its mod is
        // installed and not disabled, so an absent/disabled mod falls back to
        // the vanilla adapter, whose flags are both true.
        final caeruleusTait.world.preview.compat.ChunkGeneratorAdapter adapter =
                sampleUtils == null ? null : sampleUtils.chunkGeneratorAdapter();
        final boolean structuresSupported = adapter == null || adapter.supportsStructures();
        final boolean heightmapSupported = adapter == null || adapter.supportsHeightmap();

        // Main biomes
        if (epochSnapshot != sessionEpoch.get() || shouldEarlyAbortQueuing) {
            // Early abort: no batches were created for this range.  Clear the
            // dedup guard so the same range can be re-queued later (e.g. after
            // drag release).  Without this, lastQueuedTopLeft/BotRight would
            // still hold this range, and the dedup check in queueRange() would
            // skip it even though the work was never completed.
            lastQueuedTopLeft = null;
            lastQueuedBotRight = null;
            return;
        }
        units += queueForLevel(chunks, topLeftBlock.getY(), 4096, PreviewStorage.FLAG_BIOME, this::workUnitFactory);

        // Structures
        if (config.sampleStructures && structuresSupported && shouldContinueQueuing(epochSnapshot) && !deferDetailLayers) {
            units += queueForLevel(chunks, 0, 256, PreviewStorage.FLAG_STRUCT_START, (pos, y) -> new StructStartWorkUnit(this, sampleUtils, pos, previewData));
        }

        // Height map
        if (config.sampleHeightmap && heightmapSupported && shouldContinueQueuing(epochSnapshot) && sampleUtils.noiseGeneratorSettings() != null && !deferDetailLayers) {
            LongSet queuedChunks = new LongOpenHashSet(chunks.size());
            List<ChunkPos> heightMapChunks = new ArrayList<>(chunks.size());
            final int sectionSizeExponent = PreviewSection.SHIFT - PreviewSection.QUART_TO_SECTION_SHIFT;
            final int numChunks = PreviewSection.SECTION_SIZE >> (sectionSizeExponent - 4);
            for (ChunkPos c : chunks) {
                ChunkPos shifted = new ChunkPos((c.x >> 4) << 4, (c.z >> 4) << 4);
                if (queuedChunks.add(shifted.toLong())) {
                    heightMapChunks.add(shifted);
                }
            }
            units += queueForLevel(heightMapChunks, 0, 1, PreviewStorage.FLAG_HEIGHT, (pos, y) -> {
                final SamplingTier tier = samplingTier;
                return bindTier(new HeightmapWorkUnit(this, tier.sampler(), sampleUtils, pos, numChunks, previewData), tier);
            });
        } else if (config.sampleHeightmap && heightmapSupported && shouldContinueQueuing(epochSnapshot) && !deferDetailLayers) {
            units += queueForLevel(chunks, 0, 64, PreviewStorage.FLAG_HEIGHT, (pos, y) -> {
                final SamplingTier tier = samplingTier;
                return bindTier(new SlowHeightmapWorkUnit(this, tier.sampler(), sampleUtils, pos, previewData), tier);
            });
        }

        // Intersections
        if (config.sampleIntersections && shouldContinueQueuing(epochSnapshot) && sampleUtils.noiseGeneratorSettings() != null && !deferDetailLayers) {
            LongSet queuedChunks = new LongOpenHashSet(chunks.size());
            List<ChunkPos> intersectChunks = new ArrayList<>(chunks.size());
            final int sectionSizeExponent = PreviewSection.SHIFT - PreviewSection.QUART_TO_SECTION_SHIFT;
            final int numChunks = PreviewSection.SECTION_SIZE >> (sectionSizeExponent - 4);
            for (ChunkPos c : chunks) {
                ChunkPos shifted = new ChunkPos((c.x >> 4) << 4, (c.z >> 4) << 4);
                if (queuedChunks.add(shifted.toLong())) {
                    intersectChunks.add(shifted);
                }
            }
            units += queueForLevel(intersectChunks, 0, 1, PreviewStorage.FLAG_INTERSECT, (pos, y) -> {
                final SamplingTier tier = samplingTier;
                return bindTier(new IntersectionWorkUnit(this, tier.sampler(), sampleUtils, pos, numChunks, previewData, Y_BLOCK_STRIDE), tier);
            });
        } else if (config.sampleIntersections && shouldContinueQueuing(epochSnapshot) && !deferDetailLayers) {
            units += queueForLevel(chunks, 0, 64, PreviewStorage.FLAG_INTERSECT, (pos, y) -> {
                final SamplingTier tier = samplingTier;
                return bindTier(new SlowIntersectionWorkUnit(this, tier.sampler(), sampleUtils, pos, previewData, yMin(), yMax(), Y_BLOCK_STRIDE), tier);
            });
        }

        // Now sample adjacent levels
        if (config.backgroundSampleVertChunk && !config.buildFullVertChunk && !deferDetailLayers) {
            for (int y : genAdjacentYLevels(topLeftBlock.getY())) {
                if (shouldEarlyAbortQueuing || epochSnapshot != sessionEpoch.get()) {
                    break;
                }
                units += queueForLevel(chunks, y, 4096, PreviewStorage.FLAG_BIOME, this::workUnitFactory);
            }
        }

        /* Compression debug code
        if (units == 0) {
            List<Short> x = previewStorage.compressionStatistics();
            LOGGER.info("Compression statistics: {}", Arrays.toString(x.toArray()));
        }
         */

        final Instant end = Instant.now();
        // currentBatches holds retained in-flight batches from previous passes
        // plus the ones this pass created; report both so the log line keeps
        // meaning "how much sampling is in flight" rather than implying every
        // listed batch was just queued.
        final int retainedBatches;
        final int newBatchCount;
        synchronized (currentBatches) {
            retainedBatches = currentBatches.size();
            newBatchCount = Math.max(0, retainedBatches - batchCountBefore);
        }
        LOGGER.info(
                "Queued {} chunks for generation using {} new batches ({} in flight) [{} ms] {}",
                units,
                newBatchCount,
                retainedBatches,
                Duration.between(start, end).abs().toMillis(),
                shouldEarlyAbortQueuing ? "{early abort}" : ""
        );
    }

    private WorkUnit workUnitFactory(ChunkPos pos, int y) {
        final SamplingTier tier = samplingTier;
        final ChunkSampler sampler = tier.sampler();
        if (config.buildFullVertChunk) {
            return bindTier(new FullChunkWorkUnit(this, sampler, pos, sampleUtils, previewData, yMin(), yMax(), Y_BLOCK_STRIDE), tier);
        } else {
            return bindTier(new LayerChunkWorkUnit(this, sampler, pos, sampleUtils, previewData, y), tier);
        }
    }

    /**
     * Binds a freshly constructed unit to the sampling tier it was built
     * from: the unit's sampler and its coarse classification come from the
     * SAME immutable snapshot read, and a coarse unit freezes the snapshot's
     * epoch so its batch is discarded if the drag tier moves before the
     * results are applied.  Nothing is re-read here, so a concurrent drag
     * edge can never produce a unit that sampled through the coarse sampler
     * but is classified as fine.
     */
    private WorkUnit bindTier(WorkUnit unit, SamplingTier tier) {
        if (tier.coarse()) {
            unit.setCoarseSampling(true, tier.epoch());
        }
        return unit;
    }

    /**
     * The y levels this pass queues biome units for: the viewport layer plus,
     * with background vertical sampling enabled, its adjacent layers.
     * Full-vertical batching anchors its units at y == 0.
     */
    private List<Integer> collectQueuedYs(int y) {
        if (config.buildFullVertChunk) {
            return List.of(0);
        }
        if (!config.backgroundSampleVertChunk) {
            return List.of(y);
        }
        // While dragging, the deferred pass queues only the viewport layer;
        // the unit trim must not cancel background layers this pass did not
        // queue (they would be cancel/requeue-churned on every drag pass).
        if (deferLayersWhileDragging) {
            return List.of(y);
        }
        final List<Integer> ys = new ArrayList<>(genAdjacentYLevels(y));
        ys.add(y);
        return ys;
    }

    private boolean shouldContinueQueuing(long epochSnapshot) {
        return !shouldEarlyAbortQueuing && epochSnapshot == sessionEpoch.get();
    }

    /**
     * Chunk keys (packed) of the units in in-flight (unfinished, non-cancelled)
     * batches whose layer flag and y level match the level being queued.
     * Units of a retained batch continue sampling across a differential
     * re-queue; queueForLevel skips these chunks so the pass only submits work
     * that nothing else is doing.
     *
     * <p>The y match uses the same quantization the storage probe uses
     * ({@code QuartPos.fromBlock(probeY)}), so the in-flight set and the
     * completed-bit probe always agree on which layer a chunk belongs to.
     * Without it, a LayerChunk unit sampled for one y level would suppress
     * queuing for every other y level of the same chunk (rolling the map's
     * Y level or the background vertical-sampling loop would starve).
     *
     * <p>Finished batches are excluded: a failed batch's chunks must be
     * re-queueable by the next pass, and a successful batch's chunks are
     * already covered by the isChunkSampled probe.
     */
    private LongOpenHashSet collectInflightChunks(long flag, int probeY) {
        final LongOpenHashSet keys;
        synchronized (currentBatches) {
            if (currentBatches.isEmpty()) {
                return new LongOpenHashSet(0);
            }
            keys = new LongOpenHashSet(currentBatches.size() * 4);
            for (WorkBatch batch : currentBatches) {
                if (batch.isFinished() || batch.isCanceled()) {
                    continue;
                }
                for (WorkUnit unit : batch.workUnits) {
                    // Units cancelled by the pass trim (chunk left the range,
                    // or its y level is no longer queued) must not block
                    // re-queueing: the chunk is unsampled, and if the viewport
                    // comes back over it the next pass has to submit it again.
                    if (!unit.isCanceled() && unit.flags() == flag && unit.y() == probeY) {
                        keys.add(unit.chunk().toLong());
                    }
                }
            }
        }
        return keys;
    }

    private int queueForLevel(List<ChunkPos> chunks, int y, int maxBatchSize, long flag, BiFunction<ChunkPos, Integer, WorkUnit> workUnitFactoryFunc) {
        WorkUnit[] toQueue = new WorkUnit[chunks.size()];
        int size = 0;
        // Chunks already being sampled by a retained in-flight batch (same
        // layer, same y): a differential re-queue keeps those batches running,
        // so re-submitting their chunks would duplicate the sampling work and
        // steal throughput from the newly exposed edge chunks.  Built once per
        // level; empty (and thus never consulted) when no batches are in flight.
        final int probeY = config.buildFullVertChunk ? 0 : y;
        final LongOpenHashSet inflight = collectInflightChunks(flag, probeY);
        // No completedSynchro here: the probe below is a read-only storage
        // lookup (isChunkSampled never creates blocks or sections), and units
        // are only constructed on a probe miss, outside any lock.  The old
        // code held completedSynchro while constructing units, which itself
        // takes section monitors, so dropping the outer lock cannot introduce
        // a deadlock.  A batch completing a chunk between the probe and
        // queueing at worst causes one redundant re-sample (sampling is
        // idempotent); the secondary isCompleted() check after construction
        // covers the cheap direction of that race.
        // Note: FullChunkWorkUnit anchors its primary section at y == 0, so
        // probe at 0 when full-vertical batching is enabled.  Every other call
        // site passes y == 0 or builds layer units anchored at y.
        for (ChunkPos chunkPos : chunks) {
            // Cheap read-only probe: skip chunks whose layer was already fully
            // sampled, without constructing a WorkUnit (its constructor does
            // storage.section4: layer-map monitor + PreviewBlock monitor +
            // possible section creation).
            if (previewStorage.isChunkSampled(
                    QuartPos.fromSection(chunkPos.x),
                    QuartPos.fromBlock(probeY),
                    QuartPos.fromSection(chunkPos.z),
                    flag
            )) {
                continue;
            }
            // Skip chunks an in-flight batch of this layer is already sampling.
            if (!inflight.isEmpty() && inflight.contains(chunkPos.toLong())) {
                continue;
            }
            WorkUnit workUnit = workUnitFactoryFunc.apply(chunkPos, y);
            if (workUnit.isCompleted()) {
                continue;
            }
            toQueue[size++] = workUnit;
        }

        if (size == 0) {
            return 0;
        }

        // Viewport-center spiral sort: sample chunks nearest the viewport
        // center first, expanding outward in a square spiral pattern.  This
        // makes the map appear to load 2-3x faster from the user's perspective,
        // since the most visually important area (center) is filled first.
        //
        // Instead of a per-tile priority queue (which has O(log n) enqueue cost
        // and requires Comparable wrappers), we do a single O(n log n) sort
        // upfront with band-level randomization for load balancing.
        if (size > 2) {
            // Compute viewport center from chunk positions
            long sumX = 0, sumZ = 0;
            for (int i = 0; i < size; i++) {
                sumX += toQueue[i].chunk().x;
                sumZ += toQueue[i].chunk().z;
            }
            final int centerX = (int)(sumX / size);
            final int centerZ = (int)(sumZ / size);

            // Sort by Chebyshev distance (square ring pattern) with deterministic
            // secondary keys (X then Z) to satisfy the Java comparator contract.
            // Using random for same-band ordering violates transitivity/reflexivity
            // and causes IllegalArgumentException: Comparison method violates its
            // general contract!
            java.util.Arrays.sort(toQueue, 0, size, (a, b) -> {
                int distA = Math.max(Math.abs(a.chunk().x - centerX), Math.abs(a.chunk().z - centerZ));
                int distB = Math.max(Math.abs(b.chunk().x - centerX), Math.abs(b.chunk().z - centerZ));
                if (distA != distB) return Integer.compare(distA, distB);
                // Same band: deterministic order by X then Z for stability
                int xDiff = Integer.compare(a.chunk().x, b.chunk().x);
                if (xDiff != 0) return xDiff;
                return Integer.compare(a.chunk().z, b.chunk().z);
            });
        }

        // Batch to reduce threading overhead.
        // Old formula: Math.max(8, Math.min(maxBatchSize, size / 4096))
        // For typical previews (100-400 chunks) size/4096 = 0, so batch was
        // always 8, creating excessive small batches and scheduling overhead.
        // New formula: aim for ~2 batches per thread, with a minimum of 16
        // and a maximum of maxBatchSize.  This reduces the number of
        // Future objects and executor scheduling overhead by 2-4x.
        int batchSize = maxBatchSize == 1 ? 1 : Math.max(16, Math.min(maxBatchSize, size / Math.max(1, config.numThreads() * 2)));
        WorkBatch[] batches = new WorkBatch[batchSize == 1 ? size : (size / batchSize) + 1];
        if (batchSize > 1) {
            int batchIdx = 0;
            batches[batchIdx] = new WorkBatch(new ArrayList<>(batchSize), completedSynchro, previewData);
            for (int i = 0; i < size; ++i) {
                batches[batchIdx].workUnits.add(toQueue[i]);
                if (batches[batchIdx].workUnits.size() >= batchSize) {
                    batches[++batchIdx] = new WorkBatch(new ArrayList<>(batchSize), completedSynchro, previewData);
                }
            }
        } else {
            for (int i = 0; i < size; ++i) {
                batches[i] = new WorkBatch(List.of(toQueue[i]), completedSynchro, previewData);
            }
        }

        // Submit and store
        final long epochAtQueue = sessionEpoch.get();
        // Write-side staleness guard: batches capture the epoch at queue time
        // and refuse to write results that outlive their worldgen context.
        // Attached BEFORE submission so no task can ever run with the guard
        // uninstalled (isStale() is false while the supplier is unattached),
        // and so the submit happens-before edge publishes the supplier to
        // the worker thread.
        for (WorkBatch batch : batches) {
            batch.attachEpochGuard(sessionEpoch::get, epochAtQueue);
            // A batch carrying any drag-tier unit is invalidated together
            // with that drag session: the guard captures the tier epoch
            // frozen in the unit itself, so a batch whose units were bound
            // before the release bump is stale no matter when it is
            // submitted or finishes relative to the drag edge.  The final
            // gate is WorkBatch.process, which re-checks this guard (and the
            // worldgen epoch) under completedSynchro before applying results.
            for (WorkUnit unit : batch.workUnits) {
                if (unit.isCoarseSampling()) {
                    batch.attachTierGuard(dragTierEpoch::get, unit.tierEpoch());
                    break;
                }
            }
        }
        synchronized (futures) {
            // Guard against executor being shut down between the isShutdown()
            // check in queueRange() and this submit call.  This can happen when
            // cancel() runs on the render thread while the queue thread is in
            // the middle of queueForLevel().
            if (executorService == null || executorService.isShutdown()) {
                return 0;
            }
            for (WorkBatch batch : batches) {
                // Layer rank from the batch's first unit: biome batches (the
                // layer the visible map waits for) dequeue ahead of background
                // batches already sitting in the queue; the sequence number
                // keeps same-rank batches FIFO so the spiral order survives.
                // An empty trailing batch (size divisible by the batch size)
                // ranks as background and completes instantly.
                final long flags = batch.workUnits.isEmpty() ? 0L : batch.workUnits.get(0).flags();
                futures.add(executorService.submit(
                        new PrioritizedWorkTask(workLayerRank(flags), workerSubmitSeq.getAndIncrement(), batch::process)));
            }
        }
        synchronized (currentBatches) {
            currentBatches.addAll(Arrays.asList(batches));
            // Close the zombie window: teardown (cancel/suspend) can run
            // between this batch's submit and this addAll — it cleared the
            // list before the batch landed in it and cancelled the future, so
            // process() will never run and neither isFinished nor isCanceled
            // would ever flip.  Cancel the batch here so the next pass evicts
            // it instead of treating it as in-flight forever.
            if (executorService == null || executorService.isShutdown()) {
                for (WorkBatch batch : batches) {
                    batch.cancel();
                }
            }
        }

        return size;
    }

    private List<Integer> genAdjacentYLevels(int y) {
        final int yMin = yMin();
        final int yMax = yMax();

        final List<Integer> res = new ArrayList<>();

        final int max = dimensionType.height() / Y_BLOCK_STRIDE + 1; // Full height
        for (int i = 1; i <= max; ++i) {
            int y1 = y + i * Y_BLOCK_STRIDE;
            int y2 = y - i * Y_BLOCK_STRIDE;
            if (y2 >= yMin) {
                res.add(y2);
            }
            if (y1 <= yMax) {
                res.add(y1);
            }
            if (y1 > yMax && y2 < yMin) {
                break;
            }
        }

        return res;
    }

    public int yMin() {
        return dimensionType == null ? 0 : dimensionType.minY();
    }

    public int yMax() {
        return yMin() + (dimensionType == null ? 256 : dimensionType.height());
    }

    public PreviewStorage previewStorage() {
        return previewStorage;
    }

    public boolean isSetup() {
        return executorService != null;
    }

    /**
     * Returns the total number of batches currently queued or being processed.
     */
    public int activeBatchCount() {
        synchronized (currentBatches) {
            return (int) currentBatches.stream().filter(b -> !b.isCanceled()).count();
        }
    }

    /**
     * Returns the total number of work units in all active batches.
     */
    public int activeWorkUnitCount() {
        synchronized (currentBatches) {
            return currentBatches.stream()
                    .filter(b -> !b.isCanceled())
                    .mapToInt(b -> b.workUnits.size())
                    .sum();
        }
    }

    /**
     * Returns the number of configured sampling threads.
     */
    public int threadCount() {
        return config.numThreads();
    }

    /**
     * Returns whether the queue is currently running.
     */
    public boolean isQueueRunning() {
        return queueIsRunning;
    }

    public WorldPreviewConfig config() {
        return config;
    }

    /**
     * This resource manager can access images in datapacks, while the
     * one provided in the GUI Minecraft class can't.
     */
    public ResourceManager sampleResourceManager() {
        return sampleUtils.resourceManager();
    }

    public SampleUtils sampleUtils() {
        return sampleUtils;
    }

    /**
     * Identity cache of biome holder to biome2Id id for the current
     * {@link PreviewData}, or {@code null} while no worldgen state is active.
     * Read-only for consumers; lifecycle is owned by changeWorldGenState/cancel.
     */
    public @Nullable Map<Holder<Biome>, Short> biomeHolderIdCache() {
        return biomeHolderIdCache;
    }

    public WorldgenContext worldgenContext() {
        return worldgenContext;
    }

    /** Opens an analysis session backed by the current world sampler. */
    public AnalysisSession openAnalysisSession(AnalysisRequest request) {
        // Analysis must not observe the drag tier's coarse marks or coarse
        // values mid-session: end any active drag session first so the marks
        // are dropped and the next pass refines at full resolution.  The
        // display state dies with the screen, so clear the deferred flag too.
        exitDragTier();
        deferLayersWhileDragging = false;
        if (sampleUtils == null || previewStorage == null || worldgenContext == null) {
            throw new IllegalStateException("world generation state is not initialized");
        }
        if (request.seed() != worldgenContext.seed()) {
            throw new IllegalArgumentException("request seed does not match active preview context; use a seed-scoped context");
        }
        // Independent storage: analysis must not write into live preview storage.
        PreviewStorage analysisStorage = new PreviewStorage(
                worldgenContext.dimensionType().minY(),
                worldgenContext.dimensionType().minY() + worldgenContext.dimensionType().height()
        );
        return openAnalysisSession(request, worldgenContext, analysisStorage, false);
    }

    /** Opens an analysis session using a caller-owned, seed-specific context. */
    public AnalysisSession openAnalysisSession(AnalysisRequest request, WorldgenContext context) {
        return openAnalysisSession(request, context, true);
    }

    /**
     * Opens an analysis session.
     *
     * @param ownsContext when true, the session will close {@code context} on session close.
     *                    Live-preview sessions must pass false so WorkManager retains ownership.
     */
    public AnalysisSession openAnalysisSession(AnalysisRequest request, WorldgenContext context, boolean ownsContext) {
        if (context == null) throw new IllegalArgumentException("analysis context is unavailable");
        if (request.seed() != context.seed()) {
            throw new IllegalArgumentException("request seed does not match analysis context");
        }
        PreviewStorage storage = ownsContext
                ? new PreviewStorage(context.dimensionType().minY(), context.dimensionType().minY() + context.dimensionType().height())
                : previewStorage;
        if (storage == null) {
            storage = new PreviewStorage(context.dimensionType().minY(), context.dimensionType().minY() + context.dimensionType().height());
        }
        return openAnalysisSession(request, context, storage, ownsContext);
    }

    private AnalysisSession openAnalysisSession(AnalysisRequest request, WorldgenContext context,
                                                PreviewStorage storage) {
        // Shared live-preview context must not be closed by the analysis session.
        return openAnalysisSession(request, context, storage, context != worldgenContext);
    }

    private AnalysisSession openAnalysisSession(AnalysisRequest request, WorldgenContext context,
                                                PreviewStorage storage, boolean ownsContext) {
        if (previewData == null) {
            throw new IllegalStateException("preview data is not initialized");
        }
        TaskScheduler scheduler = new TaskScheduler(Math.max(1, config.numThreads()));
        try {
            // Live-preview sessions may reuse already-sampled biome/height facts
            // from the shared preview storage instead of recomputing them.
            AnalysisSession session = new AnalysisSession(
                    request, context, scheduler, storage, previewData, ownsContext,
                    ownsContext ? null : previewStorage);
            // Lineage: remember which worldgen epoch + identity produced this
            // session so stale results can be detected before they are used.
            session.attachOrigin(sessionEpoch.get(), context.identity().shortKey());
            return session;
        } catch (java.io.IOException error) {
            scheduler.close();
            throw new IllegalStateException("unable to create analysis sampler", error);
        }
    }

    /** Converts an existing viewport into an analysis request without changing viewport queueing.
     *
     * <p>Consumes the previously-inert config fields {@code analysisDefaultSampleStep}
     * (values &gt; 1 force a fixed sampling step in blocks; 1 keeps the zoom-adaptive
     * default) and {@code analysisMaxRegionBlocks} (caps the analyzed area so the
     * sample count stays within the configured budget).</p>
     */
    public AnalysisRequest analysisRequest(BlockPos topLeftBlock, BlockPos bottomRightBlock) {
        if (worldOptions == null || levelStem == null) {
            throw new IllegalStateException("world generation state is not initialized");
        }
        String dimension = levelStem.type().unwrapKey()
                .map(key -> key.identifier().toString())
                .orElseThrow(() -> new IllegalStateException("world dimension has no identifier"));

        // Region cap: clamp the requested area to analysisMaxRegionBlocks (block area).
        BlockPos clampedTopLeft = topLeftBlock;
        BlockPos clampedBottomRight = bottomRightBlock;
        long maxSide = (long) Math.floor(Math.sqrt(Math.max(1.0, (double) config.analysisMaxRegionBlocks)));
        long width = (long) bottomRightBlock.getX() - topLeftBlock.getX();
        long height = (long) bottomRightBlock.getZ() - topLeftBlock.getZ();
        if (width > maxSide || height > maxSide) {
            long centerX = ((long) topLeftBlock.getX() + bottomRightBlock.getX()) / 2L;
            long centerZ = ((long) topLeftBlock.getZ() + bottomRightBlock.getZ()) / 2L;
            int half = (int) Math.min(maxSide / 2L, Integer.MAX_VALUE / 4L);
            clampedTopLeft = new BlockPos((int) (centerX - half), topLeftBlock.getY(), (int) (centerZ - half));
            clampedBottomRight = new BlockPos((int) (centerX + half), topLeftBlock.getY(), (int) (centerZ + half));
            LOGGER.info("Analysis region clamped to {}x{} blocks (analysisMaxRegionBlocks={})",
                    maxSide, maxSide, config.analysisMaxRegionBlocks);
        }

        int step = config.analysisDefaultSampleStep > 1
                ? config.analysisDefaultSampleStep
                : Math.max(1, renderSettings.quartStride() * 4);
        return new AnalysisRequest(
                worldOptions.seed(),
                dimension,
                Region.of(clampedTopLeft.getX(), clampedTopLeft.getZ(), clampedBottomRight.getX(), clampedBottomRight.getZ()),
                clampedTopLeft.getY(),
                step,
                config.sampleHeightmap,
                config.sampleIntersections,
                config.storeNoiseSamples);
    }
}
