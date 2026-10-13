// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.domain.preview.accuracy.StructureAnchor;
import caeruleusTait.world.preview.domain.task.TaskGroup;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public class WorkBatch {
    public final List<WorkUnit> workUnits;
    private final Object completedSynchro;
    private final PreviewData previewData;

    // BUG FIX: isCanceled must be volatile — it is set from the manager thread
    // (via cancel()) and read from the worker thread (in process()). Without
    // volatile, the worker thread may never see the cancellation flag, causing
    // cancelled batches to continue processing.
    private volatile boolean isCanceled = false;

    // Set by process() on every exit path (success, failure, cancel).  The
    // queue pass uses it to prune batches that no longer have a future:
    // differential cancel keeps overlapping batches in currentBatches, and
    // without this flag a finished (or failed) batch would stay there forever,
    // counting as active and poisoning the in-flight chunk set.
    private volatile boolean finished = false;

    // TaskGroup adapter: wraps the batch's work units for group-level operations.
    private final TaskGroup taskGroup;

    // Write-side staleness guard (attached by WorkManager): results computed
    // for a worldgen context that has since been replaced (seed/dimension/
    // generator switch or shutdown) must never be written into the new
    // context's storage.  Volatile: read from whatever thread runs the batch;
    // WorkManager attaches it before submission, whose happens-before edge
    // already publishes it - the volatile keeps that invariant local instead
    // of relying on executor semantics.
    private volatile LongSupplier epochSupplier;
    private long epochAtQueue = Long.MIN_VALUE;

    // Drag-tier staleness guard (attached by WorkManager for batches
    // carrying drag-tier coarse units): a batch that outlives its drag
    // session must not write coarse results or grant coarse completion
    // marks after the release pass has started refining.  Volatile for the
    // same reason as epochSupplier.
    private volatile LongSupplier tierSupplier;
    private long tierAtQueue = Long.MIN_VALUE;

    public WorkBatch(List<WorkUnit> workUnits, Object completedSynchro, PreviewData previewData) {
        this.workUnits = workUnits;
        this.completedSynchro = completedSynchro;
        this.previewData = previewData;
        this.taskGroup = new TaskGroup();
        for (WorkUnit unit : workUnits) {
            taskGroup.add(unit);
        }
    }

    public boolean isCanceled() {
        return isCanceled;
    }

    public void cancel() {
        isCanceled = true;
        // Delegate cancellation to all work units via TaskGroup
        taskGroup.cancelAll();
    }

    /** Attaches the write-side epoch guard. Called by WorkManager before submission. */
    public void attachEpochGuard(LongSupplier epochSupplier, long epochAtQueue) {
        this.epochSupplier = epochSupplier;
        this.epochAtQueue = epochAtQueue;
    }

    /** True when the worldgen epoch changed since this batch was queued. */
    private boolean isStale() {
        return epochSupplier != null && epochSupplier.getAsLong() != epochAtQueue;
    }

    /** Attaches the drag-tier staleness guard. Called by WorkManager before submission. */
    public void attachTierGuard(LongSupplier tierSupplier, long tierAtQueue) {
        this.tierSupplier = tierSupplier;
        this.tierAtQueue = tierAtQueue;
    }

    /** True when the drag-tier generation changed since this batch was queued. */
    private boolean isTierStale() {
        return tierSupplier != null && tierSupplier.getAsLong() != tierAtQueue;
    }

    public void process() {
        try {
            if (isCanceled()) {
                return;
            }

            // 1. Run work units collecting results; check isCanceled in loop.
            // On cancellation, break instead of returning so work that already
            // finished is not dropped: a differential re-queue cancels only
            // batches whose chunks all left the viewport, but drags still
            // re-queue every ~50ms, so dying with finished units in hand is
            // the common case, not the exception.
            // Units interrupted mid-sampling are excluded from markCompleted:
            // their partial writes are idempotent, but only fully-run units may
            // receive the completed bit ("completed => all quarts sampled").
            List<WorkResult> res = new ArrayList<>();
            List<WorkUnit> doneUnits = new ArrayList<>();
            for (WorkUnit unit : workUnits) {
                res.addAll(unit.work());
                if (!unit.wasInterrupted()) {
                    doneUnits.add(unit);
                }
                if (isCanceled()) {
                    break;
                }
            }

            if (res.isEmpty()) {
                return;
            }

            // 1.5. Discard results captured by an obsolete worldgen context.
            // Without this check a batch racing a world switch could write
            // another seed's data into the new world's storage.
            if (isStale()) {
                LOGGER.info("Discarding {} work result(s): worldgen context changed since queueing",
                        res.size());
                return;
            }

            // 1.6. Discard results from a drag session that has already
            // ended: the release pass drops the coarse completion marks and
            // re-samples at full resolution, so a late coarse batch must not
            // re-mark them.
            if (isTierStale()) {
                LOGGER.info("Discarding {} work result(s): drag sampling tier changed since queueing",
                        res.size());
                return;
            }

            // 2. Apply results and grant completions as ONE critical section
            // under completedSynchro — the same lock the drag-tier release
            // sweep takes (WorkManager.exitDragTier).  Both staleness guards
            // are re-checked INSIDE the lock and the whole write is skipped
            // when either moved: a batch preempted between the checks above
            // and this block could otherwise apply coarse values after the
            // release sweep had already run — leaving coarse fill masked
            // behind a fine completion bit that nothing re-samples.  Under the
            // lock both orders are safe: either the sweep runs after this
            // block and clears every coarse mark it granted (and the fine
            // marks of the chunks it touched), or this block observes the
            // bumped epoch and discards itself without writing anything.
            // On apply failure, do NOT mark completed so work can be retried.
            synchronized (completedSynchro) {
                if (isStale() || isTierStale()) {
                    LOGGER.info("Discarding {} work result(s): superseded before publish", res.size());
                    return;
                }
                try {
                    applyChunkResult(res);
                } catch (Exception e) {
                    LOGGER.error("Failed to apply chunk results; leaving work units incomplete for retry", e);
                    return;
                }
                doneUnits.forEach(WorkUnit::markCompleted);
            }

            // 3. Notify the render thread that new data was written
            workUnits.get(0).storage.notifyWrite();
        } catch (Exception e) {
            LOGGER.error("WorkBatch process failed", e);
        } finally {
            // Terminal on every exit path: success, failed apply (retry comes
            // from a later pass re-queueing these chunks), cancellation and
            // staleness discards.
            finished = true;
        }
    }

    /**
     * True once {@link #process()} has run to any exit (success, failure,
     * cancellation or stale discard).  A finished batch must never be treated
     * as in-flight again: its chunks are either completed (storage probe will
     * skip them) or failed (a later pass must be able to re-queue them).
     */
    public boolean isFinished() {
        return finished;
    }

    private void applyChunkResult(List<WorkResult> workResultList) {
        for (WorkResult workResult : workResultList) {
            // Skip null entries without dropping the rest of the batch
            if (workResult == null) {
                continue;
            }

            final ChunkPos chunkPos = workResult.workUnit().chunk();
            final int qStartX = QuartPos.fromSection(chunkPos.x);
            final int qStartZ = QuartPos.fromSection(chunkPos.z);

            PreviewSection section = workResult.section();
            PreviewSection.AccessData offsetData = section.calcQuartOffsetData(qStartX, qStartZ, qStartX + 4, qStartZ + 4);

            // Assume that one chunk always fits
            for (var x : workResult.results()) {
                section.set(
                        offsetData.minX() + x.quartX() - qStartX,
                        offsetData.minZ() + x.quartZ() - qStartZ,
                        x.value()
                );
            }

            for (Pair<Identifier, StructureStart> x : workResult.structures()) {
                StructureStart structureStart = x.getSecond();
                short id = previewData.struct2Id().getShort(x.getFirst().toString());
                if (id < 0) {
                    LOGGER.warn("Structure not found in struct2Id map: {} — skipping icon overlay",
                            x.getFirst());
                    continue;
                }
                BoundingBox bb = structureStart.getBoundingBox();
                BlockPos center = new BlockPos(
                        StructureAnchor.centerX(bb.minX(), bb.maxX()),
                        StructureAnchor.centerY(bb.minY(), bb.maxY()),
                        StructureAnchor.centerZ(bb.minZ(), bb.maxZ())
                );
                section.addStructure(new PreviewSection.PreviewStruct(
                        center,
                        id,
                        bb
                ));
            }
        }
    }

    /** Returns the TaskGroup backing this batch (for domain-level group operations). */
    public TaskGroup taskGroup() {
        return taskGroup;
    }
}
