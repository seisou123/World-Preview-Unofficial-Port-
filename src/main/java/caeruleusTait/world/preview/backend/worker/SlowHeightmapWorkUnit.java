// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.domain.preview.accuracy.HeightSampleSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Slow heightmap sampling via {@link SampleUtils#doHeightSlow}.
 *
 * <p>X/Z lattice matches {@link HeightSampleSpec} / sampler {@link ChunkSampler#blockStride()}
 * so it stays aligned with {@link HeightmapWorkUnit}.
 */
public class SlowHeightmapWorkUnit extends WorkUnit {
    private final ChunkSampler sampler;

    public SlowHeightmapWorkUnit(WorkManager workManager, ChunkSampler sampler, SampleUtils sampleUtils, ChunkPos chunkPos, PreviewData previewData) {
        super(workManager, sampleUtils, chunkPos, previewData, 0);
        this.sampler = sampler;
    }

    @Override
    protected List<WorkResult> doWork() {
        WorkResult res = new WorkResult(this, QuartPos.fromBlock(0), primarySection, new ArrayList<>(16), List.of());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] xz : HeightSampleSpec.blockPositionsInChunk(chunkPos.x, chunkPos.z, sampler.blockStride())) {
            if (isCanceled()) {
                markInterrupted();
                break;
            }
            pos.set(xz[0], y, xz[1]);
            sampler.expandRaw(pos, sampleUtils.doHeightSlow(pos), res);
        }
        return List.of(res);
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_HEIGHT;
    }
}
