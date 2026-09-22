package caeruleusTait.world.preview.backend.export;

import caeruleusTait.world.preview.backend.terrain.ContourRenderer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

/**
 * Core terrain map exporter.
 * <p>
 * Unlike TFC's stripe-based parallel sampling, this uses a tile-based parallel strategy:
 * The export image is split into square tiles, each sampled and classified by a worker thread,
 * and the main thread writes tile results to {@link NativeImage} in completion order.
 * </p>
 * <p>
 * Unlike TFC's custom BinaryIndexedPngWriter (1-bit indexed PNG),
 * this uses Minecraft's built-in NativeImage API to output full-color RGB PNG.
 * </p>
 */
public final class TerrainMapExporter {

    private static final int TILE_SIZE = 64;
    /** Semi-transparent black used by the coordinate grid overlay (ARGB). */
    private static final int GRID_COLOR = 0x59000000;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final int workerThreads;

    public TerrainMapExporter(int workerThreads) {
        this.workerThreads = Math.clamp(workerThreads, 1, 8);
    }

    /**
     * Optional probe for real sampled surface heights. When the probe returns
     * a value for a pixel the export uses the real height; when it returns
     * null (or no probe is supplied) the exporter falls back to the rough
     * biome-based estimate and the metadata marks the height source
     * accordingly. Backed by {@code SampleQuery#realHeightAt}.
     */
    @FunctionalInterface
    public interface HeightProbe {
        @Nullable Integer heightAt(int blockX, int blockZ) throws Exception;
    }

    /** Lineage info written into the export metadata (world identity). */
    public record ExportContext(String seed, String dimension) {}

    /**
     * Optional storage-backed probe for biome ids. Returns the preview
     * storage's biome id for a block column when its chunk completed sampling
     * in the same worldgen context, or {@code null} when unsampled, partially
     * sampled, or otherwise unavailable. Backed by the live
     * {@code PreviewStorage}; strictly read-only.
     */
    @FunctionalInterface
    public interface BiomeIdProbe {
        /** Storage hit returns the biome id; unsampled or guarded returns null. */
        @Nullable Short biomeIdAt(int blockX, int blockZ) throws Exception;
    }

    /**
     * Per-id view over the storage biome-id space. {@link #known} guards the
     * storage-id to preview-data misalignment (out-of-range or unregistered
     * ids fall back to sampling), {@link #category} mirrors
     * {@code TerrainClassifier#classify} for the id, and
     * {@link #estimatedHeight} mirrors the exporter's biome-based estimate.
     */
    public interface BiomeIdResolver {
        boolean known(short id);

        TerrainCategory category(short id);

        short estimatedHeight(short id);
    }

    /** Storage-backed biome facts: probe plus per-id resolver pair. */
    public record BiomeFacts(BiomeIdProbe probe, BiomeIdResolver resolver) {}

    /**
     * Table-backed {@link BiomeIdResolver}: {@code byId[id] == null} marks an
     * unknown or unregistered id. Category and estimated-height tables are
     * precomputed once from the preview biome list, so the export hot path
     * performs no holder or tag lookups.
     */
    public record IdTableResolver(TerrainCategory[] byId, short[] estById) implements BiomeIdResolver {
        @Override
        public boolean known(short id) {
            return id >= 0 && id < byId.length && byId[id] != null;
        }

        @Override
        public TerrainCategory category(short id) {
            return known(id) ? byId[id] : TerrainCategory.UNKNOWN;
        }

        @Override
        public short estimatedHeight(short id) {
            return known(id) ? estById[id] : TerrainClassifier.categoryHeight(TerrainCategory.UNKNOWN);
        }
    }

    /**
     * Execute terrain map export with a filename prefix (used by batch exports to
     * tag each file with its dimension, e.g. {@code terrain_overworld_...}).
     * Callers pass the per-dimension height range: heights are exported as blocks
     * above {@code yMin}, so contours and grayscale cover deep and tall dimensions
     * alike instead of flattening outside 0..255.
     */
    public Result export(
            TerrainExportSpec spec,
            BiomeSampler sampler,
            int yMin,
            int yMax,
            Path outputDir,
            String filenamePrefix,
            BooleanSupplier cancelled,
            LongConsumer progress
    ) throws Exception {
        return export(spec, sampler, null, null, null, yMin, yMax, outputDir, filenamePrefix, cancelled, progress);
    }

    /**
     * Full export entry: optional real-height probe, optional world lineage
     * for the metadata, and optional storage-backed biome facts.
     * {@code heightProbe == null} keeps the legacy estimate behavior; when
     * supplied, real heights win and the metadata records the height source
     * per pixel set. {@code biomeFacts == null} keeps the pure noise-sampling
     * behavior; when supplied, pixels whose chunk completed preview sampling
     * reuse the storage's biome id instead of re-sampling (fact hits count as
     * estimated heights in the metadata, matching the estimate fallback).
     *
     * @param yMin lowest world Y of the dimension; heights are stored relative to it
     * @param yMax highest world Y of the dimension (exclusive upper sampling bound)
     */
    public Result export(
            TerrainExportSpec spec,
            BiomeSampler sampler,
            @Nullable HeightProbe heightProbe,
            @Nullable ExportContext exportContext,
            @Nullable BiomeFacts biomeFacts,
            int yMin,
            int yMax,
            Path outputDir,
            String filenamePrefix,
            BooleanSupplier cancelled,
            LongConsumer progress
    ) throws Exception {
        Files.createDirectories(outputDir);

        int width = spec.imageWidth();
        int height = spec.imageHeight();
        int ySpan = Math.max(1, yMax - yMin);

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String filename = filenamePrefix + "terrain_" + timestamp + "_"
                + spec.centerX() + "_" + spec.centerZ() + "_"
                + spec.coverageRadius() + ".png";
        Path pngPath = outputDir.resolve(filename);
        Path partPath = pngPath.resolveSibling(pngPath.getFileName() + ".part");
        Files.deleteIfExists(partPath);

        // Build tile task list
        List<TileTask> tasks = buildTileTasks(spec, width, height);
        int totalTiles = tasks.size();

        ExecutorService workers = Executors.newFixedThreadPool(workerThreads, new ExportThreadFactory());
        List<Future<TileResult>> futures = new ArrayList<>(totalTiles);

        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        try {
            // Submit all tile tasks
            for (TileTask task : tasks) {
                final TileTask t = task;
                futures.add(workers.submit(() ->
                        sampleTile(t, spec, sampler, heightProbe, biomeFacts, yMin, ySpan, cancelled, progress)));
            }

            // Collect results in completion order, write to NativeImage, collect height data
            short[] heightField = null;
            if (spec.exportContours()) {
                heightField = new short[width * height];
            }

            // Track how many pixels used real vs estimated heights for metadata.
            AtomicInteger realHeightPixels = new AtomicInteger();

            // In contour mode every pixel is repainted by the grayscale + contour
            // pass below, so the terrain color write is skipped entirely.
            final boolean writeTerrainColors = heightField == null;

            for (Future<TileResult> future : futures) {
                checkCancelled(cancelled);
                TileResult tile = await(future);
                realHeightPixels.addAndGet(tile.realHeightCount);
                // Write tile pixels to NativeImage and collect height data
                for (int row = 0; row < tile.rowCount; row++) {
                    int y = tile.startY + row;
                    if (y >= height) break;
                    int rowOffset = row * tile.tileWidth;
                    if (writeTerrainColors) {
                        fillRowRuns(image, tile.pixels, rowOffset, tile.startX, y, tile.tileWidth, width);
                    }
                    if (heightField != null) {
                        for (int col = 0; col < tile.tileWidth; col++) {
                            int x = tile.startX + col;
                            if (x >= width) break;
                            heightField[y * width + x] = tile.heights[rowOffset + col];
                        }
                    }
                }
            }

            checkCancelled(cancelled);

            // Contour overlay post-processing
            if (spec.exportContours() && heightField != null) {
                // Rebuild color buffer from height field
                int[] colorBuffer = new int[width * height];
                for (int py = 0; py < height; py++) {
                    for (int px = 0; px < width; px++) {
                        // Pixels already written above; rebuild with terrain classification colors
                        // We already have colors in tile.pixels, but they've been written to image
                        // Generate rough colors from heightField here
                        int h = heightField[py * width + px] & 0xFFFF;
                        int gray = grayForOffset(h, ySpan);
                        colorBuffer[py * width + px] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
                    }
                }
                ContourRenderer cr = new ContourRenderer(spec.contourInterval(), true, 0xC08B4513, 0x608B6914);
                cr.render(heightField, colorBuffer, width, height);
                for (int py = 0; py < height; py++) {
                    fillRowRuns(image, colorBuffer, py * width, 0, py, width, width);
                }
            }

            // Grid overlay post-processing (drawn on top of terrain and contours)
            if (spec.gridIntervalBlocks() > 0) {
                drawGridOverlay(image, spec, width, height);
            }

            // Write PNG file (write .part first, then atomic move)
            image.writeToFile(partPath.toFile());
            moveCompleteFile(partPath, pngPath);

            // Write metadata JSON
            String metadata = buildMetadata(spec, width, height, timestamp,
                    heightProbe != null, realHeightPixels.get(), exportContext);
            Path metaPath = pngPath.resolveSibling(filename.replace(".png", ".json"));
            Path metaPart = metaPath.resolveSibling(metaPath.getFileName() + ".part");
            Files.writeString(metaPart, metadata);
            moveCompleteFile(metaPart, metaPath);

            return new Result(pngPath, metaPath);
        } catch (CancellationException e) {
            cleanupQuiet(partPath);
            cleanupQuiet(pngPath.resolveSibling(filename.replace(".png", ".json") + ".part"));
            throw e;
        } catch (Exception e) {
            cleanupQuiet(partPath);
            throw e;
        } finally {
            image.close();
            for (Future<TileResult> f : futures) {
                f.cancel(true);
            }
            workers.shutdownNow();
        }
    }

    private List<TileTask> buildTileTasks(TerrainExportSpec spec, int width, int height) {
        List<TileTask> tasks = new ArrayList<>();
        for (int ty = 0; ty < height; ty += TILE_SIZE) {
            for (int tx = 0; tx < width; tx += TILE_SIZE) {
                int tw = Math.min(TILE_SIZE, width - tx);
                int th = Math.min(TILE_SIZE, height - ty);
                tasks.add(new TileTask(tx, ty, tw, th));
            }
        }
        return tasks;
    }

    private static TileResult sampleTile(
            TileTask task,
            TerrainExportSpec spec,
            BiomeSampler sampler,
            @Nullable HeightProbe heightProbe,
            @Nullable BiomeFacts biomeFacts,
            int yMin,
            int ySpan,
            BooleanSupplier cancelled,
            LongConsumer progress
    ) {
        int[] pixels = new int[task.tileWidth * task.tileHeight];
        short[] heights = new short[task.tileWidth * task.tileHeight];
        int realHeightCount = 0;
        int minBlockX = spec.minBlockX();
        int minBlockZ = spec.minBlockZ();
        int bpp = spec.blocksPerPixel();

        for (int row = 0; row < task.tileHeight; row++) {
            if ((row & 15) == 0) {
                checkCancelled(cancelled);
            }
            int pixelZ = task.startY + row;
            int blockZ = minBlockZ + pixelZ * bpp;
            int rowOffset = row * task.tileWidth;

            for (int col = 0; col < task.tileWidth; col++) {
                int pixelX = task.startX + col;
                int blockX = minBlockX + pixelX * bpp;

                // Real sampled height wins; estimation is the explicit fallback.
                // Probed before the biome lookup so the storage fast path below
                // can still honor real heights.
                Integer realHeight = null;
                if (heightProbe != null) {
                    try {
                        realHeight = heightProbe.heightAt(blockX, blockZ);
                    } catch (Exception ignored) {
                        realHeight = null;
                    }
                }

                // Storage fast path: reuse the preview storage's biome id for
                // this column when its chunk completed sampling for the same
                // worldgen context. The resolver rejects unknown ids; anything
                // else (unsampled chunk, misaligned id) falls through to the
                // noise sampler unchanged. Fact hits count as estimated heights
                // in the metadata, matching the estimate fallback below.
                if (biomeFacts != null) {
                    Short stored = null;
                    try {
                        stored = biomeFacts.probe().biomeIdAt(blockX, blockZ);
                    } catch (Exception ignored) {
                        stored = null;
                    }
                    if (stored != null && biomeFacts.resolver().known(stored)) {
                        if (realHeight != null) {
                            realHeightCount++;
                            heights[rowOffset + col] = toHeightFieldOffset(realHeight, yMin, ySpan);
                        } else {
                            heights[rowOffset + col] = toHeightFieldOffset(
                                    biomeFacts.resolver().estimatedHeight(stored), yMin, ySpan);
                        }
                        pixels[rowOffset + col] = biomeFacts.resolver().category(stored).pixelColor();
                        continue;
                    }
                }

                Holder<Biome> biomeHolder;
                try {
                    biomeHolder = sampler.sample(blockX, blockZ);
                } catch (Exception e) {
                    biomeHolder = null;
                }

                if (realHeight != null) {
                    realHeightCount++;
                    heights[rowOffset + col] = toHeightFieldOffset(realHeight, yMin, ySpan);
                } else {
                    heights[rowOffset + col] = toHeightFieldOffset(estimateHeight(biomeHolder), yMin, ySpan);
                }

                TerrainCategory category = TerrainClassifier.classify(biomeHolder);
                pixels[rowOffset + col] = category.pixelColor();
            }

            progress.accept(task.tileWidth);
        }

        return new TileResult(task.startX, task.startY, task.tileWidth, task.tileHeight, pixels, heights, realHeightCount);
    }

    /**
     * Export height-field unit: blocks above yMin, clamped into [0, ySpan].
     * World Y spans the whole dimension (overworld -64..320), so a 0-255 byte
     * field would flatten everything above yMin+255 and below it.
     */
    static short toHeightFieldOffset(int worldY, int yMin, int ySpan) {
        return (short) Math.max(0, Math.min(ySpan, worldY - yMin));
    }

    /** Grayscale base for the contour overlay: offset 0 (yMin) is black, ySpan (yMax) is white. */
    static int grayForOffset(int offset, int ySpan) {
        return (offset * 255) / ySpan;
    }

    /**
     * Paints one row of {@code cols} pixels with horizontal run merging:
     * consecutive pixels sharing the same color are emitted as a single
     * {@code fillRect} instead of one call per pixel. Output is identical to
     * per-pixel filling because {@link NativeImage#fillRect} overwrites (no
     * blending) and runs are emitted left-to-right in order.
     */
    private static void fillRowRuns(NativeImage image, int[] pixels, int rowOffset,
                                    int startX, int y, int cols, int imageWidth) {
        fillRowRuns(image::fillRect, pixels, rowOffset, startX, y, cols, imageWidth);
    }

    /** Sink abstraction over {@code NativeImage#fillRect} so run merging is unit-testable. */
    @FunctionalInterface
    interface RectSink {
        void fillRect(int x, int y, int width, int height, int color);
    }

    /** Run-merging core; package-private so the drawing-matrix equivalence is testable. */
    static void fillRowRuns(RectSink sink, int[] pixels, int rowOffset,
                            int startX, int y, int cols, int imageWidth) {
        // Edge tiles can overrun the image's right border; clip exactly like
        // the per-pixel loops this replaces.
        final int usable = Math.min(cols, imageWidth - startX);
        int runStart = 0;
        for (int i = 1; i <= usable; i++) {
            if (i == usable || pixels[rowOffset + i] != pixels[rowOffset + runStart]) {
                sink.fillRect(startX + runStart, y, i - runStart, 1, pixels[rowOffset + runStart]);
                runStart = i;
            }
        }
    }

    /**
     * Draws the block-coordinate grid overlay. A pixel lies on a grid line when
     * its block coordinate modulo the grid interval falls within one pixel step;
     * floorMod keeps negative world coordinates aligned to the same grid.
     * <p>
     * Painted as full-row / full-column fills instead of per-pixel rects:
     * {@code fillRect} overwrites (no blending) and the grid color is constant,
     * so drawing a line once per row/column produces exactly the same pixels as
     * the per-pixel pass (line intersections repaint the same color).
     */
    private static void drawGridOverlay(NativeImage image, TerrainExportSpec spec, int width, int height) {
        int bpp = spec.blocksPerPixel();
        int interval = spec.gridIntervalBlocks();
        int minBlockX = spec.minBlockX();
        int minBlockZ = spec.minBlockZ();

        for (int py = 0; py < height; py++) {
            int blockZ = minBlockZ + py * bpp;
            if (Math.floorMod(blockZ, interval) < bpp) {
                image.fillRect(0, py, width, 1, GRID_COLOR);
            }
        }
        for (int px = 0; px < width; px++) {
            int blockX = minBlockX + px * bpp;
            if (Math.floorMod(blockX, interval) < bpp) {
                image.fillRect(px, 0, 1, height, GRID_COLOR);
            }
        }
    }

    /**
     * Roughly estimate terrain height from biome.
     */
    private static short estimateHeight(Holder<Biome> biomeHolder) {
        if (biomeHolder == null) return 0;
        return TerrainClassifier.categoryHeight(TerrainClassifier.classify(biomeHolder));
    }

    private static TileResult await(Future<TileResult> future) throws Exception {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Terrain export interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CancellationException ce) throw ce;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error er) throw er;
            throw new Exception("Terrain sampling failed", cause);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Terrain export cancelled");
        }
    }

    private static void moveCompleteFile(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanupQuiet(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private static String buildMetadata(TerrainExportSpec spec, int width, int height, String timestamp,
                                        boolean heightProbeSupplied, int realHeightPixels,
                                        @Nullable ExportContext exportContext) {
        String heightSource;
        if (!heightProbeSupplied) {
            heightSource = "estimated";
        } else if (realHeightPixels <= 0) {
            heightSource = "estimated";
        } else if (realHeightPixels >= width * height) {
            heightSource = "real";
        } else {
            heightSource = "mixed";
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"timestamp\": \"").append(timestamp).append("\",\n");
        if (exportContext != null) {
            sb.append("  \"seed\": \"").append(exportContext.seed()).append("\",\n");
            sb.append("  \"dimension\": \"").append(exportContext.dimension()).append("\",\n");
        }
        sb.append("  \"heightSource\": \"").append(heightSource).append("\",\n");
        sb.append("  \"realHeightPixels\": ").append(realHeightPixels).append(",\n");
        sb.append("  \"centerX\": ").append(spec.centerX()).append(",\n");
        sb.append("  \"centerZ\": ").append(spec.centerZ()).append(",\n");
        sb.append("  \"coverageRadius\": ").append(spec.coverageRadius()).append(",\n");
        sb.append("  \"blocksPerPixel\": ").append(spec.blocksPerPixel()).append(",\n");
        sb.append("  \"gridIntervalBlocks\": ").append(spec.gridIntervalBlocks()).append(",\n");
        sb.append("  \"imageWidth\": ").append(width).append(",\n");
        sb.append("  \"imageHeight\": ").append(height).append(",\n");
        sb.append("  \"bounds\": {\n");
        sb.append("    \"minX\": ").append(spec.minBlockX()).append(",\n");
        sb.append("    \"maxX\": ").append(spec.maxBlockX()).append(",\n");
        sb.append("    \"minZ\": ").append(spec.minBlockZ()).append(",\n");
        sb.append("    \"maxZ\": ").append(spec.maxBlockZ()).append("\n");
        sb.append("  },\n");
        sb.append("  \"classificationMode\": \"vanilla_biome_tag_priority\",\n");
        sb.append("  \"categories\": [\n");
        TerrainCategory[] cats = TerrainCategory.values();
        for (int i = 0; i < cats.length; i++) {
            sb.append("    {\"name\": \"").append(cats[i].name())
              .append("\", \"color\": \"").append(cats[i].hexColor()).append("\"}");
            if (i < cats.length - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ===== Internal types =====

    /** Function interface mapping block coords to biome Holder. */
    @FunctionalInterface
    public interface BiomeSampler {
        Holder<Biome> sample(int blockX, int blockZ) throws Exception;
    }

    /** Export result. */
    public record Result(Path pngPath, Path metadataPath) {}

    /** Tile sampling task descriptor. */
    private record TileTask(int startX, int startY, int tileWidth, int tileHeight) {}

    /** Tile sampling result. */
    private record TileResult(int startX, int startY, int tileWidth, int rowCount, int[] pixels, short[] heights,
                              int realHeightCount) {}

    /** Export thread factory. */
    private static final class ExportThreadFactory implements ThreadFactory {
        private static final AtomicInteger NEXT_ID = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "wp-terrain-export-" + NEXT_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
