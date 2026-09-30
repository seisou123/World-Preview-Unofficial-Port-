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

        // Y-scan geometry: intersect the requested range with the noise cell
        // grid and include the topmost partial cell.  The pre-fix arithmetic
        // (cellCountY = floorDiv(maxY - minY, cellHeight)) dropped that cell,
        // so surfaces sitting in its band were never sampled and heights were
        // reported too low.  See {@link #cellScan}.
        final CellScan scan = cellScan(
                config.heightmapMinY,
                config.heightmapMaxY,
                config.onlySampleInVisualRange,
                noiseSettings.minY(),
                noiseSettings.height(),
                cellHeight);
        final int cellMinY = scan.cellStart();
        final int cellCountY = scan.cellCount();
        final int cellOffsetY = scan.cellOffset();
        final int effMinY = scan.effMinY();
        final int effMaxY = scan.effMaxY();

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
                            if (y < effMinY || y > effMaxY) {
                                // The cell straddles the effective range boundary;
                                // this y lies outside the requested band.
                                continue;
                            }
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

    /**
     * Pure arithmetic of the fast-path Y scan: the effective block-Y range plus
     * the cell span covering it on the NoiseChunk cell grid.
     *
     * <p>Vanilla {@code NoiseChunk} builds its grid with
     * {@code cellCountY = floorDiv(noiseHeight, cellHeight)} and
     * {@code cellNoiseMinY = floorDiv(noiseMinY, cellHeight)}; grid cell 0 is
     * the cell containing {@code noiseMinY} and {@code selectCellYZ} indexes the
     * slice arrays directly, so a cell index outside {@code [0, cellCountY)}
     * would throw.  The effective range is therefore clamped to the grid's block
     * extent before the cell span is derived, and the span is computed from
     * {@code floorDiv(effMaxY, cellHeight)} so the topmost PARTIAL cell (one
     * whose top lies above {@code effMaxY}) is still scanned; the per-y skip in
     * {@link #doWork} discards the overshoot inside it.  Full mode (visual off)
     * returns the aligned grid unchanged, which is exactly what the pre-fix
     * code scanned there.
     *
     * @param cfgMinY inclusive lower bound of the configured heightmap range
     * @param cfgMaxY inclusive upper bound of the configured heightmap range
     * @param onlyVisual value of {@code onlySampleInVisualRange}
     * @param noiseMinY bottom block Y of the noise grid
     * @param noiseHeight block height of the noise grid
     * @param cellHeight noise cell height in blocks
     */
    record CellScan(int cellStart, int cellCount, int cellOffset, int effMinY, int effMaxY) {
        static final CellScan EMPTY = new CellScan(0, 0, 0, 1, 0);
    }

    static CellScan cellScan(int cfgMinY, int cfgMaxY, boolean onlyVisual, int noiseMinY, int noiseHeight, int cellHeight) {
        final int gridCellStart = Mth.floorDiv(noiseMinY, cellHeight);
        final int gridCellCount = Mth.floorDiv(noiseHeight, cellHeight);
        final int gridBottomY = gridCellStart * cellHeight;
        final int gridTopY = (gridCellStart + gridCellCount) * cellHeight - 1;

        if (!onlyVisual) {
            return new CellScan(gridCellStart, gridCellCount, 0, gridBottomY, gridTopY);
        }

        final int effMinY = Math.max(cfgMinY, gridBottomY);
        final int effMaxY = Math.min(cfgMaxY, gridTopY);
        if (effMinY > effMaxY) {
            return CellScan.EMPTY;
        }

        final int cellStart = Mth.floorDiv(effMinY, cellHeight);
        final int cellEnd = Mth.floorDiv(effMaxY, cellHeight);
        return new CellScan(cellStart, cellEnd - cellStart + 1, cellStart - gridCellStart, effMinY, effMaxY);
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_HEIGHT;
    }
}
