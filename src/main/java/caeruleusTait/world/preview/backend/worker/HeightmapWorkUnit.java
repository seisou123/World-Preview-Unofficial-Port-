// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.mixin.NoiseChunkAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Fast heightmap sampling via NoiseChunk.
 *
 * <p>X/Z lattice follows the active {@link ChunkSampler} block stride (see
 * {@link caeruleusTait.world.preview.domain.preview.accuracy.HeightSampleSpec}).
 * Y is the first opaque column sample (OCEAN_FLOOR_WG-equivalent).
 * Slow path ({@link SlowHeightmapWorkUnit}) should use the same X/Z lattice.
 */
public class HeightmapWorkUnit extends WorkUnit {
    private final ChunkSampler sampler;
    private final int numChunks;

    public HeightmapWorkUnit(WorkManager workManager, ChunkSampler sampler, SampleUtils sampleUtils, ChunkPos chunkPos, int numChunks, PreviewData previewData) {
        super(workManager, sampleUtils, chunkPos, previewData, 0);
        this.sampler = sampler;
        this.numChunks = numChunks;
    }

    @Override
    protected List<WorkResult> doWork() {
        final WorkResult res = new WorkResult(this, QuartPos.fromBlock(0), primarySection, new ArrayList<>(numChunks * numChunks * 4 * 4), List.of());
        final NoiseGeneratorSettings noiseGeneratorSettings = sampleUtils.noiseGeneratorSettings();
        final WorldPreviewConfig config = workManager.config();

        if (noiseGeneratorSettings == null) {
            return List.of(res);
        }

        final NoiseSettings noiseSettings = noiseGeneratorSettings.noiseSettings();
        final NoiseChunk noiseChunk = sampleUtils.getNoiseChunk(chunkPos, numChunks, false);
        final BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

        final int cellWidth = noiseSettings.getCellWidth();
        final int cellHeight = noiseSettings.getCellHeight();

        final int minY = config.onlySampleInVisualRange ? config.heightmapMinY : noiseSettings.minY();
        final int maxY = config.onlySampleInVisualRange ? config.heightmapMaxY : minY + noiseSettings.height();
        final int cellMinY = Mth.floorDiv(minY, noiseSettings.getCellHeight());
        final int cellCountY = Mth.floorDiv(maxY - minY, noiseSettings.getCellHeight());
        final int cellOffsetY = config.onlySampleInVisualRange ? cellMinY -  Mth.floorDiv(noiseSettings.minY(), noiseSettings.getCellHeight()): 0;

        final int minBlockX = chunkPos.getMinBlockX();
        final int minBlockZ = chunkPos.getMinBlockZ();
        final int cellCountXZ = (16 * numChunks) / cellWidth;
        final int cellStrideXZ = Math.max(1, sampler.blockStride() / cellWidth);

        // Per-unit scratch buffers for the X/Z lattice (reused for every cell, no per-cell allocation).
        final int stride = Math.min(sampler.blockStride(), cellWidth);
        final int cap = Math.max(1, (cellWidth + stride - 1) / stride)
                * Math.max(1, (cellWidth + stride - 1) / stride);
        final int[] xs = new int[cap];
        final int[] zs = new int[cap];
        final double[] dXs = new double[cap];
        final double[] dZs = new double[cap];

        final Predicate<BlockState> predicate = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();

        noiseChunk.initializeForFirstCellX();

        try {
            // Iterate over cell X Z Y
            for(int cellX = 0; cellX < cellCountXZ && !isCanceled(); cellX += cellStrideXZ) {
                noiseChunk.advanceCellX(cellX);

                for(int cellZ = 0; cellZ < cellCountXZ && !isCanceled(); cellZ += cellStrideXZ) {

                    int count = 0;
                    for (int xInCell = 0; xInCell < cellWidth; xInCell += sampler.blockStride()) {
                        for (int zInCell = 0; zInCell < cellWidth; zInCell += sampler.blockStride()) {
                            xs[count] = minBlockX + cellX * cellWidth + xInCell;
                            zs[count] = minBlockZ + cellZ * cellWidth + zInCell;
                            dXs[count] = (double) xInCell / (double) cellWidth;
                            dZs[count] = (double) zInCell / (double) cellWidth;
                            ++count;
                        }
                    }

                    for(int cellY = cellCountY - 1; cellY >= 0 && count > 0 && !isCanceled(); --cellY) {
                        noiseChunk.selectCellYZ(cellY + cellOffsetY, cellZ);

                        // Iterate over block in cell Y X Z
                        for (int yInCell = cellHeight - 1; yInCell >= 0 && count > 0; --yInCell) {
                            final int y = (cellMinY + cellY) * cellHeight + yInCell;
                            noiseChunk.updateForY(y, (double) yInCell / (double) cellHeight);

                            for (int idx = 0; idx < count; ++idx) {
                                noiseChunk.updateForX(xs[idx], dXs[idx]);
                                noiseChunk.updateForZ(zs[idx], dZs[idx]);

                                BlockState blockState = ((NoiseChunkAccessor) noiseChunk).invokeGetInterpolatedState();
                                if (blockState == null) {
                                    blockState = noiseGeneratorSettings.defaultBlock();
                                }

                                if (predicate.test(blockState)) {
                                    mutableBlockPos.set(xs[idx], 0, zs[idx]);
                                    sampler.expandRaw(mutableBlockPos, (short) (y + 1), res);
                                    // Ordered compaction: shift everything right of idx one slot left
                                    // (same element order as the previous ArrayList.remove).
                                    System.arraycopy(xs, idx + 1, xs, idx, count - idx - 1);
                                    System.arraycopy(zs, idx + 1, zs, idx, count - idx - 1);
                                    System.arraycopy(dXs, idx + 1, dXs, idx, count - idx - 1);
                                    System.arraycopy(dZs, idx + 1, dZs, idx, count - idx - 1);
                                    --count;
                                    --idx;
                                }
                            }
                        }
                    }
                }

                // Whatever this does, but it is required...
                noiseChunk.swapSlices();
            }
        } finally {
            noiseChunk.stopInterpolation();
        }

        return List.of(res);
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_HEIGHT;
    }
}
