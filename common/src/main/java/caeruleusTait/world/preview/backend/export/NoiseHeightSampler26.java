// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.export;

import caeruleusTait.world.preview.backend.worker.SampleUtils;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Real surface heights for terrain export on the 26.3 line.
 * <p>
 * Export used to read heights only from the live preview storage, which covers
 * the viewport and its preload margin -- 2048x2048 blocks at most. Any export
 * radius beyond that left three quarters of the map on the biome-based
 * estimate ({@code TerrainClassifier.categoryHeight}), a nine-step constant per
 * category, so the contours drawn on it traced biome borders rather than
 * terrain, and the boundary between the two sources showed up as a hard
 * rectangular seam.
 * <p>
 * 26.3 replaced the noise chunk's manual cell traversal with a compiled sampler
 * tree, so this variant evaluates the final-density bound plus the aquifer
 * directly ({@link SampleUtils#getNoiseRegion}), the same pair the preview's own
 * heightmap unit uses. One instance is shared by all export workers, so mutable
 * state lives in a {@link ThreadLocal}.
 */
public final class NoiseHeightSampler26 implements TerrainMapExporter.BatchHeightProbe {

    /** Chunks per sampling region. 4 keeps the density volume at 64x64 columns. */
    private static final int CHUNK_SPAN = 4;

    private final SampleUtils sampleUtils;
    private final NoiseSettings noiseSettings;
    private final BlockState defaultBlock;
    private final int scanMinY;
    private final int scanMaxY;
    private final Predicate<BlockState> opaque = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();

    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    public NoiseHeightSampler26(SampleUtils sampleUtils, int scanMinY, int scanMaxY) {
        this.sampleUtils = sampleUtils;
        this.noiseSettings = sampleUtils.noiseGeneratorSettings() == null
                ? null : sampleUtils.noiseGeneratorSettings().noiseSettings();
        this.defaultBlock = sampleUtils.noiseGeneratorSettings() == null
                ? null : sampleUtils.noiseGeneratorSettings().defaultBlock();
        this.scanMinY = scanMinY;
        this.scanMaxY = scanMaxY;
    }

    /** Whether the sampler was given settings it can actually use. */
    public boolean usable() {
        return noiseSettings != null && defaultBlock != null;
    }

    @Override
    public void fillGrid(int originX, int originZ, int step, int width, int height, int[] out,
                         BooleanSupplier cancelled) throws Exception {
        if (!usable()) {
            return;
        }
        final int maxX = originX + (width - 1) * step;
        final int maxZ = originZ + (height - 1) * step;

        final int groupBlocks = CHUNK_SPAN * 16;
        final int firstGroupX = Math.floorDiv(originX, groupBlocks);
        final int lastGroupX = Math.floorDiv(maxX, groupBlocks);
        final int firstGroupZ = Math.floorDiv(originZ, groupBlocks);
        final int lastGroupZ = Math.floorDiv(maxZ, groupBlocks);

        final Scratch s = scratch.get();
        for (int gx = firstGroupX; gx <= lastGroupX; gx++) {
            for (int gz = firstGroupZ; gz <= lastGroupZ; gz++) {
                if (cancelled.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException("Terrain export cancelled");
                }
                scanGroup(s, gx * groupBlocks, gz * groupBlocks,
                        originX, originZ, step, width, height, maxX, maxZ, out);
            }
        }
    }

    /**
     * Single-column queries are not supported: one column would pay the whole
     * sampler setup per pixel. Returning null keeps the caller on the estimate.
     */
    @Override
    public @org.jetbrains.annotations.Nullable Integer heightAt(int blockX, int blockZ) {
        return null;
    }

    private void scanGroup(Scratch s,
                           int groupMinX, int groupMinZ,
                           int originX, int originZ,
                           int step, int width, int height, int maxX, int maxZ,
                           int[] out) {
        final int groupMaxX = groupMinX + CHUNK_SPAN * 16 - 1;
        final int groupMaxZ = groupMinZ + CHUNK_SPAN * 16 - 1;
        final int toX = Math.min(maxX, groupMaxX);
        final int toZ = Math.min(maxZ, groupMaxZ);
        if (Math.max(originX, groupMinX) > toX || Math.max(originZ, groupMinZ) > toZ) {
            return;
        }

        // Collect every requested column inside this group.
        int count = 0;
        final int fromX = Math.max(originX, groupMinX + Math.floorMod(originX - groupMinX, step));
        final int fromZ = Math.max(originZ, groupMinZ + Math.floorMod(originZ - groupMinZ, step));
        for (int bx = fromX; bx <= toX; bx += step) {
            for (int bz = fromZ; bz <= toZ; bz += step) {
                if (count == s.xs.length) {
                    break;
                }
                s.xs[count] = bx;
                s.zs[count] = bz;
                s.outIdx[count] = ((bz - originZ) / step) * width + ((bx - originX) / step);
                ++count;
            }
        }
        if (count == 0) {
            return;
        }

        // Effective block-Y range, clipped to the dimension the noise settings cover.
        final int gridBottomY = noiseSettings.minY();
        final int gridTopY = noiseSettings.minY() + noiseSettings.height() - 1;
        final int effMinY = Math.max(scanMinY, gridBottomY);
        final int effMaxY = Math.min(scanMaxY, gridTopY);
        if (effMinY > effMaxY) {
            return;
        }

        final SampleUtils.NoiseRegion region;
        try {
            region = sampleUtils.getNoiseRegion(
                    new ChunkPos(groupMinX >> 4, groupMinZ >> 4), CHUNK_SPAN, false);
        } catch (Exception e) {
            return;
        }
        final DensitySampler.Bound finalDensity = region.finalDensity();
        final Aquifer aquifer = region.aquifer();

        // Descend layer by layer: a column leaves the active set as soon as it
        // hits an opaque block, which is the same OCEAN_FLOOR_WG contract the
        // preview's heightmap layer uses.
        for (int y = effMaxY; y >= effMinY && count > 0; --y) {
            for (int idx = 0; idx < count; ++idx) {
                final float density = finalDensity.sampleValue(s.xs[idx], y, s.zs[idx]);
                BlockState state = aquifer.computeSubstance(s.xs[idx], y, s.zs[idx], density);
                if (state == null) {
                    state = defaultBlock;
                }

                if (opaque.test(state)) {
                    out[s.outIdx[idx]] = y + 1;
                    final int tail = count - idx - 1;
                    if (tail > 0) {
                        System.arraycopy(s.xs, idx + 1, s.xs, idx, tail);
                        System.arraycopy(s.zs, idx + 1, s.zs, idx, tail);
                        System.arraycopy(s.outIdx, idx + 1, s.outIdx, idx, tail);
                    }
                    --count;
                    --idx;
                }
            }
        }
    }

    /** Per-thread column buffers; a group holds at most (64/step)^2 columns. */
    private static final class Scratch {
        final int[] xs = new int[64 * 64];
        final int[] zs = new int[64 * 64];
        final int[] outIdx = new int[64 * 64];
    }
}
