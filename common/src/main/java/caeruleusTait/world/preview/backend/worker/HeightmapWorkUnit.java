// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

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
        final SampleUtils.NoiseRegion region = sampleUtils.getNoiseRegion(chunkPos, numChunks, false);
        final DensitySampler.Bound finalDensity = region.finalDensity();
        final Aquifer aquifer = region.aquifer();
        final BlockState defaultBlock = noiseGeneratorSettings.defaultBlock();
        final BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

        // Y-scan geometry: intersect the requested range with the noise grid's
        // block extent.  26.3's compiled sampler tree owns the cell interpolation
        // and caching internally, so there is no manual cell grid to align to
        // anymore; only the effective block-Y range remains (see heightScan).
        final HeightScan scan = heightScan(
                config.heightmapMinY,
                config.heightmapMaxY,
                config.onlySampleInVisualRange,
                noiseSettings.minY(),
                noiseSettings.height());
        final int effMinY = scan.effMinY();
        final int effMaxY = scan.effMaxY();
        if (effMinY > effMaxY) {
            return List.of(res);
        }

        final int minBlockX = chunkPos.getMinBlockX();
        final int minBlockZ = chunkPos.getMinBlockZ();
        final int spanBlocks = 16 * numChunks;
        final int stride = Math.max(1, sampler.blockStride());
        final int ptsPerAxis = (spanBlocks + stride - 1) / stride;

        // The X/Z lattice from the active ChunkSampler, flattened once; points
        // are compacted out of the arrays as their columns find a height.
        final int[] xs = new int[ptsPerAxis * ptsPerAxis];
        final int[] zs = new int[ptsPerAxis * ptsPerAxis];
        int count = 0;
        for (int ix = 0; ix < ptsPerAxis; ++ix) {
            for (int iz = 0; iz < ptsPerAxis; ++iz) {
                xs[count] = minBlockX + ix * stride;
                zs[count] = minBlockZ + iz * stride;
                ++count;
            }
        }

        final Predicate<BlockState> predicate = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();

        // Descend block by block from the top of the effective range; a point
        // leaves the active set as soon as its column hits an opaque state.
        // (Aquifer here is EmptyAquifer: null density result means solid.)
        for (int y = effMaxY; y >= effMinY && count > 0 && !isCanceled(); --y) {
            for (int idx = 0; idx < count; ++idx) {
                float density = finalDensity.sampleValue(xs[idx], y, zs[idx]);
                BlockState blockState = aquifer.computeSubstance(xs[idx], y, zs[idx], density);
                if (blockState == null) {
                    blockState = defaultBlock;
                }

                if (predicate.test(blockState)) {
                    mutableBlockPos.set(xs[idx], 0, zs[idx]);
                    sampler.expandRaw(mutableBlockPos, (short) (y + 1), res);
                    // Ordered compaction: shift everything right of idx one slot left
                    // (same element order as the previous ArrayList.remove).
                    System.arraycopy(xs, idx + 1, xs, idx, count - idx - 1);
                    System.arraycopy(zs, idx + 1, zs, idx, count - idx - 1);
                    --count;
                    --idx;
                }
            }
        }

        return List.of(res);
    }

    /**
     * Pure arithmetic of the fast-path Y scan: the effective block-Y range,
     * i.e. the configured heightmap range clipped to the noise grid's block
     * extent {@code [noiseMinY, noiseMinY + noiseHeight - 1]} when
     * {@code onlySampleInVisualRange} is on (full mode scans the aligned grid
     * unchanged).  {@code effMinY > effMaxY} denotes an empty scan.  26.3's
     * compiled sampler tree removed the cell grid the old version had to align
     * to, so no cell-span arithmetic is left here.
     *
     * @param cfgMinY inclusive lower bound of the configured heightmap range
     * @param cfgMaxY inclusive upper bound of the configured heightmap range
     * @param onlyVisual value of {@code onlySampleInVisualRange}
     * @param noiseMinY bottom block Y of the noise grid
     * @param noiseHeight block height of the noise grid
     */
    record HeightScan(int effMinY, int effMaxY) {
        static final HeightScan EMPTY = new HeightScan(1, 0);
    }

    static HeightScan heightScan(int cfgMinY, int cfgMaxY, boolean onlyVisual, int noiseMinY, int noiseHeight) {
        final int gridBottomY = noiseMinY;
        final int gridTopY = noiseMinY + noiseHeight - 1;

        if (!onlyVisual) {
            return new HeightScan(gridBottomY, gridTopY);
        }

        final int effMinY = Math.max(cfgMinY, gridBottomY);
        final int effMaxY = Math.min(cfgMaxY, gridTopY);
        if (effMinY > effMaxY) {
            return HeightScan.EMPTY;
        }
        return new HeightScan(effMinY, effMaxY);
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_HEIGHT;
    }
}
