package caeruleusTait.world.preview.backend.export;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.Nullable;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

/**
 * Async terrain export controller.
 * <p>
 * Manages the export task lifecycle (idle -> running -> completed/cancelled/failed)
 * and provides thread-safe state snapshots for UI polling.
 * </p>
 * <p>
 * Unlike the TFC version ({@code LandWaterExportController}):
 * <ul>
 *   <li>TFC supports batch preset exports; this controller handles single configurable exports</li>
 *   <li>TFC uses a dedicated coordination thread; this uses {@link CompletableFuture} + single-thread executor</li>
 *   <li>TFC has a CANCELLING intermediate phase; this uses a simpler state machine</li>
 * </ul>
 * </p>
 */
public final class TerrainExportController implements AutoCloseable {

    private final TerrainMapExporter exporter;
    private final ExecutorService coordinator;
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final AtomicLong completedPixels = new AtomicLong();

    private volatile State state = State.IDLE;
    private volatile long totalPixels;
    private volatile long startedNanos;
    private volatile long finishedNanos;
    @Nullable private volatile Path outputPath;
    @Nullable private volatile String errorMessage;
    @Nullable private volatile CompletableFuture<TerrainMapExporter.Result> future;

    public TerrainExportController(int workerThreads) {
        this.exporter = new TerrainMapExporter(workerThreads);
        this.coordinator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wp-terrain-export-coordinator");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start a terrain export task with an optional real-height probe and world
     * lineage for the metadata. {@code yMin}/{@code yMax} bound the dimension's
     * world Y range and anchor the exported height field. Returns false if a
     * task is already running.
     */
    public synchronized boolean start(
            TerrainExportSpec spec,
            TerrainMapExporter.BiomeSampler sampler,
            @Nullable TerrainMapExporter.HeightProbe heightProbe,
            @Nullable TerrainMapExporter.ExportContext exportContext,
            int yMin,
            int yMax,
            Path outputDir
    ) {
        if (state == State.RUNNING) {
            return false;
        }

        cancelRequested.set(false);
        completedPixels.set(0L);
        totalPixels = spec.totalWork();
        startedNanos = System.nanoTime();
        finishedNanos = 0L;
        outputPath = outputDir;
        errorMessage = null;
        state = State.RUNNING;

        future = CompletableFuture.supplyAsync(() -> {
            try {
                return exporter.export(
                        spec,
                        sampler,
                        heightProbe,
                        exportContext,
                        yMin,
                        yMax,
                        outputDir,
                        "",
                        cancelRequested::get,
                        completedPixels::addAndGet
                );
            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, coordinator);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof CancellationException) {
                    state = State.CANCELLED;
                } else {
                    errorMessage = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                    state = State.FAILED;
                    LOGGER.error("Terrain export failed", cause);
                }
            } else {
                outputPath = result.pngPath();
                state = State.COMPLETED;
            }
            finishedNanos = System.nanoTime();
        });

        return true;
    }

    /**
     * Request cancellation of the current export task.
     */
    public synchronized void cancel() {
        if (state != State.RUNNING) {
            return;
        }
        cancelRequested.set(true);
    }

    /**
     * Get the current state snapshot.
     */
    public Status status() {
        long now = (finishedNanos == 0L) ? System.nanoTime() : finishedNanos;
        long elapsed = (startedNanos == 0L) ? 0L : Math.max(0L, now - startedNanos);
        long completed = Math.min(completedPixels.get(), totalPixels);

        long remainingNanos = -1L;
        if (state == State.RUNNING && completed > 0L && completed < totalPixels) {
            remainingNanos = (long) ((double) elapsed * (totalPixels - completed) / completed);
        }

        double pct = totalPixels <= 0L ? 0.0 : 100.0 * completed / totalPixels;

        return new Status(state, completed, totalPixels, pct, elapsed, remainingNanos, outputPath, errorMessage);
    }

    /**
     * Whether an export task is currently running.
     */
    public boolean isRunning() {
        return state == State.RUNNING;
    }

    @Override
    public void close() {
        cancel();
        coordinator.shutdownNow();
    }

    // ===== Internal types =====

    /** Controller state. */
    public enum State {
        IDLE,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    /** Immutable state snapshot. */
    public record Status(
            State state,
            long completedPixels,
            long totalPixels,
            double percentage,
            long elapsedNanos,
            long estimatedRemainingNanos,
            @Nullable Path outputPath,
            @Nullable String errorMessage
    ) {}
}
