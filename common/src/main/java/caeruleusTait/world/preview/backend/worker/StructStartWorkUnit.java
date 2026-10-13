// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.List;

public class StructStartWorkUnit extends WorkUnit {
    public StructStartWorkUnit(WorkManager workManager, SampleUtils sampleUtils, ChunkPos pos, PreviewData previewData) {
        super(workManager, sampleUtils, pos, previewData, 0);
    }

    @Override
    protected List<WorkResult> doWork() {
        if (isCanceled()) {
            markInterrupted();
            return List.of();
        }
        List<Pair<Identifier, StructureStart>> res = sampleUtils.doStructures(chunkPos);
        if (isCanceled()) {
            markInterrupted();
            return List.of();
        }
        return List.of(
                new WorkResult(
                        this,
                        0,
                        primarySection,
                        List.of(),
                        res
                )
        );
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_STRUCT_START;
    }
}
