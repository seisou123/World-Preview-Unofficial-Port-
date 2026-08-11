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

    // TaskGroup adapter: wraps the batch's work units for group-level operations.
    private final TaskGroup taskGroup;

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

    public void process() {
        try {
            if (isCanceled()) {
                return;
            }

            // 1. Run work units collecting results; check isCanceled in loop
            List<WorkResult> res = new ArrayList<>();
            for (WorkUnit unit : workUnits) {
                res.addAll(unit.work());
                if (isCanceled()) {
                    return;
                }
            }

            // 2. Apply results first — on failure, do NOT mark completed so work can be retried
            try {
                applyChunkResult(res);
            } catch (Exception e) {
                LOGGER.error("Failed to apply chunk results; leaving work units incomplete for retry", e);
                return;
            }

            // 3. Only mark completed after successful apply
            synchronized (completedSynchro) {
                workUnits.forEach(WorkUnit::markCompleted);
            }

            // 4. Notify the render thread that new data was written
            if (!workUnits.isEmpty()) {
                workUnits.get(0).storage.notifyWrite();
            }
        } catch (Exception e) {
            LOGGER.error("WorkBatch process failed", e);
        }
    }

    private void applyChunkResult(List<WorkResult> workResultList) {
        for (WorkResult workResult : workResultList) {
            // Skip null entries without dropping the rest of the batch
            if (workResult == null) {
                continue;
            }

            final ChunkPos chunkPos = workResult.workUnit().chunk();
            final int qStartX = QuartPos.fromSection(chunkPos.x());
            final int qStartZ = QuartPos.fromSection(chunkPos.z());

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
