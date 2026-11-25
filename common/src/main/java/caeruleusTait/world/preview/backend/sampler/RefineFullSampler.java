// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.sampler;

import caeruleusTait.world.preview.backend.FillLevel;
import caeruleusTait.world.preview.backend.worker.WorkResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-lattice refinement of a chunk that was already sampled at
 * {@link FillLevel#QUARTER}.
 *
 * <p>{@link QuarterQuartSampler} sampled the four even quart points of the
 * chunk; this sampler only samples the twelve remaining points and fills its
 * own single quart cell.  The four cells of the quarter samples keep their
 * values — they are the same sample points — so the result is identical to a
 * fresh {@link FullQuartSampler} pass at three quarters of the sampling cost.
 */
public class RefineFullSampler implements ChunkSampler {

    @Override
    public List<BlockPos> blocksForChunk(ChunkPos chunkPos, int y) {
        final int xMin = SectionPos.sectionToBlockCoord(chunkPos.x, 0);
        final int zMin = SectionPos.sectionToBlockCoord(chunkPos.z, 0);

        final List<BlockPos> res = new ArrayList<>(12);
        for (int x = 0; x < 16; x += QuartPos.SIZE) {
            for (int z = 0; z < 16; z += QuartPos.SIZE) {
                // The four quarter points were sampled by the previous level.
                if (isQuarterPoint(x) && isQuarterPoint(z)) {
                    continue;
                }
                res.add(new BlockPos(xMin + x, y, zMin + z));
            }
        }
        return res;
    }

    private static boolean isQuarterPoint(int offset) {
        return offset % (QuartPos.SIZE * 2) == 0;
    }

    @Override
    public void expandRaw(BlockPos pos, short raw, WorkResult result) {
        result.results().add(new WorkResult.BlockResult(
                QuartPos.fromBlock(pos.getX()),
                QuartPos.fromBlock(pos.getZ()),
                raw
        ));
    }

    @Override
    public int blockStride() {
        return QuartPos.SIZE;
    }

    @Override
    public FillLevel level() {
        return FillLevel.FULL;
    }
}
