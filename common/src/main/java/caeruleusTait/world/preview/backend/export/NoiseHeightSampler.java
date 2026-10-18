// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.export;

import caeruleusTait.world.preview.backend.stubs.EmptyAquifer;
import caeruleusTait.world.preview.mixin.NoiseBasedChunkGeneratorAccessor;
import caeruleusTait.world.preview.mixin.NoiseChunkAccessor;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Real surface heights for terrain export, computed straight from the
 * worldgen noise tree.
 * <p>
 * Export used to read heights only from the live preview storage, which covers
 * the viewport and its preload margin -- 2048x2048 blocks at most. Any export
 * radius beyond that left three quarters of the map on the biome-based
 * estimate ({@code TerrainClassifier.categoryHeight}), a nine-step constant
 * per category, so the contours drawn on it traced biome borders rather than
 * terrain, and the boundary between the two sources showed up as a hard
 * rectangular seam.
 * <p>
 * This sampler removes the dependency on what the viewport happened to sample:
 * every column in the export is evaluated the same way the preview's own
 * heightmap layer evaluates it (OCEAN_FLOOR_WG over the noise chunk cell grid).
 * <p>
 * One instance is shared by all export worker threads, so every mutable piece
 * of state lives in a {@link ThreadLocal}: a NoiseChunk cannot be used from
 * two threads and is expensive enough that per-call construction would
 * dominate.
 */
public final class NoiseHeightSampler implements TerrainMapExporter.BatchHeightProbe {

    /**
     * Chunks per NoiseChunk.
     * <p>
     * A NoiseChunk's setup cost (interpolator arrays, router wrapping, aquifer
     * stub) is fixed per instance, so a larger span amortises it over more
     * columns: 4 chunks meant one construction per 64x64 blocks, i.e. 16k
     * constructions for a full-radius export. 8 halves that at the cost of a
     * four-times-larger interpolator grid, which stays well inside a few MB per
     * worker thread.
     */
    private static final int CHUNK_SPAN = 8;

    /**
     * Blocks of headroom above a group's highest preliminary surface level
     * before the downward scan starts. The bound is an estimate, so a little
     * slack keeps most groups from needing the upper-band rescan; anything that
     * still overshoots is caught by that rescan and produces the same result as
     * a full-range scan.
     */
    private static final int SURFACE_MARGIN = 32;

    /**
     * Whether to take {@code NoiseChunk}'s cell-wide interpolation cache out of
     * the sampling path.
     * <p>
     * It is worth roughly 1.6-1.9x at the default four blocks per pixel, but it
     * relies on three members of {@code NoiseChunk} being reachable and on the
     * final density being a single interpolated node. Flip this to false if an
     * export ever comes out wrong on an unfamiliar preset: everything else
     * stays, only the cache is used again.
     */
    private static final boolean BYPASS_CELL_CACHE = true;

    /** Value written for a column that never hit an opaque block within the scanned range. */
    private static final int UNSAMPLED = Integer.MIN_VALUE;

    private final NoiseGeneratorSettings generatorSettings;
    /**
     * Same settings with ore veins and aquifers switched off.
     * <p>
     * A NoiseChunk builds its block-state rule from these two flags: ore veins
     * add three density functions to every sampled layer, and aquifers add a
     * full fluid-picking aquifer to the constructor. Neither can affect whether
     * a block is opaque, which is all a heightmap needs, so both are dead work
     * here. Switching them off at the source means the chunk never builds the
     * machinery in the first place.
     */
    private final NoiseGeneratorSettings samplingSettings;
    private final NoiseSettings noiseSettings;
    private final RandomState randomState;
    private final ChunkGenerator chunkGenerator;
    private final int scanMinY;
    private final int scanMaxY;
    private final Predicate<BlockState> opaque = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();

    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    /**
     * @param generatorSettings the dimension's noise settings (default block + cell size)
     * @param randomState        random state for the exported seed
     * @param chunkGenerator     the dimension's noise generator, for the fluid picker
     * @param scanMinY           lowest block Y to scan (inclusive)
     * @param scanMaxY           highest block Y to scan (inclusive)
     */
    public NoiseHeightSampler(NoiseGeneratorSettings generatorSettings,
                              RandomState randomState,
                              ChunkGenerator chunkGenerator,
                              int scanMinY,
                              int scanMaxY) {
        this.generatorSettings = generatorSettings;
        this.samplingSettings = samplingOnly(generatorSettings);
        this.noiseSettings = generatorSettings.noiseSettings();
        this.randomState = randomState;
        this.chunkGenerator = chunkGenerator;
        this.scanMinY = scanMinY;
        this.scanMaxY = scanMaxY;
    }

    /**
     * The given settings with ore veins and aquifers disabled, or the same
     * instance when both are already off.
     * <p>
     * Ore veins cost three density functions per sampled layer and aquifers a
     * real {@code Aquifer} in the NoiseChunk constructor. Neither changes
     * whether a block is opaque -- water and air are equally transparent, which
     * is all the heightmap asks -- so both are skipped at the source. The
     * default block and fluid are kept intact; only the two flags change.
     */
    private static NoiseGeneratorSettings samplingOnly(NoiseGeneratorSettings settings) {
        if (!settings.oreVeinsEnabled() && !settings.aquifersEnabled()) {
            return settings;
        }
        return new NoiseGeneratorSettings(
                settings.noiseSettings(),
                settings.defaultBlock(),
                settings.defaultFluid(),
                settings.noiseRouter(),
                settings.surfaceRule(),
                settings.spawnTarget(),
                settings.seaLevel(),
                settings.disableMobGeneration(),
                false,  // aquifersEnabled
                false,  // oreVeinsEnabled
                settings.useLegacyRandomSource());
    }

    @Override
    public void fillGrid(int originX, int originZ, int step, int width, int height, int[] out,
                         BooleanSupplier cancelled) throws Exception {
        final int maxX = originX + (width - 1) * step;
        final int maxZ = originZ + (height - 1) * step;

        // Process chunk-span groups so the NoiseChunk stays small enough to be
        // cheap to build, and so a long export keeps checking cancellation
        // between groups rather than only between tiles.
        //
        // Groups are anchored at the grid origin rather than at world chunk
        // boundaries. World alignment looks tidier but costs: the export origin
        // is centre minus radius, essentially never a multiple of the group
        // size, so each tile would straddle 3x3 groups instead of 2x2 and pay a
        // full NoiseChunk setup for every one of them -- a third of them for a
        // sliver of columns.
        final int groupBlocks = CHUNK_SPAN * 16;
        final int groupsX = (maxX - originX) / groupBlocks + 1;
        final int groupsZ = (maxZ - originZ) / groupBlocks + 1;

        final Scratch s = scratch.get();
        for (int gx = 0; gx < groupsX; gx++) {
            for (int gz = 0; gz < groupsZ; gz++) {
                if (cancelled.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException("Terrain export cancelled");
                }
                final int groupMinX = originX + gx * groupBlocks;
                final int groupMinZ = originZ + gz * groupBlocks;
                scanGroup(s, groupMinX, groupMinZ, originX, originZ, step, width, height, maxX, maxZ, out);
            }
        }
    }

    /**
     * Single-column queries are not supported: the sampler's whole point is to
     * amortise the Y sweep over a cell, and answering one column would pay that
     * cost per pixel. Returning null keeps the caller on the estimate.
     */
    @Override
    public @org.jetbrains.annotations.Nullable Integer heightAt(int blockX, int blockZ) {
        return null;
    }

    /**
     * Scans one chunk-span group and writes every requested column of it into
     * {@code out}. Columns that find no opaque block keep {@link #UNSAMPLED}.
     */
    private void scanGroup(Scratch s,
                           int groupMinX, int groupMinZ,
                           int originX, int originZ,
                           int step, int width, int height, int maxX, int maxZ,
                           int[] out) {
        final int cellWidth = noiseSettings.getCellWidth();
        final int cellHeight = noiseSettings.getCellHeight();

        // Effective block-Y range, clamped to the noise grid the same way the
        // preview's heightmap unit does, and aligned to whole cells so
        // selectCellYZ stays in range.
        final int gridCellStart = Mth.floorDiv(noiseSettings.minY(), cellHeight);
        final int gridCellCount = Mth.floorDiv(noiseSettings.height(), cellHeight);
        final int gridBottomY = gridCellStart * cellHeight;
        final int gridTopY = (gridCellStart + gridCellCount) * cellHeight - 1;
        final int effMinY = Math.max(scanMinY, gridBottomY);
        final int effMaxY = Math.min(scanMaxY, gridTopY);
        if (effMinY > effMaxY) {
            return;
        }
        final int cellStart = Mth.floorDiv(effMinY, cellHeight);
        final int cellEnd = Mth.floorDiv(effMaxY, cellHeight);
        final int cellCountY = cellEnd - cellStart + 1;
        final int cellOffsetY = cellStart - gridCellStart;
        if (cellCountY <= 0) {
            return;
        }

        // Group extent, clipped to the requested grid. The last group of a tile
        // is usually narrower than the full span, and constructing a NoiseChunk
        // large enough for the whole span when only a sliver of it holds columns
        // means paying the interpolator setup for blocks nobody reads.
        final int spanX = Math.min(CHUNK_SPAN * 16, maxX - groupMinX + 1);
        final int spanZ = Math.min(CHUNK_SPAN * 16, maxZ - groupMinZ + 1);
        // The noise grid is square, so cover the larger axis.
        final int cellCountXZ = (Math.max(spanX, spanZ) + cellWidth - 1) / cellWidth;

        // Block extent of this group, intersected with the requested grid.
        final int groupMaxX = groupMinX + spanX - 1;
        final int groupMaxZ = groupMinZ + spanZ - 1;
        final int fromX = Math.max(originX, groupMinX);
        final int toX = Math.min(maxX, groupMaxX);
        final int fromZ = Math.max(originZ, groupMinZ);
        final int toZ = Math.min(maxZ, groupMaxZ);
        if (fromX > toX || fromZ > toZ) {
            return;
        }

        final NoiseChunk noiseChunk = createNoiseChunk(groupMinX, groupMinZ, cellCountXZ);
        final NoiseChunkAccessor accessor = (NoiseChunkAccessor) noiseChunk;

        // The group's highest estimated surface bounds the downward scan for
        // every column in it, and it depends only on the group rectangle -- not
        // on which cell is being scanned. Compute it once here: inside the cell
        // loop it would be recomputed once per cell, i.e. 32x32 times per group
        // at the default span, each time walking the whole rectangle.
        // Some presets (noNewCaves is shared by the nether, the caves preset and
        // floating islands) fill the preliminary-surface slot with zero(), so the
        // value carries no information there. Taken at face value it would put
        // the main scan at y <= 0 and push every column into the upper-band pass,
        // i.e. scan the world twice. Treat a zero bound as unknown and scan the
        // full range instead -- slower than a good bound, but never wrong.
        final int groupSurface = maxSurfaceLevel(
                noiseChunk::preliminarySurfaceLevel, fromX, fromZ, toX, toZ);
        final int surfaceStartY = groupSurface == Integer.MIN_VALUE || groupSurface == 0
                ? effMaxY
                : Math.min(effMaxY, groupSurface + SURFACE_MARGIN);

        try {
            noiseChunk.initializeForFirstCellX();

            for (int cellX = 0; cellX < cellCountXZ; cellX++) {
                noiseChunk.advanceCellX(cellX);

                final int cellMinX = groupMinX + cellX * cellWidth;

                for (int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
                    final int cellMinZ = groupMinZ + cellZ * cellWidth;

                    int count = collectCellColumns(cellMinX, cellMinZ, cellWidth,
                            step, originX, originZ, toX, toZ, width,
                            s.xs, s.zs, s.dXs, s.dZs, s.outIdx);
                    if (count == 0) {
                        continue;
                    }

                    int remaining = scanYRange(noiseChunk, accessor, s, count, cellZ,
                            cellHeight, cellStart, cellOffsetY, cellCountY,
                            effMinY, surfaceStartY, generatorSettings.defaultBlock(), opaque, out);

                    // preliminarySurfaceLevel is an estimate: spires, floating
                    // islands and the terrain above large caves can sit above it.
                    // Anything still unresolved gets the skipped upper band, which
                    // keeps the result identical to a full-range scan.
                    if (remaining > 0 && surfaceStartY < effMaxY) {
                        scanYRange(noiseChunk, accessor, s, remaining, cellZ,
                                cellHeight, cellStart, cellOffsetY, cellCountY,
                                surfaceStartY + 1, effMaxY, generatorSettings.defaultBlock(), opaque, out);
                    }
                }

                noiseChunk.swapSlices();
            }
        } finally {
            noiseChunk.stopInterpolation();
        }
    }

    /**
     * Highest preliminary surface level across a block rectangle, or
     * {@link Integer#MIN_VALUE} when nothing could be estimated.
     * <p>
     * Walks every fourth block on both axes. That is exactly what Minecraft's
     * own {@code NoiseChunk.maxPreliminarySurfaceLevel} does -- its aquifer uses
     * that method for the same purpose -- but the helper only exists from 1.21.2
     * onwards, so it is spelled out here to keep one implementation across the
     * whole version range.
     *
     * @param levels column surface levels, as (x, z) to block Y
     */
    static int maxSurfaceLevel(java.util.function.IntBinaryOperator levels,
                               int x1, int z1, int x2, int z2) {
        int max = Integer.MIN_VALUE;
        for (int z = z1; z <= z2; z += 4) {
            for (int x = x1; x <= x2; x += 4) {
                final int level = levels.applyAsInt(x, z);
                if (level > max) {
                    max = level;
                }
            }
        }
        return max;
    }

    /**
     * Scans the Y band {@code [fromY, toY]} top-down for the first opaque block
     * of every still-unresolved column, writing hits into {@code out} and
     * compacting the scratch arrays in place.
     * <p>
     * Split out of {@link #scanGroup} because the scan runs in two bands: the
     * main band below the estimated surface, and an upper band that only the
     * columns the estimate undershot need.
     *
     * @return how many columns are still unresolved after the band
     */
    private static int scanYRange(NoiseChunk noiseChunk,
                                  NoiseChunkAccessor accessor,
                                  Scratch s,
                                  int count,
                                  int cellZ,
                                  int cellHeight,
                                  int cellStart,
                                  int cellOffsetY,
                                  int cellCountY,
                                  int fromY,
                                  int toY,
                                  BlockState defaultBlock,
                                  Predicate<BlockState> opaque,
                                  int[] out) {
        for (int cellY = cellCountY - 1; cellY >= 0 && count > 0; --cellY) {
            final int cellBase = (cellStart + cellY) * cellHeight;
            if (cellBase > toY) {
                // This cell lies entirely above the band; keep descending.
                continue;
            }
            if (cellBase + cellHeight - 1 < fromY) {
                // This cell and every lower one lie below the band.
                break;
            }
            noiseChunk.selectCellYZ(cellY + cellOffsetY, cellZ);

            for (int yInCell = cellHeight - 1; yInCell >= 0 && count > 0; --yInCell) {
                final int y = cellBase + yInCell;
                if (y < fromY || y > toY) {
                    continue;
                }
                noiseChunk.updateForY(y, (double) yInCell / (double) cellHeight);

                for (int idx = 0; idx < count; ++idx) {
                    noiseChunk.updateForX(s.xs[idx], s.dXs[idx]);
                    noiseChunk.updateForZ(s.zs[idx], s.dZs[idx]);

                    BlockState state = accessor.invokeGetInterpolatedState();
                    if (state == null) {
                        state = defaultBlock;
                    }

                    if (opaque.test(state)) {
                        out[s.outIdx[idx]] = y + 1;
                        // Ordered compaction, same contract as the preview
                        // heightmap unit: keep the remaining columns in order.
                        final int tail = count - idx - 1;
                        if (tail > 0) {
                            System.arraycopy(s.xs, idx + 1, s.xs, idx, tail);
                            System.arraycopy(s.zs, idx + 1, s.zs, idx, tail);
                            System.arraycopy(s.dXs, idx + 1, s.dXs, idx, tail);
                            System.arraycopy(s.dZs, idx + 1, s.dZs, idx, tail);
                            System.arraycopy(s.outIdx, idx + 1, s.outIdx, idx, tail);
                        }
                        --count;
                        --idx;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Collects the requested grid columns that fall inside one noise cell.
     * <p>
     * The export grid is anchored at {@code (originX, originZ)} with a fixed
     * {@code step}, which is almost never aligned to the noise cell grid. This
     * finds the first requested column at or after the cell's left/top edge and
     * walks outwards from there, so a cell pays only for the columns it owns.
     * <p>
     * Extracted as a pure function so the mapping can be tested without a
     * worldgen bootstrap: any gap or double-visit in this arithmetic shows up
     * as a pixel whose height stays unknown or is written twice.
     *
     * @return the number of columns written into the scratch arrays
     */
    static int collectCellColumns(int cellMinX, int cellMinZ, int cellWidth,
                                  int step, int originX, int originZ,
                                  int toX, int toZ, int gridWidth,
                                  int[] xs, int[] zs, double[] dXs, double[] dZs, int[] outIdx) {
        // A cell that starts before the grid origin would otherwise yield a
        // column left of it, and the grid offset would go negative: clamp the
        // search start to the grid, then align forward from there.
        final int fromX = Math.max(originX, cellMinX);
        final int firstX = fromX + Math.floorMod(originX - fromX, step);
        if (firstX >= cellMinX + cellWidth || firstX > toX) {
            return 0;
        }
        final int fromZ = Math.max(originZ, cellMinZ);
        final int firstZ = fromZ + Math.floorMod(originZ - fromZ, step);
        if (firstZ >= cellMinZ + cellWidth || firstZ > toZ) {
            return 0;
        }

        int count = 0;
        for (int bx = firstX; bx < cellMinX + cellWidth && bx <= toX; bx += step) {
            for (int bz = firstZ; bz < cellMinZ + cellWidth && bz <= toZ; bz += step) {
                if (count == xs.length) {
                    return count;
                }
                xs[count] = bx;
                zs[count] = bz;
                dXs[count] = (double) (bx - cellMinX) / (double) cellWidth;
                dZs[count] = (double) (bz - cellMinZ) / (double) cellWidth;
                outIdx[count] = ((bz - originZ) / step) * gridWidth + ((bx - originX) / step);
                ++count;
            }
        }
        return count;
    }

    private NoiseChunk createNoiseChunk(int minBlockX, int minBlockZ, int cellCountXZ) {
        final NoiseChunk noiseChunk = new NoiseChunk(
                cellCountXZ,
                randomState,
                minBlockX,
                minBlockZ,
                noiseSettings,
                DensityFunctions.BeardifierMarker.INSTANCE,
                samplingSettings,
                ((NoiseBasedChunkGeneratorAccessor) chunkGenerator).getGlobalFluidPicker().get(),
                Blender.empty());
        final NoiseChunkAccessor accessor = (NoiseChunkAccessor) noiseChunk;

        // The heightmap only asks "is this block opaque", and water and air are
        // both transparent, so the real aquifer's fluid picking is pure waste --
        // it costs a global fluid lookup, a lava test, three grid divisions and a
        // 3x3x3 interpolation per sampled layer. EmptyAquifer answers the same
        // question with one density comparison, and is what the preview's own
        // heightmap layer has always used (SampleUtils.getNoiseChunk(...,
        // keepAquifer = false)).
        accessor.setAquifer(new EmptyAquifer());

        // Take the cell-wide cache out of the path.
        //
        // A noise cell is 4x4x8 blocks and, at the default 4 blocks per pixel,
        // exactly one column of it is read. selectCellYZ nevertheless fills all
        // 128 interpolated values of the cell on every Y step, of which we use
        // eight. Dropping the cache and calling the interpolator directly yields
        // the same number: with no cache installed `fillingCell` stays false, so
        // the interpolator returns the value its updateForX/Y/Z just produced
        // instead of re-deriving it from the eight corners.
        //
        // Only attempted when the chunk holds exactly one cache and one
        // interpolator. Anything else means the final density is not a single
        // interpolated node (a modded preset, say), and guessing which one feeds
        // the block-state rule would silently sample the wrong field.
        final List<?> cellCaches = accessor.getCellCaches();
        final List<?> interpolators = accessor.getInterpolators();
        if (BYPASS_CELL_CACHE
                && cellCaches.size() == 1 && interpolators.size() == 1
                && interpolators.get(0) instanceof NoiseChunk.NoiseInterpolator interpolator) {
            final Aquifer aquifer = accessor.getAquifer();
            cellCaches.clear();
            accessor.setBlockStateRule(ctx -> aquifer.computeSubstance(ctx, interpolator.compute(ctx)));
        }
        return noiseChunk;
    }

    /** Per-thread scratch: the cell lattice plus one NoiseChunk's worth of state. */
    private static final class Scratch {
        /** Largest lattice a cell can request: cellWidth/step + 1 squared, with step >= 1. */
        private static final int CAP = 64 * 64;

        final int[] xs = new int[CAP];
        final int[] zs = new int[CAP];
        final double[] dXs = new double[CAP];
        final double[] dZs = new double[CAP];
        final int[] outIdx = new int[CAP];
    }
}
