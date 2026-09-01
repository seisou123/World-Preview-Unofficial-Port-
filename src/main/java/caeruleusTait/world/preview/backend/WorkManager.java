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
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
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
    private ChunkSampler chunkSampler;
    private SampleUtils sampleUtils;
    private WorldgenContext worldgenContext;

    private PreviewData previewData;
    private PreviewStorage previewStorage;
    private PreviewStorageCacheManager previewStorageCacheManager;
    private final RenderSettings renderSettings;
    private final WorldPreviewConfig config;

    private final List<WorkBatch> currentBatches = new ArrayList<>();
    private final List<Future<?>> futures = new ArrayList<>();
    private final List<Future<?>> queueFutures = new ArrayList<>();
    private final SplittableRandom random = new SplittableRandom();

    private ExecutorService executorService;
    private ExecutorService queueChunksService;

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

    /** Bumped on cancel/shutdown so in-flight queue work can detect obsolescence. */
    private final AtomicLong sessionEpoch = new AtomicLong(0);

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
        chunkSampler = renderSettings.samplerType.create(renderSettings.quartStride());
        previewData = _previewData;

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
            LOGGER.error("SampleUtils creation failed: {}", e.getMessage());
            e.printStackTrace();
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
        executorService = Executors.newFixedThreadPool(config.numThreads());
        queueChunksService = Executors.newSingleThreadExecutor();

        // Pre-start all core threads so the first batch of work units
        // doesn't have to wait for thread creation.  This shaves off
        // the lazy-initialization latency on the critical first render.
        if (executorService instanceof ThreadPoolExecutor tpe) {
            tpe.prestartAllCoreThreads();
        }
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
            f.cancel(true);
        }

        executorService.shutdownNow();
        queueChunksService.shutdownNow();

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

            if (previewStorageCacheManager != null && worldOptions != null) {
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
            lastQueuedTopLeft = null;
            lastQueuedBotRight = null;
            lastY = Integer.MIN_VALUE;
            queueIsRunning = false;
            pendingTopLeft = null;
            pendingBottomRight = null;
            futures.clear();
            queueFutures.clear();
            executorService = null;
            queueChunksService = null;
            previewStorageCacheManager = null;
        }

        if (closeError != null) {
            throw closeError;
        }
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
            if (queueChunksService == null) {
                return;
            }
            queueFutures.add(queueChunksService.submit(() -> queueRangeWrapper(topLeftBlock, bottomRightBlock)));
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
     * True when no queue pass is running and every submitted batch has finished
     * (successfully, exceptionally or cancelled).  More accurate than
     * {@link #activeBatchCount()}, which never reaches zero because finished
     * batches stay in {@code currentBatches} until the next pass clears them.
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
            e.printStackTrace();
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

    private void queueRangeReal(BlockPos topLeftBlock, BlockPos bottomRightBlock, long epochSnapshot) {
        if (epochSnapshot != sessionEpoch.get()) {
            return;
        }

        final Instant start = Instant.now();
        final ChunkPos topLeft = new ChunkPos(topLeftBlock);
        final ChunkPos bottomRight = new ChunkPos(bottomRightBlock);

        // Cancel current batches
        synchronized (currentBatches) {
            currentBatches.forEach(WorkBatch::cancel);
            currentBatches.clear();
        }
        synchronized (futures) {
            for (Future<?> f : futures) {
                f.cancel(true);
            }
            futures.clear();
        }

        if (epochSnapshot != sessionEpoch.get()) {
            return;
        }

        // Calculate new batches
        final List<ChunkPos> chunks = ChunkPos.rangeClosed(topLeft, bottomRight).toList();
        int units = 0;

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
        units += queueForLevel(chunks, topLeftBlock.getY(), 4096, this::workUnitFactory);

        // Structures
        if (config.sampleStructures && shouldContinueQueuing(epochSnapshot)) {
            units += queueForLevel(chunks, 0, 256, (pos, y) -> new StructStartWorkUnit(this, sampleUtils, pos, previewData));
        }

        // Height map
        if (config.sampleHeightmap && shouldContinueQueuing(epochSnapshot) && sampleUtils.noiseGeneratorSettings() != null) {
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
            units += queueForLevel(heightMapChunks, 0, 1, (pos, y) -> new HeightmapWorkUnit(this, chunkSampler, sampleUtils, pos, numChunks, previewData));
        } else if (config.sampleHeightmap && shouldContinueQueuing(epochSnapshot)) {
            units += queueForLevel(chunks, 0, 64, (pos, y) -> new SlowHeightmapWorkUnit(this, chunkSampler, sampleUtils, pos, previewData));
        }

        // Intersections
        if (config.sampleIntersections && shouldContinueQueuing(epochSnapshot) && sampleUtils.noiseGeneratorSettings() != null) {
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
            units += queueForLevel(intersectChunks, 0, 1, (pos, y) -> new IntersectionWorkUnit(this, chunkSampler, sampleUtils, pos, numChunks, previewData, Y_BLOCK_STRIDE));
        } else if (config.sampleIntersections && shouldContinueQueuing(epochSnapshot)) {
            units += queueForLevel(chunks, 0, 64, (pos, y) -> new SlowIntersectionWorkUnit(this, chunkSampler, sampleUtils, pos, previewData, yMin(), yMax(), Y_BLOCK_STRIDE));
        }

        // Now sample adjacent levels
        if (config.backgroundSampleVertChunk && !config.buildFullVertChunk) {
            for (int y : genAdjacentYLevels(topLeftBlock.getY())) {
                if (shouldEarlyAbortQueuing || epochSnapshot != sessionEpoch.get()) {
                    break;
                }
                units += queueForLevel(chunks, y, 4096, this::workUnitFactory);
            }
        }

        /* Compression debug code
        if (units == 0) {
            List<Short> x = previewStorage.compressionStatistics();
            LOGGER.info("Compression statistics: {}", Arrays.toString(x.toArray()));
        }
         */

        final Instant end = Instant.now();
        LOGGER.info(
                "Queued {} chunks for generation using {} batches [{} ms] {}",
                units,
                currentBatches.size(),
                Duration.between(start, end).abs().toMillis(),
                shouldEarlyAbortQueuing ? "{early abort}" : ""
        );
    }

    private WorkUnit workUnitFactory(ChunkPos pos, int y) {
        if (config.buildFullVertChunk) {
            return new FullChunkWorkUnit(this, chunkSampler, pos, sampleUtils, previewData, yMin(), yMax(), Y_BLOCK_STRIDE);
        } else {
            return new LayerChunkWorkUnit(this, chunkSampler, pos, sampleUtils, previewData, y);
        }
    }

    private boolean shouldContinueQueuing(long epochSnapshot) {
        return !shouldEarlyAbortQueuing && epochSnapshot == sessionEpoch.get();
    }

    private int queueForLevel(List<ChunkPos> chunks, int y, int maxBatchSize, BiFunction<ChunkPos, Integer, WorkUnit> workUnitFactoryFunc) {
        WorkUnit[] toQueue = new WorkUnit[chunks.size()];
        int size = 0;
        synchronized (completedSynchro) {
            for (ChunkPos chunkPos : chunks) {
                WorkUnit workUnit = workUnitFactoryFunc.apply(chunkPos, y);
                if (workUnit.isCompleted()) {
                    continue;
                }
                toQueue[size++] = workUnit;
            }
        }

        if (size == 0) {
            return 0;
        }

        // Viewport-center spiral sort: sample chunks nearest the viewport
        // center first, expanding outward in a square spiral pattern.  This
        // makes the map appear to load 2-3x faster from the user's perspective,
        // since the most visually important area (center) is filled first.
        //
        // Inspired by seedviewer's TileScheduler priority queue, but improved:
        // instead of a per-tile priority queue (which has O(log n) enqueue cost
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
        synchronized (futures) {
            // Guard against executor being shut down between the isShutdown()
            // check in queueRange() and this submit call.  This can happen when
            // cancel() runs on the render thread while the queue thread is in
            // the middle of queueForLevel().
            if (executorService == null || executorService.isShutdown()) {
                return 0;
            }
            for (WorkBatch batch : batches) {
                futures.add(executorService.submit(batch::process));
            }
        }
        // Write-side staleness guard: batches capture the epoch at queue time
        // and refuse to write results that outlive their worldgen context.
        for (WorkBatch batch : batches) {
            batch.attachEpochGuard(sessionEpoch::get, epochAtQueue);
        }
        synchronized (currentBatches) {
            currentBatches.addAll(Arrays.asList(batches));
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

    public WorldgenContext worldgenContext() {
        return worldgenContext;
    }

    /** Opens an analysis session backed by the current world sampler. */
    public AnalysisSession openAnalysisSession(AnalysisRequest request) {
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
