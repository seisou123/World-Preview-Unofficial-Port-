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
 * Quarter-lattice refinement of a chunk that was already sampled at
 * {@link FillLevel#SINGLE}.
 *
 * <p>{@link SingleQuartSampler} sampled the chunk's (0,0) quart; this sampler
 * only samples the three remaining points of {@link QuarterQuartSampler}'s
 * 2x2 lattice and fills its own 2x2-quart cell.  The (0,0) cell keeps the
 * value the single sample already wrote — it is the same sample point — so
 * the result is identical to a fresh {@link QuarterQuartSampler} pass at
 * three quarters of the sampling cost.
 */
public class RefineQuarterSampler implements ChunkSampler {

    @Override
    public List<BlockPos> blocksForChunk(ChunkPos chunkPos, int y) {
        final int xMin = SectionPos.sectionToBlockCoord(chunkPos.x(), 0);
        final int zMin = SectionPos.sectionToBlockCoord(chunkPos.z(), 0);
        final int half = QuartPos.SIZE * 2;

        final List<BlockPos> res = new ArrayList<>(3);
        res.add(new BlockPos(xMin + half, y, zMin));
        res.add(new BlockPos(xMin, y, zMin + half));
        res.add(new BlockPos(xMin + half, y, zMin + half));
        return res;
    }

    @Override
    public void expandRaw(BlockPos pos, short raw, WorkResult result) {
        final int quartX = QuartPos.fromBlock(pos.getX());
        final int quartZ = QuartPos.fromBlock(pos.getZ());
        result.results().add(new WorkResult.BlockResult(quartX + 0, quartZ + 0, raw));
        result.results().add(new WorkResult.BlockResult(quartX + 0, quartZ + 1, raw));
        result.results().add(new WorkResult.BlockResult(quartX + 1, quartZ + 0, raw));
        result.results().add(new WorkResult.BlockResult(quartX + 1, quartZ + 1, raw));
    }

    @Override
    public int blockStride() {
        return QuartPos.SIZE * 2;
    }

    @Override
    public FillLevel level() {
        return FillLevel.QUARTER;
    }
}
