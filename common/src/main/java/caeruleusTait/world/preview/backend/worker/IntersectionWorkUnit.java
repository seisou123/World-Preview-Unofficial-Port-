// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
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

public class IntersectionWorkUnit extends WorkUnit {
    private final ChunkSampler sampler;
    private final int numChunks;
    private final int yStride;

    public IntersectionWorkUnit(
            WorkManager workManager,
            ChunkSampler sampler,
            SampleUtils sampleUtils,
            ChunkPos chunkPos,
            int numChunks,
            PreviewData previewData,
            int yStride
    ) {
        super(workManager, sampleUtils, chunkPos, previewData, 0);
        this.sampler = sampler;
        this.numChunks = numChunks;
        this.yStride = yStride;
    }

    @Override
    protected List<WorkResult> doWork() {
        final NoiseGeneratorSettings noiseGeneratorSettings = sampleUtils.noiseGeneratorSettings();

        if (noiseGeneratorSettings == null) {
            return List.of();
        }

        final NoiseSettings noiseSettings = noiseGeneratorSettings.noiseSettings();
        final NoiseChunk noiseChunk = sampleUtils.getNoiseChunk(chunkPos, numChunks, true);
        final BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

        final int yMin = noiseSettings.minY();
        final int yMax = yMin + noiseSettings.height();
        final int cellWidth = noiseSettings.getCellWidth();
        final int cellHeight = noiseSettings.getCellHeight();

        final int cellMinY = Mth.floorDiv(yMin, noiseSettings.getCellHeight());
        final int cellCountY = Mth.floorDiv(noiseSettings.height(), noiseSettings.getCellHeight());

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
        final short[] lastValues = new short[cap];

        final List<WorkResult> results = new ArrayList<>((yMax - yMin) / yStride);

        // Initialize the results for each y-level
        for (int y = yMin; y <= yMax; y += yStride) {
            results.add(
                    new WorkResult(
                            this,
                            QuartPos.fromBlock(y),
                            y == this.y ? primarySection : storage.section4(chunkPos, y, flags()),
                            new ArrayList<>(numChunks * numChunks * 4 * 4),
                            List.of()
                    )
            );
        }

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
                            lastValues[count] = 0;
                            ++count;
                        }
                    }

                    int lastCellY = Integer.MIN_VALUE;
                    for (int yTemp = yMin; yTemp <= yMax; yTemp += yStride) {
                        final int y = Math.min(yTemp, yMax - 1);
                        final int cellY = Math.min(Math.floorDiv(y - yMin, cellHeight), cellCountY - 1);
                        final int yInCell = Math.floorMod(y, cellHeight);
                        if (cellY != lastCellY) {
                            noiseChunk.selectCellYZ(cellY, cellZ);
                        }
                        noiseChunk.updateForY(y, (double) yInCell / (double) cellHeight);
                        lastCellY = cellY;

                        final WorkResult res = results.get((yTemp - yMin) / yStride);
                        for (int i = 0; i < count; ++i) {
                            noiseChunk.updateForX(xs[i], dXs[i]);
                            noiseChunk.updateForZ(zs[i], dZs[i]);

                            BlockState blockState = ((NoiseChunkAccessor) noiseChunk).invokeGetInterpolatedState();
                            if (blockState == null) {
                                blockState = noiseGeneratorSettings.defaultBlock();
                            }

                            short colorId = (short) blockState.getMapColor(null, null).id;
                            final short lastId = lastValues[i];
                            lastValues[i] = colorId;

                            // Allow "seeing through" one layer of air
                            if (colorId == 0 && lastId > 0) {
                                colorId = (short) -lastId;
                            }

                            mutableBlockPos.set(xs[i], yTemp, zs[i]);
                            sampler.expandRaw(mutableBlockPos, colorId, res);
                        }
                    }
                }

                // Whatever this does, but it is required...
                noiseChunk.swapSlices();
            }
        } finally {
            noiseChunk.stopInterpolation();
        }

        return results;
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_INTERSECT;
    }
}
