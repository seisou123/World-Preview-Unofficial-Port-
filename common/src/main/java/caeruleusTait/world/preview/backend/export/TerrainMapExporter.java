package caeruleusTait.world.preview.backend.export;

import caeruleusTait.world.preview.backend.terrain.ContourRenderer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;


import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** Per-id biome color table over the storage biome-id space (biome color mode). */
    public record BiomeColorTable(int[] colorById, String[] tagById, String[] nameById) {
    }

    /**
     * Per-pixel color source for the exported map: turns the biome identity of
     * each pixel (a storage id on the fast path, a freshly sampled holder on
     * the noise path) into an opaque ARGB color and a metadata statistics
     * bucket. Implementations also self-describe the metadata: the
     * {@code classificationMode} value and the color legend. The exporter is
     * otherwise agnostic of whether it paints terrain categories or biomes.
     */
    public interface TerrainColorSource {
        /**
         * Color for a storage biome id (fast path). Only called for ids the
         * facts resolver knows, so the resolver guard and the color table
         * always agree on the same id space.
         */
        int colorForId(short id);

        /** Metadata statistics bucket for a storage biome id (fast path). */
        int bucketForId(short id);

        /**
         * Color for a freshly sampled holder (noise path). A null holder or a
         * biome without a table entry falls back to the {@code UNKNOWN}
         * category color so unresolvable pixels stay visible.
         */
        int colorForHolder(@Nullable Holder<Biome> holder);

        /** Metadata statistics bucket for a freshly sampled holder; -1 skips the pixel. */
        int bucketForHolder(@Nullable Holder<Biome> holder);

        /** Number of metadata statistics buckets. */
        int bucketCount();

        /** Value written into the metadata's {@code classificationMode} field. */
        String modeName();

        /** Writes the pixel-count statistics and the color legend into the export metadata. */
        void writeLegend(JsonObject metadata, long[] statCounts);
    }

    /** Category mode: the historical nine-category coloring over the facts resolver. */
    private record CategoryColorSource(@Nullable BiomeIdResolver resolver) implements TerrainColorSource {
        @Override
        public int colorForId(short id) {
            return resolver != null ? resolver.category(id).argbColor() : TerrainCategory.UNKNOWN.argbColor();
        }

        @Override
        public int bucketForId(short id) {
            return resolver != null ? resolver.category(id).ordinal() : TerrainCategory.UNKNOWN.ordinal();
        }

        @Override
        public int colorForHolder(@Nullable Holder<Biome> holder) {
            return TerrainClassifier.classify(holder).argbColor();
        }

        @Override
        public int bucketForHolder(@Nullable Holder<Biome> holder) {
            return TerrainClassifier.classify(holder).ordinal();
        }

        @Override
        public int bucketCount() {
            return TerrainCategory.values().length;
        }

        @Override
        public String modeName() {
            return "vanilla_biome_tag_priority";
        }

        @Override
        public void writeLegend(JsonObject metadata, long[] statCounts) {
            JsonObject categoryPixels = new JsonObject();
            for (TerrainCategory category : TerrainCategory.values()) {
                categoryPixels.addProperty(category.name(), statCounts[category.ordinal()]);
            }
            metadata.add("categoryPixels", categoryPixels);
            JsonArray categories = new JsonArray();
            for (TerrainCategory category : TerrainCategory.values()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", category.name());
                entry.addProperty("color", category.hexColor());
                categories.add(entry);
            }
            metadata.add("categories", categories);
        }
    }

    /**
     * Biome mode: every pixel takes its biome's preview color. Storage ids
     * index the color table directly; freshly sampled holders are bridged
     * through their registry key's path, and unknown biomes fall back to the
     * {@code UNKNOWN} category color.
     */
    private static final class BiomeColorSource implements TerrainColorSource {
        private final int[] colorById;
        private final String[] tagById;
        private final String[] nameById;
        private final Map<String, Short> idByTag;

        BiomeColorSource(BiomeColorTable table) {
            this.colorById = table.colorById();
            this.tagById = table.tagById();
            this.nameById = table.nameById();
            this.idByTag = new java.util.HashMap<>();
            for (short id = 0; id < tagById.length; id++) {
                if (tagById[id] != null) {
                    idByTag.put(tagById[id], id);
                }
            }
        }

        @Override
        public int colorForId(short id) {
            return knownColor(id) ? colorById[id] : TerrainCategory.UNKNOWN.argbColor();
        }

        @Override
        public int bucketForId(short id) {
            return id;
        }

        @Override
        public int colorForHolder(@Nullable Holder<Biome> holder) {
            short id = idForHolder(holder);
            return id >= 0 ? colorById[id] : TerrainCategory.UNKNOWN.argbColor();
        }

        @Override
        public int bucketForHolder(@Nullable Holder<Biome> holder) {
            return idForHolder(holder);
        }

        @Override
        public int bucketCount() {
            return colorById.length;
        }

        @Override
        public String modeName() {
            return "biome_colors";
        }

        @Override
        public void writeLegend(JsonObject metadata, long[] statCounts) {
            JsonObject biomePixels = new JsonObject();
            JsonArray biomes = new JsonArray();
            for (int id = 0; id < tagById.length; id++) {
                if (tagById[id] == null) {
                    continue;
                }
                biomePixels.addProperty(tagById[id], statCounts[id]);
                JsonObject entry = new JsonObject();
                entry.addProperty("id", tagById[id]);
                if (nameById[id] != null) {
                    entry.addProperty("name", nameById[id]);
                }
                entry.addProperty("color", hexColor(colorById[id]));
                biomes.add(entry);
            }
            metadata.add("biomePixels", biomePixels);
            metadata.add("biomes", biomes);
        }

        private short idForHolder(@Nullable Holder<Biome> holder) {
            if (holder == null) {
                return -1;
            }
            return holder.unwrapKey()
                    .map(key -> idByTag.getOrDefault(key.identifier().toString(), (short) -1))
                    .orElse((short) -1);
        }

        private boolean knownColor(short id) {
            return id >= 0 && id < colorById.length && colorById[id] != 0;
        }

        private static String hexColor(int argb) {
            return String.format("#%02X%02X%02X", (argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF);
        }
    }

    /** Category mode color source; a null resolver only serves the noise path. */
    public static TerrainColorSource categories(@Nullable BiomeIdResolver resolver) {
        return new CategoryColorSource(resolver);
    }

    /** Biome mode color source over the per-id preview color table. */
    public static TerrainColorSource biomes(BiomeColorTable table) {
        return new BiomeColorSource(table);
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
        return export(spec, sampler, categories(null), null, null, null, yMin, yMax, outputDir, filenamePrefix, cancelled, progress);
    }

    /**
     * Full export entry: the pixel color source, an optional real-height
     * probe, optional world lineage for the metadata, and optional
     * storage-backed biome facts.
     * {@code colorSource} decides whether pixels take the nine terrain
     * category colors or their biome's preview color; the metadata's
     * classificationMode and color legend come from the same source.
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
            TerrainColorSource colorSource,
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
        if (!TerrainExportSpec.isSupported(spec.coverageRadius(), spec.blocksPerPixel())) {
            throw new IllegalArgumentException("Export exceeds the pixel allocation limit");
        }
        checkCancelled(cancelled);
        Files.createDirectories(outputDir);
        int width = spec.imageWidth();
        int height = spec.imageHeight();
        int ySpan = Math.max(1, yMax - yMin);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String filename = filenamePrefix + "terrain_" + timestamp + "_"
                + spec.centerX() + "_" + spec.centerZ() + "_"
                + spec.coverageRadius() + "_" + UUID.randomUUID() + ".png";
        Path pngPath = outputDir.resolve(filename);
        Path partPath = pngPath.resolveSibling(filename + ".part");
        Path metaPath = pngPath.resolveSibling(filename.substring(0, filename.length() - 4) + ".json");
        Path metaPart = metaPath.resolveSibling(metaPath.getFileName() + ".part");
        boolean writeHeightmap = spec.exportHeightmap();
        Path heightPath = null;
        Path heightPart = null;
        if (writeHeightmap) {
            String heightName = filename.substring(0, filename.length() - 4) + "_height.png";
            heightPath = outputDir.resolve(heightName);
            heightPart = heightPath.resolveSibling(heightName + ".part");
        }
        AtomicBoolean stopping = new AtomicBoolean();
        BooleanSupplier taskCancelled = () -> stopping.get() || cancelled.getAsBoolean();
        ExecutorService workers = Executors.newFixedThreadPool(workerThreads, new ExportThreadFactory());
        ExecutorCompletionService<TileResult> completions = new ExecutorCompletionService<>(workers);
        Set<Future<TileResult>> inFlight = new HashSet<>();
        boolean complete = false;
        try {
            int[] colors = new int[width * height];
            short[] heights = spec.exportContours() || spec.exportHeightmap()
                    ? new short[width * height] : null;
            int columns = (width + TILE_SIZE - 1) / TILE_SIZE;
            int totalTiles = columns * ((height + TILE_SIZE - 1) / TILE_SIZE);
            int submitted = 0;
            int collected = 0;
            int realHeightPixels = 0;
            long[] statCounts = new long[colorSource.bucketCount()];
            while (collected < totalTiles) {
                checkCancelled(cancelled);
                while (submitted < totalTiles && inFlight.size() < workerThreads * 2) {
                    int tx = (submitted % columns) * TILE_SIZE;
                    int ty = (submitted / columns) * TILE_SIZE;
                    TileTask task = new TileTask(tx, ty, Math.min(TILE_SIZE, width - tx),
                            Math.min(TILE_SIZE, height - ty));
                    inFlight.add(completions.submit(() -> sampleTile(task, spec, sampler, colorSource,
                            heightProbe, biomeFacts, yMin, ySpan, taskCancelled, progress)));
                    submitted++;
                }
                Future<TileResult> ready;
                try {
                    ready = completions.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Terrain export interrupted");
                }
                if (ready == null) continue;
                inFlight.remove(ready);
                TileResult tile = await(ready);
                realHeightPixels += tile.realHeightCount;
                for (int i = 0; i < statCounts.length; i++) {
                    statCounts[i] += tile.statCounts[i];
                }
                for (int row = 0; row < tile.rowCount; row++) {
                    int target = (tile.startY + row) * width + tile.startX;
                    System.arraycopy(tile.pixels, row * tile.tileWidth, colors, target, tile.tileWidth);
                    if (heights != null) {
                        System.arraycopy(tile.heights, row * tile.tileWidth, heights, target, tile.tileWidth);
                    }
                }
                collected++;
            }
            checkCancelled(cancelled);
            if (heights != null) {
                overlayContours(heights, colors, width, height, spec.contourInterval(), cancelled);
            }
            if (spec.gridIntervalBlocks() > 0) {
                drawGridOverlay(colors, spec, width, height, cancelled);
            }
            checkCancelled(cancelled);
            try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false)) {
                for (int y = 0; y < height; y++) {
                    checkCancelled(cancelled);
                    fillRowRuns(image, colors, y * width, 0, y, width, width);
                }
                checkCancelled(cancelled);
                // Native PNG encoding cannot be interrupted; cancellation is checked immediately after it.
                image.writeToFile(partPath.toFile());
            }
            if (writeHeightmap) {
                // Grayscale height field from the same offsets the contour
                // overlay consumes; run-merged like the color rows. R=G=B, so
                // the ABGR/ARGB sink difference is a no-op for these pixels.
                try (NativeImage gray = new NativeImage(NativeImage.Format.RGBA, width, height, false)) {
                    int[] row = new int[width];
                    for (int y = 0; y < height; y++) {
                        checkCancelled(cancelled);
                        for (int x = 0; x < width; x++) {
                            int g = grayForOffset(heights[y * width + x], ySpan);
                            row[x] = 0xFF000000 | (g << 16) | (g << 8) | g;
                        }
                        fillRowRuns(gray, row, 0, 0, y, width, width);
                    }
                    checkCancelled(cancelled);
                    gray.writeToFile(heightPart.toFile());
                }
            }
            checkCancelled(cancelled);
            Files.writeString(metaPart, buildMetadata(spec, width, height, timestamp,
                    heightProbe != null, realHeightPixels, exportContext, yMin, ySpan,
                    writeHeightmap ? heightPath.getFileName().toString() : null, colorSource, statCounts));
            checkCancelled(cancelled);
            moveCompleteFile(partPath, pngPath);
            checkCancelled(cancelled);
            if (writeHeightmap) {
                moveCompleteFile(heightPart, heightPath);
            }
            checkCancelled(cancelled);
            moveCompleteFile(metaPart, metaPath);
            checkCancelled(cancelled);
            complete = true;
            appendHistory(outputDir, spec, filename,
                    writeHeightmap ? heightPath.getFileName().toString() : null, exportContext, timestamp);
            return new Result(pngPath, metaPath);
        } finally {
            stopping.set(true);
            for (Future<TileResult> future : inFlight) future.cancel(true);
            workers.shutdownNow();
            // The caller may release the sampling world as soon as export returns.
            // Never let a cancelled worker outlive that world or publish progress into a new run.
            boolean interrupted = Thread.interrupted();
            for (;;) {
                try {
                    if (workers.awaitTermination(100, TimeUnit.MILLISECONDS)) break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (!complete) {
                cleanupQuiet(partPath);
                if (heightPart != null) cleanupQuiet(heightPart);
                cleanupQuiet(metaPart);
                cleanupQuiet(pngPath);
                if (heightPath != null) cleanupQuiet(heightPath);
                cleanupQuiet(metaPath);
            }
        }
    }

    /** Render overlapping two-row strips, retaining the global minor-then-major pass order. */
    static void overlayContours(short[] heights, int[] colors, int width, int height,
                                int interval, BooleanSupplier cancelled) {
        short[] stripHeights = new short[width * 2];
        int[] stripColors = new int[width * 2];
        int[] intervals = {Math.max(1, interval / 5), interval};
        int[] lineColors = {0x608B6914, 0xC08B4513};
        for (int pass = 0; pass < intervals.length; pass++) {
            // Blending treats each channel identically, so the renderer also accepts ARGB buffers.
            ContourRenderer renderer = new ContourRenderer(intervals[pass], false,
                    lineColors[pass], lineColors[pass]);
            for (int y = 0; y < height - 1; y++) {
                checkCancelled(cancelled);
                System.arraycopy(heights, y * width, stripHeights, 0, stripHeights.length);
                System.arraycopy(colors, y * width, stripColors, 0, stripColors.length);
                renderer.render(stripHeights, stripColors, width, 2);
                System.arraycopy(stripColors, 0, colors, y * width, stripColors.length);
            }
        }
    }

    private static TileResult sampleTile(
            TileTask task,
            TerrainExportSpec spec,
            BiomeSampler sampler,
            TerrainColorSource colorSource,
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
        long[] statCounts = new long[colorSource.bucketCount()];
        int minBlockX = spec.minBlockX();
        int minBlockZ = spec.minBlockZ();
        int bpp = spec.blocksPerPixel();

        for (int row = 0; row < task.tileHeight; row++) {
            checkCancelled(cancelled);
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
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Terrain export interrupted");
                    } catch (CancellationException e) {
                        throw e;
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
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Terrain export interrupted");
                    } catch (CancellationException e) {
                        throw e;
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
                        pixels[rowOffset + col] = colorSource.colorForId(stored);
                        int storedBucket = colorSource.bucketForId(stored);
                        if (storedBucket >= 0) {
                            statCounts[storedBucket]++;
                        }
                        continue;
                    }
                }

                Holder<Biome> biomeHolder;
                try {
                    biomeHolder = sampler.sample(blockX, blockZ);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Terrain export interrupted");
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    biomeHolder = null;
                }

                if (realHeight != null) {
                    realHeightCount++;
                    heights[rowOffset + col] = toHeightFieldOffset(realHeight, yMin, ySpan);
                } else {
                    heights[rowOffset + col] = toHeightFieldOffset(estimateHeight(biomeHolder), yMin, ySpan);
                }

                pixels[rowOffset + col] = colorSource.colorForHolder(biomeHolder);
                int holderBucket = colorSource.bucketForHolder(biomeHolder);
                if (holderBucket >= 0) {
                    statCounts[holderBucket]++;
                }
            }

            checkCancelled(cancelled);
            progress.accept(task.tileWidth);
        }

        return new TileResult(task.startX, task.startY, task.tileWidth, task.tileHeight, pixels, heights, realHeightCount,
                statCounts);
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

    /** Composite each grid pixel once, including line intersections, onto opaque terrain. */
    private static void drawGridOverlay(int[] colors, TerrainExportSpec spec, int width, int height,
                                        BooleanSupplier cancelled) {
        int bpp = spec.blocksPerPixel();
        int interval = spec.gridIntervalBlocks();
        int inverseAlpha = 255 - (GRID_COLOR >>> 24);
        for (int y = 0; y < height; y++) {
            checkCancelled(cancelled);
            boolean horizontal = Math.floorMod(spec.minBlockZ() + y * bpp, interval) < bpp;
            for (int x = 0; x < width; x++) {
                if (horizontal || Math.floorMod(spec.minBlockX() + x * bpp, interval) < bpp) {
                    int color = colors[y * width + x];
                    int r = ((color >>> 16) & 255) * inverseAlpha / 255;
                    int g = ((color >>> 8) & 255) * inverseAlpha / 255;
                    int b = (color & 255) * inverseAlpha / 255;
                    colors[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
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

    /**
     * Appends one line to the output directory's history.jsonl so exports can
     * be traced back by seed and dimension. Best-effort: a failed append never
     * fails the export itself.
     */
    private static void appendHistory(Path outputDir, TerrainExportSpec spec, String filename,
                                      @Nullable String heightmapFile, @Nullable ExportContext exportContext,
                                      String timestamp) {
        try {
            JsonObject entry = new JsonObject();
            entry.addProperty("timestamp", timestamp);
            if (exportContext != null) {
                entry.addProperty("seed", exportContext.seed());
                entry.addProperty("dimension", exportContext.dimension());
            }
            entry.addProperty("file", filename);
            if (heightmapFile != null) {
                entry.addProperty("heightmapFile", heightmapFile);
            }
            entry.addProperty("centerX", spec.centerX());
            entry.addProperty("centerZ", spec.centerZ());
            entry.addProperty("coverageRadius", spec.coverageRadius());
            entry.addProperty("blocksPerPixel", spec.blocksPerPixel());
            Files.writeString(outputDir.resolve("history.jsonl"), new GsonBuilder().create().toJson(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOGGER.warn("Failed to append terrain export history", e);
        }
    }



    private static String buildMetadata(TerrainExportSpec spec, int width, int height, String timestamp,
                                        boolean heightProbeSupplied, int realHeightPixels,
                                        @Nullable ExportContext exportContext, int yMin, int ySpan,
                                        @Nullable String heightmapFile, TerrainColorSource colorSource,
                                        long[] statCounts) {
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
        JsonObject metadata = new JsonObject();
        metadata.addProperty("timestamp", timestamp);
        if (exportContext != null) {
            metadata.addProperty("seed", exportContext.seed());
            metadata.addProperty("dimension", exportContext.dimension());
        }
        metadata.addProperty("heightSource", heightSource);
        metadata.addProperty("realHeightPixels", realHeightPixels);
        metadata.addProperty("exportHeightmap", spec.exportHeightmap());
        if (heightmapFile != null) {
            JsonObject heightmap = new JsonObject();
            heightmap.addProperty("file", heightmapFile);
            heightmap.addProperty("encoding",
                    "8-bit grayscale; surface height offset above minY scaled to 0-255 over ySpan");
            heightmap.addProperty("minY", yMin);
            heightmap.addProperty("ySpan", ySpan);
            metadata.add("heightmap", heightmap);
        }
        metadata.addProperty("centerX", spec.centerX());
        metadata.addProperty("centerZ", spec.centerZ());
        metadata.addProperty("exportY", spec.exportY());
        metadata.addProperty("coverageRadius", spec.coverageRadius());
        metadata.addProperty("blocksPerPixel", spec.blocksPerPixel());
        metadata.addProperty("exportContours", spec.exportContours());
        metadata.addProperty("contourInterval", spec.contourInterval());
        metadata.addProperty("gridIntervalBlocks", spec.gridIntervalBlocks());
        metadata.addProperty("imageWidth", width);
        metadata.addProperty("imageHeight", height);
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", spec.minBlockX());
        bounds.addProperty("maxX", spec.maxBlockX());
        bounds.addProperty("minZ", spec.minBlockZ());
        bounds.addProperty("maxZ", spec.maxBlockZ());
        metadata.add("bounds", bounds);
        metadata.addProperty("classificationMode", colorSource.modeName());
        colorSource.writeLegend(metadata, statCounts);
        return new GsonBuilder().setPrettyPrinting().create().toJson(metadata) + "\n";
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
                              int realHeightCount, long[] statCounts) {}

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
