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
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

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

    /**
     * Chunks per sampling region.
     * <p>
     * Each region pays for a fresh sampler set and density volume, so a larger
     * span amortises that over more columns. 8 keeps the region at 128x128
     * blocks while cutting the number of regions fourfold versus 4.
     */
    private static final int CHUNK_SPAN = 8;

    /**
     * Blocks of headroom above the group's estimated surface before the scan
     * starts. The estimate is not an upper bound, so a little slack keeps most
     * groups from needing the upper-band pass; anything that still overshoots is
     * caught there and produces the same result as a full-range scan.
     */
    private static final int SURFACE_MARGIN = 32;

    private final SampleUtils sampleUtils;
    private final NoiseSettings noiseSettings;
    private final BlockState defaultBlock;
    private final int scanMinY;
    private final int scanMaxY;
    private final Predicate<BlockState> opaque = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
    /** Estimated surface level, or null when the router exposes none. */
    private final DensitySampler.Bound surfaceLevel;

    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    public NoiseHeightSampler26(SampleUtils sampleUtils, int scanMinY, int scanMaxY) {
        this.sampleUtils = sampleUtils;
        this.noiseSettings = sampleUtils.noiseGeneratorSettings() == null
                ? null : sampleUtils.noiseGeneratorSettings().noiseSettings();
        this.defaultBlock = sampleUtils.noiseGeneratorSettings() == null
                ? null : sampleUtils.noiseGeneratorSettings().defaultBlock();
        this.surfaceLevel = sampleUtils.preliminarySurfaceLevel();
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
        final int fromX = Math.max(originX, groupMinX + Math.floorMod(originX - groupMinX, step));
        final int fromZ = Math.max(originZ, groupMinZ + Math.floorMod(originZ - groupMinZ, step));
        final int columns = ((toX - fromX) / step + 1) * ((toZ - fromZ) / step + 1);
        if (columns > s.xs.length) {
            // Cannot happen with the buffer sized as it is, but dropping columns
            // one by one would leave this group half real and half estimated --
            // exactly the seam this sampler exists to remove. Skip it whole.
            return;
        }

        int count = 0;
        for (int bx = fromX; bx <= toX; bx += step) {
            for (int bz = fromZ; bz <= toZ; bz += step) {
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
        //
        // Start below the group's estimated surface so the air column above the
        // terrain is never sampled. The estimate is not guaranteed to be an upper
        // bound, so every column it undershoots is caught by the upper-band pass
        // below -- the result is identical to a full-range scan either way.
        final int surfaceStartY = Math.min(effMaxY, estimatedStartY(s, count));

        count = scanBand(s, count, finalDensity, aquifer, effMinY, surfaceStartY, out);
        if (count > 0 && surfaceStartY < effMaxY) {
            scanBand(s, count, finalDensity, aquifer, surfaceStartY + 1, effMaxY, out);
        }
    }

    /**
     * Highest estimated surface among this group's columns, plus
     * {@link #SURFACE_MARGIN}.
     * <p>
     * Samples every column. A stride would be tempting, but the column list is
     * x-major, so stepping by index only advances along Z and never reaches the
     * rest of the group -- the maximum then comes out systematically low and
     * every column needs the upper-band pass. Sampling all of them costs a few
     * thousand 2D evaluations against the tens of thousands of layers the bound
     * saves.
     */
    private int estimatedStartY(Scratch s, int count) {
        if (surfaceLevel == null || count == 0) {
            return Integer.MAX_VALUE;
        }
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < count; ++i) {
            final float v = surfaceLevel.sampleValue(s.xs[i], 0, s.zs[i]);
            if (v > max) {
                max = v;
            }
        }
        if (max == -Float.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        // Some presets (noNewCaves is shared by the nether, the caves preset and
        // floating islands) fill the surface slot with zero(), so a non-positive
        // reading means "unknown", not "the surface is at y=0". Treating it as a
        // real bound would start the scan at y=0 and push every column into the
        // upper band -- scanning the world twice.
        if (max <= 0.0f) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(max) + SURFACE_MARGIN;
    }

    /**
     * Scans the Y band {@code [fromY, toY]} top-down, writing the first opaque
     * block of every unresolved column.
     * <p>
     * Each column is fetched as a one-block-wide {@link DensityVolume} rather
     * than point by point: {@code sampleValue} rebuilds the 2x2x2 corner volume
     * and re-evaluates eight corners per call, while {@code sampleVolume}
     * evaluates the corners once and lerps the whole column incrementally. This
     * is the same path vanilla's own {@code iterateNoiseColumn} takes.
     * <p>
     * Unresolved columns are compacted to the front in a single pass, so a
     * column that was already found does not cost a copy for every later hit.
     *
     * @return how many columns are still unresolved after the band
     */
    private int scanBand(Scratch s, int count,
                         DensitySampler.Bound finalDensity, Aquifer aquifer,
                         int fromY, int toY, int[] out) {
        final int sizeY = toY - fromY + 1;
        if (sizeY <= 0) {
            return count;
        }

        int write = 0;
        for (int idx = 0; idx < count; ++idx) {
            final int x = s.xs[idx];
            final int z = s.zs[idx];
            final int outIdx = s.outIdx[idx];
            boolean resolved = false;

            final DensityVolume volume = new DensityVolume(1, sizeY, 1, x, fromY, z);
            try (ScopedDensityBuffer buffer = finalDensity.sampleVolume(volume)) {
                for (int y = toY; y >= fromY; --y) {
                    final float density = buffer.get(volume.indexUnchecked(0, y - fromY, 0));
                    BlockState state = aquifer.computeSubstance(x, y, z, density);
                    if (state == null) {
                        state = defaultBlock;
                    }
                    if (opaque.test(state)) {
                        out[outIdx] = y + 1;
                        resolved = true;
                        break;
                    }
                }
            } catch (Exception e) {
                // Leave the column unresolved: the caller tries the other band
                // and finally the estimate.
                resolved = false;
            }

            if (!resolved) {
                // write <= idx always, so this forward compaction is safe.
                s.xs[write] = x;
                s.zs[write] = z;
                s.outIdx[write] = outIdx;
                ++write;
            }
        }
        return write;
    }

    /**
     * Per-thread column buffers.
     * <p>
     * Sized for the worst case, blocksPerPixel = 1, where a region holds one
     * column per block: {@code (CHUNK_SPAN * 16)^2}. Undersizing would silently
     * drop columns -- the collection loop stops at the array length and those
     * heights would fall back to the estimate.
     */
    private static final class Scratch {
        private static final int CAP = (CHUNK_SPAN * 16) * (CHUNK_SPAN * 16);
        final int[] xs = new int[CAP];
        final int[] zs = new int[CAP];
        final int[] outIdx = new int[CAP];
    }
}
