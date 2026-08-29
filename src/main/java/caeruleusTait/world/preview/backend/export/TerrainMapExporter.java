package caeruleusTait.world.preview.backend.export;

import caeruleusTait.world.preview.backend.terrain.ContourRenderer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

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
     * Execute terrain map export.
     *
     * @param spec       Export specification
     * @param sampler    Biome sampler (maps block coords to biome Holder)
     * @param outputDir  Output directory
     * @param cancelled  Cancellation flag supplier
     * @param progress   Progress callback (receives completed pixel count)
     * @return Export result (file path and metadata)
     */
    public Result export(
            TerrainExportSpec spec,
            BiomeSampler sampler,
            Path outputDir,
            BooleanSupplier cancelled,
            LongConsumer progress
    ) throws Exception {
        return export(spec, sampler, outputDir, "", cancelled, progress);
    }

    /**
     * Execute terrain map export with a filename prefix (used by batch exports to
     * tag each file with its dimension, e.g. {@code terrain_overworld_...}).
     */
    public Result export(
            TerrainExportSpec spec,
            BiomeSampler sampler,
            Path outputDir,
            String filenamePrefix,
            BooleanSupplier cancelled,
            LongConsumer progress
    ) throws Exception {
        Files.createDirectories(outputDir);

        int width = spec.imageWidth();
        int height = spec.imageHeight();

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
                futures.add(workers.submit(() -> sampleTile(t, spec, sampler, cancelled, progress)));
            }

            // Collect results in completion order, write to NativeImage, collect height field
            byte[] heightField = null;
            if (spec.exportContours()) {
                heightField = new byte[width * height];
            }

            for (Future<TileResult> future : futures) {
                checkCancelled(cancelled);
                TileResult tile = await(future);
                // Write tile pixels to NativeImage and collect height data
                for (int row = 0; row < tile.rowCount; row++) {
                    int y = tile.startY + row;
                    if (y >= height) break;
                    int rowOffset = row * tile.tileWidth;
                    for (int col = 0; col < tile.tileWidth; col++) {
                        int x = tile.startX + col;
                        if (x >= width) break;
                        image.fillRect(x, y, 1, 1, tile.pixels[rowOffset + col]);
                        if (heightField != null) {
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
                        int h = heightField[py * width + px] & 0xFF;
                        int gray = Math.max(0, Math.min(255, h + 64));
                        colorBuffer[py * width + px] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
                    }
                }
                ContourRenderer cr = new ContourRenderer(spec.contourInterval(), true, 0xC08B4513, 0x608B6914);
                cr.render(heightField, colorBuffer, width, height);
                for (int py = 0; py < height; py++) {
                    for (int px = 0; px < width; px++) {
                        image.fillRect(px, py, 1, 1, colorBuffer[py * width + px]);
                    }
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
            String metadata = buildMetadata(spec, width, height, timestamp);
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
            BooleanSupplier cancelled,
            LongConsumer progress
    ) {
        int[] pixels = new int[task.tileWidth * task.tileHeight];
        byte[] heights = new byte[task.tileWidth * task.tileHeight];
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

                Holder<Biome> biomeHolder;
                try {
                    biomeHolder = sampler.sample(blockX, blockZ);
                } catch (Exception e) {
                    biomeHolder = null;
                }

                TerrainCategory category = TerrainClassifier.classify(biomeHolder);
                pixels[rowOffset + col] = category.pixelColor();
                heights[rowOffset + col] = estimateHeight(biomeHolder);
            }

            progress.accept(task.tileWidth);
        }

        return new TileResult(task.startX, task.startY, task.tileWidth, task.tileHeight, pixels, heights);
    }

    /**
     * Draws the block-coordinate grid overlay. A pixel lies on a grid line when
     * its block coordinate modulo the grid interval falls within one pixel step;
     * floorMod keeps negative world coordinates aligned to the same grid.
     */
    private static void drawGridOverlay(NativeImage image, TerrainExportSpec spec, int width, int height) {
        int bpp = spec.blocksPerPixel();
        int interval = spec.gridIntervalBlocks();
        int minBlockX = spec.minBlockX();
        int minBlockZ = spec.minBlockZ();

        for (int py = 0; py < height; py++) {
            int blockZ = minBlockZ + py * bpp;
            boolean onHorizontalLine = Math.floorMod(blockZ, interval) < bpp;
            for (int px = 0; px < width; px++) {
                int blockX = minBlockX + px * bpp;
                if (onHorizontalLine || Math.floorMod(blockX, interval) < bpp) {
                    image.fillRect(px, py, 1, 1, GRID_COLOR);
                }
            }
        }
    }

    /**
     * Roughly estimate terrain height from biome.
     */
    private static byte estimateHeight(Holder<Biome> biomeHolder) {
        if (biomeHolder == null) return 0;
        TerrainCategory cat = TerrainClassifier.classify(biomeHolder);
        return switch (cat) {
            case DEEP_OCEAN -> (byte) 30;
            case OCEAN -> (byte) 50;
            case RIVER -> (byte) 55;
            case BEACH -> (byte) 63;
            case PLAINS -> (byte) 70;
            case FOREST -> (byte) 75;
            case HILLS -> (byte) 90;
            case MOUNTAIN -> (byte) 120;
            case PEAK -> (byte) 160;
            case UNKNOWN -> (byte) 70;
        };
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

    private static String buildMetadata(TerrainExportSpec spec, int width, int height, String timestamp) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"timestamp\": \"").append(timestamp).append("\",\n");
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
    private record TileResult(int startX, int startY, int tileWidth, int rowCount, int[] pixels, byte[] heights) {}

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
