// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.FillLevel;
import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.color.BiomeIdLookup;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.domain.task.Task;
import caeruleusTait.world.preview.domain.task.TaskId;
import caeruleusTait.world.preview.domain.task.TaskProgress;
import caeruleusTait.world.preview.domain.task.TaskResult;
import caeruleusTait.world.preview.domain.task.TaskState;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public abstract class WorkUnit implements Task {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldPreview/WorkUnit");
    protected final WorkManager workManager;
    protected final SampleUtils sampleUtils;
    protected final PreviewStorage storage;
    protected final PreviewSection primarySection;
    protected final ChunkPos chunkPos;
    protected final PreviewData previewData;
    protected final int y;

    // BUG FIX: isCanceled must be volatile — it is set from the queue/manager thread
    // and read from worker threads. Without volatile, the worker thread may never
    // see the updated value due to CPU caching, causing cancelled work to continue
    // executing and wasting CPU cycles.
    private volatile boolean isCanceled;

    // Set when the unit was canceled before or during sampling, meaning its
    // collected results may be partial.  WorkBatch only grants the completed
    // bit to units that were NOT interrupted; interrupted units keep their
    // idempotent partial writes and are re-sampled in full on a later pass.
    private volatile boolean wasInterrupted;

    // Staged-fill marking: set right after construction for units bound to a
    // non-target sampling stage (the drag tier or an intermediate refinement
    // stage of the initial fill).  Such units complete on a separate section
    // bitset and freeze the tier epoch, so their batches are invalidated
    // together with that stage.  Written before the unit is published to the
    // worker pool (executor submit happens-before), so no volatile is needed.
    private FillLevel fillLevel = FillLevel.FULL;
    private boolean fillFinal = true;
    private long tierEpoch = Long.MIN_VALUE;

    /**
     * Marks this unit as belonging to a staged fill pass.
     *
     * @param level      lattice density the unit samples
     * @param finalStage true when this stage reaches the configured (target)
     *                   sampler, so its completion is a real one
     * @param tierEpoch  the stage epoch, frozen for the stale-batch guard
     */
    public void setFillStage(FillLevel level, boolean finalStage, long tierEpoch) {
        this.fillLevel = level;
        this.fillFinal = finalStage;
        this.tierEpoch = tierEpoch;
    }

    /** True when this unit was sampled by a non-target (staged) sampler. */
    public boolean isStagedSampling() {
        return !fillFinal;
    }

    /** The staged fill epoch frozen at construction ({@link Long#MIN_VALUE} for target units). */
    public long tierEpoch() {
        return tierEpoch;
    }

    // Task interface support
    private final TaskId taskId = TaskId.generate();
    private final AtomicReference<TaskState> taskState = new AtomicReference<>(TaskState.QUEUED);
    private final AtomicReference<TaskResult<?>> taskResult = new AtomicReference<>();
    private volatile boolean paused;

    protected WorkUnit(WorkManager workManager, SampleUtils sampleUtils, ChunkPos chunkPos, PreviewData previewData, int y) {
        this.workManager = Objects.requireNonNull(workManager, "workManager");
        this.sampleUtils = sampleUtils;
        this.storage = workManager.previewStorage();
        this.primarySection = storage.section4(chunkPos, y, flags());
        this.chunkPos = chunkPos;
        this.previewData = previewData;
        this.y = y;
    }

    public short biomeIdFrom(Holder<Biome> holder) {
        final short id = BiomeIdLookup.idFrom(previewData, holder, workManager.biomeHolderIdCache());
        if (id < 0) {
            LOGGER.warn("Biome not found in biome2Id map: {} - it will be rendered as black.",
                    holder.unwrapKey().map(key -> key.identifier()).orElse(null));
        }
        return id;
    }
    public short biomeIdFrom(Identifier location) {
        final short id = BiomeIdLookup.idFrom(previewData, location);
        if (id < 0) {
            LOGGER.warn("Biome not found in biome2Id map: {} — it will be rendered as black. " +
                    "This usually means the biome was not in the registry when the preview data was built.",
                    location);
        }
        return id;
    }

    /**
     * Return {@code true} on successful completion
     */
    protected abstract List<WorkResult> doWork();

    public abstract long flags();

    /**
     * Number of chunks this unit samples along each axis from its anchor
     * chunk.  Plain units cover a single chunk (the default); region units
     * (fast heightmap/intersection) cover a numChunks x numChunks area
     * starting at their 16-aligned anchor.  Used by viewport-overlap tests
     * that must not cancel a region whose anchor slid off-screen while most
     * of its area is still visible.
     */
    public int coverageChunks() {
        return 1;
    }

    public boolean isCompleted() {
        return primarySection.isCompletedAtLeast(chunkPos, fillFinal ? FillLevel.FULL : fillLevel);
    }

    public void markCompleted() {
        if (fillFinal) {
            primarySection.markCompleted(chunkPos);
        } else if (fillLevel == FillLevel.QUARTER) {
            primarySection.markCompletedQuarter(chunkPos);
        } else {
            primarySection.markCompletedCoarse(chunkPos);
        }
    }

    public List<WorkResult> work() {
        while (paused && !isCanceled) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                isCanceled = true;
                break;
            }
        }
        if (isCanceled) {
            taskState.set(TaskState.CANCELLED);
            markInterrupted();
            return List.of();
        }
        taskState.set(TaskState.RUNNING);
        try {
            List<WorkResult> result = doWork();
            if (isCanceled()) {
                // Conservative: the unit was canceled while doWork() ran, so
                // treat its results as possibly partial even if the sampling
                // loops happened to finish their final iteration.
                markInterrupted();
            }
            taskState.set(TaskState.COMPLETED);
            taskResult.set(TaskResult.success(result));
            return result;
        } catch (Throwable e) {
            // No logging here: WorkBatch is the single record point and knows
            // whether the failure is the first one in its batch.
            taskState.set(TaskState.FAILED);
            taskResult.set(TaskResult.error(e));
            throw e;
        }
    }

    public ChunkPos chunk() {
        return chunkPos;
    }

    public int y() {
        return y;
    }

    public void cancel() {
        isCanceled = true;
        if (!taskState.get().isTerminal()) {
            taskState.set(TaskState.CANCELLED);
        }
    }

    public boolean isCanceled() {
        return isCanceled;
    }

    /** Marks this unit as canceled before/during sampling; its results are partial. */
    protected final void markInterrupted() {
        wasInterrupted = true;
    }

    /** True when the unit did not run to completion, i.e. must not receive the completed bit. */
    public boolean wasInterrupted() {
        return wasInterrupted;
    }

    // ---- Task interface: isCancelled() delegates to isCanceled() ----

    @Override
    public boolean isCancelled() {
        return isCanceled;
    }

    // ---- Task interface implementation ----

    @Override
    public TaskId id() {
        return taskId;
    }

    @Override
    public TaskState state() {
        return taskState.get();
    }

    @Override
    public TaskProgress progress() {
        TaskState state = taskState.get();
        long completed = state.isTerminal() ? 1 : 0;
        return new TaskProgress(completed, 1, 0, state.name().toLowerCase());
    }

    @Override
    public Optional<TaskResult<?>> result() {
        return Optional.ofNullable(taskResult.get());
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }
}
