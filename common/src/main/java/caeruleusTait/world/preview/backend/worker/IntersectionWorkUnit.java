// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

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
        final SampleUtils.NoiseRegion region = sampleUtils.getNoiseRegion(chunkPos, numChunks, true);
        final DensitySampler.Bound finalDensity = region.finalDensity();
        final Aquifer aquifer = region.aquifer();
        final BlockState defaultBlock = noiseGeneratorSettings.defaultBlock();
        final BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

        final int yMin = noiseSettings.minY();
        final int yMax = yMin + noiseSettings.height();

        final int minBlockX = chunkPos.getMinBlockX();
        final int minBlockZ = chunkPos.getMinBlockZ();
        final int spanBlocks = 16 * numChunks;
        final int stride = Math.max(1, sampler.blockStride());
        final int ptsPerAxis = (spanBlocks + stride - 1) / stride;

        // The X/Z lattice from the active ChunkSampler, flattened once (no
        // per-cell scratch buffers: the compiled sampler tree handles the cell
        // grid internally now).
        final int[] xs = new int[ptsPerAxis * ptsPerAxis];
        final int[] zs = new int[ptsPerAxis * ptsPerAxis];
        final short[] lastValues = new short[ptsPerAxis * ptsPerAxis];
        int count = 0;
        for (int ix = 0; ix < ptsPerAxis; ++ix) {
            for (int iz = 0; iz < ptsPerAxis; ++iz) {
                xs[count] = minBlockX + ix * stride;
                zs[count] = minBlockZ + iz * stride;
                lastValues[count] = 0;
                ++count;
            }
        }

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

        for (int yTemp = yMin; yTemp <= yMax && !isCanceled(); yTemp += yStride) {
            final int y = Math.min(yTemp, yMax - 1);
            final WorkResult res = results.get((yTemp - yMin) / yStride);
            for (int i = 0; i < count; ++i) {
                float density = finalDensity.sampleValue(xs[i], y, zs[i]);
                BlockState blockState = aquifer.computeSubstance(xs[i], y, zs[i], density);
                if (blockState == null) {
                    blockState = defaultBlock;
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

        return results;
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_INTERSECT;
    }
}
