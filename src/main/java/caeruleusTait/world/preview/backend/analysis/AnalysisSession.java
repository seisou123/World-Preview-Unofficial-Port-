package caeruleusTait.world.preview.backend.analysis;

import caeruleusTait.world.preview.backend.color.BiomeIdLookup;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.backend.worker.RegionWorkUnit;
import caeruleusTait.world.preview.backend.worker.SampleUtils;
import caeruleusTait.world.preview.domain.session.Session;
import caeruleusTait.world.preview.domain.session.SessionCheckpoint;
import caeruleusTait.world.preview.domain.session.SessionState;
import caeruleusTait.world.preview.domain.task.TaskProgress;
import caeruleusTait.world.preview.domain.task.TaskScheduler;
import caeruleusTait.world.preview.domain.task.TaskState;
import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns one cancellable region analysis task and its immutable snapshots.
 *
 * <p>Now extends {@link Session} for unified lifecycle management. The
 * internal {@link AnalysisStatus} from the scheduler is synchronized to
 * the {@link SessionState} whenever the task status changes.
 */
public final class AnalysisSession extends Session {
    @FunctionalInterface
    public interface Sampler {
        Sample sample(int x, int z, int y) throws Exception;
    }

    public record Sample(short biome, short height, short[] noise, Short intersection) {
        public Sample(short biome, short height) {
            this(biome, height, null, null);
        }

        public Sample(short biome, short height, short[] noise) {
            this(biome, height, noise, null);
        }

        public Sample {
            noise = noise == null ? null : noise.clone();
        }

        @Override
        public short[] noise() {
            return noise == null ? null : noise.clone();
        }
    }

    private final AnalysisRequest request;
    private final TaskScheduler scheduler;
    private final PreviewStorage storage;
    private final MetricAggregator metrics;
    private final ProfileAnalyzer profileAnalyzer;
    private final Sampler sampler;
    private final AutoCloseable ownedResource;
    private final boolean ownsResource;
    private final long totalUnits;
    private final AtomicReference<TaskScheduler.TaskHandle> task = new AtomicReference<>();
    private volatile boolean closed;
    private volatile boolean started;

    public AnalysisSession(AnalysisRequest request, TaskScheduler scheduler,
                           PreviewStorage storage, Sampler sampler) {
        this(request, scheduler, storage, sampler, null, false);
    }

    public AnalysisSession(AnalysisRequest request, TaskScheduler scheduler,
                           PreviewStorage storage, Sampler sampler, AutoCloseable ownedResource) {
        this(request, scheduler, storage, sampler, ownedResource, ownedResource != null);
    }

    private AnalysisSession(AnalysisRequest request, TaskScheduler scheduler,
                            PreviewStorage storage, Sampler sampler,
                            AutoCloseable ownedResource, boolean ownsResource) {
        super(UUID.randomUUID().toString(), "analysis", null);
        this.request = Objects.requireNonNull(request, "request");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.ownedResource = ownedResource;
        this.ownsResource = ownsResource && ownedResource != null;
        this.totalUnits = count(request);
        this.metrics = new MetricAggregator(totalUnits * rowWidth(request), request.sampleStep());
        this.profileAnalyzer = new ProfileAnalyzer((x, z, y) -> {
            short biome = storage.getRawData4(
                    net.minecraft.core.QuartPos.fromBlock(x),
                    net.minecraft.core.QuartPos.fromBlock(y),
                    net.minecraft.core.QuartPos.fromBlock(z),
                    PreviewStorage.FLAG_BIOME);
            short height = storage.getRawData4(
                    net.minecraft.core.QuartPos.fromBlock(x), 0,
                    net.minecraft.core.QuartPos.fromBlock(z), PreviewStorage.FLAG_HEIGHT);
            return new ProfileAnalyzer.Sample(biome, height);
        });
    }

    public AnalysisSession(AnalysisRequest request, WorldgenContext context,
                           TaskScheduler scheduler, PreviewStorage storage,
                           PreviewData previewData) throws java.io.IOException {
        // Do not own the live-preview WorldgenContext; WorkManager owns it.
        this(request, context, scheduler, storage, previewData, false);
    }

    public AnalysisSession(AnalysisRequest request, WorldgenContext context,
                           TaskScheduler scheduler, PreviewStorage storage,
                           PreviewData previewData, boolean ownsContext) throws java.io.IOException {
        this(request, scheduler, storage, createContextSampler(request, context,
                Objects.requireNonNull(previewData, "previewData")), ownsContext ? context : null, ownsContext);
    }

    private static Sampler createContextSampler(AnalysisRequest request, WorldgenContext context,
                                                PreviewData previewData) {
        // Reuse SampleUtils across samples on the same worker call chain, and keep BlockPos
        // allocations to one mutable instance to cut GC pressure on large analyses.
        return (x, z, y) -> {
            SampleUtils utils = context.createSampleUtils();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
            SampleUtils.BiomeResult result = utils.doSample(pos);
            short biome = BiomeIdLookup.idFrom(previewData, result.biome());
            short height = Short.MIN_VALUE;
            Short intersection = null;
            if (request.includeHeight()) {
                height = utils.doHeightSlow(pos.set(x, 0, z));
            }
            if (request.includeIntersections()) {
                intersection = (short) utils.doIntersectionsSlow(pos.set(x, 0, z))
                        .getBlock(request.y())
                        .getMapColor(null, pos.set(x, request.y(), z)).id;
            }
            return new Sample(biome, height, result.noiseResult(), intersection);
        };
    }

    public AnalysisRequest request() {
        return request;
    }

    public AnalysisProgress progress() {
        TaskScheduler.TaskHandle handle = task.get();
        if (handle == null) {
            synchronized (metrics) {
                RegionMetrics snapshot = metrics.snapshot();
                AnalysisStatus status = started ? AnalysisStatus.CANCELLED : AnalysisStatus.QUEUED;
                String stage = started ? "cancelled" : "not started";
                return new AnalysisProgress(status, 0, totalUnits,
                        snapshot.presentSamples(), totalUnits, stage, null);
            }
        }
        TaskState state = scheduler.stateOf(handle);
        TaskProgress schedulerProgress = scheduler.snapshot(handle);
        AnalysisStatus status = toAnalysisStatus(state);
        // Sync session state with scheduler status
        syncSessionState(status);
        synchronized (metrics) {
            return new AnalysisProgress(status, schedulerProgress.completedUnits(),
                    schedulerProgress.totalUnits(), metrics.snapshot().presentSamples(),
                    Math.max(0, totalUnits - schedulerProgress.completedUnits()),
                    schedulerProgress.stage(), scheduler.errorOf(handle));
        }
    }

    public RegionMetrics result() {
        synchronized (metrics) {
            return metrics.snapshot();
        }
    }

    public ProfileResult profile(ProfileRequest request) {
        return profileAnalyzer.analyze(request);
    }

    @Override
    public synchronized void start() {
        ensureOpen();
        TaskScheduler.TaskHandle existing = task.get();
        if (existing != null) {
            TaskState status = scheduler.stateOf(existing);
            if (!status.isTerminal()) return;
            // Allow a fresh run after cancel/complete/fail.
            task.set(null);
            synchronized (metrics) {
                metrics.reset();
            }
        } else if (started) {
            // Previous run was cancelled before a handle was retained, or after terminal clear.
            synchronized (metrics) {
                metrics.reset();
            }
        }
        started = true;
        setStateDirect(SessionState.RUNNING);
        TaskScheduler.TaskHandle handle = scheduler.submit(totalUnits, (unit, cancelled) -> {
            new RegionWorkUnit(request, storage, metrics, sampler).run(unit, cancelled);
        });
        task.set(handle);
    }

    @Override
    public void pause() {
        TaskScheduler.TaskHandle handle = task.get();
        if (handle != null) scheduler.pause(handle);
        setStateDirect(SessionState.PAUSED);
    }

    @Override
    public void resume() {
        TaskScheduler.TaskHandle handle = task.get();
        if (handle != null) scheduler.resume(handle);
        setStateDirect(SessionState.RUNNING);
    }

    @Override
    public void cancel() {
        TaskScheduler.TaskHandle handle = task.get();
        if (handle != null) {
            scheduler.cancel(handle);
        }
        // No task yet, but mark session as no longer "queued/not started".
        started = true;
        setStateDirect(SessionState.CANCELLED);
    }

    public boolean isRunning() {
        TaskScheduler.TaskHandle handle = task.get();
        if (handle == null) return false;
        TaskState status = scheduler.stateOf(handle);
        return status == TaskState.RUNNING
                || status == TaskState.QUEUED
                || status == TaskState.PENDING_START
                || status == TaskState.PAUSED;
    }

    private static AnalysisStatus toAnalysisStatus(TaskState state) {
        return switch (state) {
            case QUEUED, PENDING_START -> AnalysisStatus.QUEUED;
            case RUNNING -> AnalysisStatus.RUNNING;
            case PAUSED -> AnalysisStatus.PAUSED;
            case COMPLETED -> AnalysisStatus.COMPLETED;
            case CANCELLED -> AnalysisStatus.CANCELLED;
            case FAILED -> AnalysisStatus.FAILED;
        };
    }

    /**
     * Synchronizes the Session state with the TaskScheduler status.
     */
    private void syncSessionState(AnalysisStatus analysisStatus) {
        SessionState sessionState = switch (analysisStatus) {
            case QUEUED, RUNNING -> SessionState.RUNNING;
            case PAUSED -> SessionState.PAUSED;
            case COMPLETED -> SessionState.COMPLETED;
            case CANCELLED -> SessionState.CANCELLED;
            case FAILED -> SessionState.FAILED;
        };
        if (state() != sessionState) {
            setStateDirect(sessionState);
        }
    }

    @Override
    public SessionCheckpoint checkpoint() {
        AnalysisProgress p = progress();
        return new SessionCheckpoint(
                id(),
                state(),
                p.completedUnits(),
                p.totalUnits(),
                p.stage(),
                p.error() != null ? java.util.List.of(p.error()) : java.util.List.of(),
                System.currentTimeMillis()
        );
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            cancel();
            try {
                scheduler.close();
            } catch (RuntimeException ignored) {
                // Never block screen transitions on scheduler shutdown.
            }
            if (ownsResource && ownedResource != null) {
                try {
                    ownedResource.close();
                } catch (Exception error) {
                    // Never block screen transitions on resource cleanup.
                }
            }
        }
        super.close();
    }

    PreviewStorage storage() {
        return storage;
    }

    private static long count(AnalysisRequest request) {
        return coordinateCount(request.region().minZ(), request.region().maxZ(), request.sampleStep());
    }

    private static long rowWidth(AnalysisRequest request) {
        return coordinateCount(request.region().minX(), request.region().maxX(), request.sampleStep());
    }

    private static long coordinateCount(int min, int max, int step) {
        return RegionWorkUnit.coordinateCount(min, max, step);
    }
}
