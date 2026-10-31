package caeruleusTait.world.preview.backend.export;

import caeruleusTait.world.preview.backend.terrain.ContourRenderer;
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
 * and the main thread assembles tile results in completion order.
 * </p>
 * <p>
 * Unlike TFC's custom BinaryIndexedPngWriter (1-bit indexed PNG),
 * this writes full-color PNG through the pure-Java {@link PngWriter}.
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
        // The pool is sized by the configured preview thread count; the only
        // ceiling that matters is the machine itself. The previous fixed cap
        // of 8 left the extra cores of larger hosts idle for minutes-long
        // exports.
        this.workerThreads = Math.clamp(workerThreads, 1,
                Math.max(1, Runtime.getRuntime().availableProcessors()));
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

    /**
     * A height probe that can fill a whole pixel grid at once.
     * <p>
     * Worldgen height sampling works on the noise chunk's cell lattice, not on
     * isolated points: the expensive part is advancing the interpolator through
     * the Y column, which serves every column of a cell at once. A per-pixel
     * probe would therefore pay that cost once per pixel. Implementations of
     * this interface receive the export tile as a grid and can batch the work
     * by chunk, which is what makes live sampling viable for a full-size export.
     * <p>
     * Implementations must leave a cell untouched (never write to {@code out})
     * when they cannot determine its height, so the caller can fall back to the
     * estimate for exactly those pixels.
     */
    public interface BatchHeightProbe extends HeightProbe {
        /**
         * Fills the heights of a block-space grid.
         *
         * @param originX block X of the first grid cell
         * @param originZ block Z of the first grid cell
         * @param step    block distance between neighbouring grid cells
         * @param width   grid width in cells
         * @param height  grid height in cells
         * @param out     {@code width * height} block-Y values, row-major, pre-filled with
         *                {@link Integer#MIN_VALUE} for "unknown"
         */
        void fillGrid(int originX, int originZ, int step, int width, int height, int[] out,
                      BooleanSupplier cancelled) throws Exception;
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

        /**
         * Bulk variant of {@link #biomeIdAt}: fills {@code out} (row-major,
         * {@code width * height} grid cells, pre-filled with -1) with the
         * storage biome id of every cell the probe can answer, leaving -1
         * where the per-pixel probe would answer null. Batches the storage
         * locking per layer instead of acquiring it per cell. Returns false
         * when unsupported; the exporter then stays on the per-pixel probe.
         */
        default boolean biomeIdBand(int minBlockX, int minBlockZ, int step, int width, int height,
                                    short[] out, BooleanSupplier cancelled) throws Exception {
            return false;
        }
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
        /** Sentinel in the packed estimate slot: the source cannot supply a height estimate. */
        int NO_ESTIMATE = 0xFFFF;

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

        /**
         * One lookup for a freshly sampled holder: ARGB color in bits 63..32,
         * statistics bucket in bits 31..16 (signed; -1 skips the pixel) and
         * estimated world Y in bits 15..0 (unsigned; {@link #NO_ESTIMATE}
         * when unknown). Implementations override this so a pixel that needs
         * color, bucket and height estimate classifies once instead of once
         * per accessor.
         */
        default long resolveHolder(@Nullable Holder<Biome> holder) {
            int estimate = holder == null ? 0
                    : TerrainClassifier.categoryHeight(TerrainClassifier.classify(holder));
            return pack(colorForHolder(holder), bucketForHolder(holder), estimate);
        }

        /** One lookup for a storage biome id (fast path), same packing as {@link #resolveHolder}. */
        default long resolveId(short id) {
            return pack(colorForId(id), bucketForId(id), NO_ESTIMATE);
        }

        /** Packs the fused style triple; see {@link #resolveHolder}. */
        static long pack(int argb, int bucket, int estimatedHeight) {
            return ((long) argb << 32) | ((bucket & 0xFFFFL) << 16) | (estimatedHeight & 0xFFFFL);
        }

        /** ARGB color slot of a packed style; see {@link #resolveHolder}. */
        static int argbOf(long style) {
            return (int) (style >>> 32);
        }

        /** Statistics bucket slot of a packed style; -1 keeps its sign. */
        static int bucketOf(long style) {
            return (short) (style >>> 16);
        }

        /** Estimated-height slot of a packed style; {@link #NO_ESTIMATE} when unknown. */
        static int estimatedHeightOf(long style) {
            return (int) (style & 0xFFFF);
        }
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
        public long resolveHolder(@Nullable Holder<Biome> holder) {
            if (holder == null) {
                // estimateHeight(null) has always been 0, not UNKNOWN's 70.
                return TerrainColorSource.pack(TerrainCategory.UNKNOWN.argbColor(), TerrainCategory.UNKNOWN.ordinal(), 0);
            }
            TerrainCategory category = TerrainClassifier.classify(holder);
            return TerrainColorSource.pack(category.argbColor(), category.ordinal(),
                    TerrainClassifier.categoryHeight(category));
        }

        @Override
        public long resolveId(short id) {
            if (resolver != null) {
                return TerrainColorSource.pack(resolver.category(id).argbColor(), resolver.category(id).ordinal(),
                        resolver.estimatedHeight(id));
            }
            return TerrainColorSource.pack(TerrainCategory.UNKNOWN.argbColor(), TerrainCategory.UNKNOWN.ordinal(),
                    TerrainClassifier.categoryHeight(TerrainCategory.UNKNOWN));
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
        public long resolveHolder(@Nullable Holder<Biome> holder) {
            short id = idForHolder(holder);
            int argb = id >= 0 ? colorById[id] : TerrainCategory.UNKNOWN.argbColor();
            int estimate = holder == null ? 0
                    : TerrainClassifier.categoryHeight(TerrainClassifier.classify(holder));
            return TerrainColorSource.pack(argb, id, estimate);
        }

        @Override
        public long resolveId(short id) {
            // The facts resolver owns the height estimates on this path; the
            // color table does not carry them.
            return TerrainColorSource.pack(knownColor(id) ? colorById[id] : TerrainCategory.UNKNOWN.argbColor(),
                    id, NO_ESTIMATE);
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
                    .map(key -> idByTag.getOrDefault(key.location().toString(), (short) -1))
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
        // Colors never depend on heights, so when neither the contour overlay
        // nor the heightmap consumes the height field the whole height channel
        // -- per-tile arrays, the batch probe sweep, the per-pixel probe
        // ladder -- is skipped and the metadata says so.
        final boolean heightsRequested = spec.exportContours() || spec.exportHeightmap();
        AtomicBoolean stopping = new AtomicBoolean();
        BooleanSupplier taskCancelled = () -> stopping.get() || cancelled.getAsBoolean();
        ExecutorService workers = Executors.newFixedThreadPool(workerThreads, new ExportThreadFactory());
        ExecutorCompletionService<TileResult> completions = new ExecutorCompletionService<>(workers);
        Set<Future<TileResult>> inFlight = new HashSet<>();
        boolean complete = false;
        try {
            int[] colors = new int[width * height];
            short[] heights = heightsRequested ? new short[width * height] : null;
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
                            heightProbe, biomeFacts, heightsRequested, yMin, ySpan, taskCancelled, progress)));
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
                if (spec.heightSampleStride() > 1) {
                    // Interpolated heights: render onto a copy and keep only
                    // contour components that connect into larger structures.
                    // Sampling every stride-th pixel and interpolating turns
                    // node-scale terrain oscillation into per-cell bumps, and
                    // every bump straddling a contour level renders as an
                    // isolated ring or cross locked to the stride lattice --
                    // on near-flat terrain a regular field of dots covering
                    // whole oceans. The bumps are the interpolation's shape,
                    // not quantization noise, so no amount of smoothing or
                    // level shifting removes them; dropping the small
                    // disconnected components does, and everything that ties
                    // into the real contour network survives.
                    int[] contoured = colors.clone();
                    overlayContours(heights, contoured, width, height, spec.contourInterval(), cancelled);
                    copyLargeContourComponents(contoured, colors, width, height, 64, cancelled);
                } else {
                    overlayContours(heights, colors, width, height, spec.contourInterval(), cancelled);
                }
            }
            if (spec.gridIntervalBlocks() > 0) {
                drawGridOverlay(colors, spec, width, height, cancelled);
            }
            checkCancelled(cancelled);
            // Pure-Java encoder: palette for the nine category colors, truecolor
            // otherwise, cancellable between rows (the native encode was
            // uninterruptible for the whole file).
            PngWriter.writeArgb(partPath, colors, width, height, cancelled);
            if (writeHeightmap) {
                checkCancelled(cancelled);
                // Grayscale height field from the same offsets the contour
                // overlay consumes, written straight from the short field.
                PngWriter.writeGray(heightPart, heights, ySpan, width, height, cancelled);
            }
            checkCancelled(cancelled);
            Files.writeString(metaPart, buildMetadata(spec, width, height, timestamp,
                    heightsRequested, heightProbe != null, realHeightPixels, exportContext, yMin, ySpan,
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

    /**
     * Contour overlay: one full-image render per level, minor first then
     * major. Byte-identical to the former two-row strip implementation: a
     * strip's bottom row was always recomputed as the next strip's top row,
     * so only the top-row writes survived -- exactly what the full-image
     * render produces -- and the strips' per-row copies are gone.
     */
    static void overlayContours(short[] heights, int[] colors, int width, int height,
                                int interval, BooleanSupplier cancelled) {
        int[] intervals = {Math.max(1, interval / 5), interval};
        int[] lineColors = {0x608B6914, 0xC08B4513};
        for (int pass = 0; pass < intervals.length; pass++) {
            checkCancelled(cancelled);
            // Blending treats each channel identically, so the renderer also accepts ARGB buffers.
            ContourRenderer renderer = new ContourRenderer(intervals[pass], false,
                    lineColors[pass], lineColors[pass]);
            renderer.render(heights, colors, width, height);
        }
    }

    /**
     * Copies from {@code contoured} back into {@code target} only the pixels
     * of 8-connected contour components larger than {@code maxPixels}.
     * {@code target} holds the pre-contour colors, so a pixel differs between
     * the two exactly where the overlay drew. Small isolated components --
     * the interpolation rings and crosses of the stride modes -- are dropped;
     * anything tied into the larger contour network is kept. Cancellation is
     * checked per row of the component scan.
     */
    static void copyLargeContourComponents(int[] contoured, int[] target, int width, int height,
                                           int maxPixels, BooleanSupplier cancelled) {
        final int size = width * height;
        boolean[] drawn = new boolean[size];
        for (int i = 0; i < size; i++) {
            drawn[i] = contoured[i] != target[i];
        }
        boolean[] seen = new boolean[size];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        java.util.ArrayList<Integer> members = new java.util.ArrayList<>();
        for (int start = 0; start < size; start++) {
            if (seen[start] || !drawn[start]) {
                continue;
            }
            queue.add(start);
            seen[start] = true;
            members.clear();
            while (!queue.isEmpty()) {
                int idx = queue.poll();
                members.add(idx);
                int x = idx % width;
                int y = idx / width;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= width) {
                            continue;
                        }
                        int n = ny * width + nx;
                        if (!seen[n] && drawn[n]) {
                            seen[n] = true;
                            queue.add(n);
                        }
                    }
                }
            }
            if (members.size() > maxPixels) {
                for (int i = 0; i < members.size(); i++) {
                    target[members.get(i)] = contoured[members.get(i)];
                }
            }
            if ((start & 0xFFFF) == 0) {
                checkCancelled(cancelled);
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
            boolean heightsRequested,
            int yMin,
            int ySpan,
            BooleanSupplier cancelled,
            LongConsumer progress
    ) {
        int[] pixels = new int[task.tileWidth * task.tileHeight];
        short[] heights = heightsRequested ? new short[task.tileWidth * task.tileHeight] : null;
        int realHeightCount = 0;
        long[] statCounts = new long[colorSource.bucketCount()];
        int minBlockX = spec.minBlockX();
        int minBlockZ = spec.minBlockZ();
        int bpp = spec.blocksPerPixel();
        final int tileMinBlockX = minBlockX + task.startX * bpp;
        final int tileMinBlockZ = minBlockZ + task.startY * bpp;

        // Per-tile quart memo (bpp < 4 only): with fewer than four blocks per
        // pixel, neighbouring pixels share a quart column and the sampler
        // would answer them identically, so one lookup serves the whole
        // 4x4 block square. Tile origins are TILE_SIZE * bpp blocks apart --
        // a multiple of four -- so a quart cell never straddles a tile
        // boundary, and every piece of state is thread-confined. Opt-in
        // through the QuartBiomeSampler marker: a sampler without the quart
        // contract stays exactly per-pixel. Indices are relative to the
        // tile's own first block, so off-grid export origins only shift the
        // base, never the cell count.
        final boolean memoQuarts = bpp < 4 && sampler instanceof QuartBiomeSampler;
        final int quartBaseX = tileMinBlockX >> 2;
        final int quartBaseZ = tileMinBlockZ >> 2;
        // Offset-aware cell count: (tileMinBlockX & 3) can add one column at the right edge.
        final int quartCols = memoQuarts ? (((task.tileWidth - 1) * bpp + (tileMinBlockX & 3)) >> 2) + 1 : 1;
        final int quartRows = memoQuarts ? (((task.tileHeight - 1) * bpp + (tileMinBlockZ & 3)) >> 2) + 1 : 1;
        final QuartMemo quartMemo = memoQuarts ? new QuartMemo(quartCols * quartRows) : null;
        // Fused-style memo: biome holders are canonical, so an identity probe
        // resolves each distinct holder (color+bucket+estimate) once per tile.
        final StyleMemo styleMemo = new StyleMemo();

        // Batch height pass: a cell-lattice sampler can answer the whole tile in
        // one sweep, so ask it once per tile instead of once per pixel. Falls
        // back to the per-pixel probe (and then to the estimate) when the batch
        // sampler is absent or declines. Never invoked when no consumer reads
        // the height field. With heightSampleStride > 1 the sweep covers a
        // coarse grid (every stride-th pixel) and the per-pixel heights are
        // interpolated from it -- an explicit quality/speed dial, so only the
        // exact grid nodes count as real heights in the metadata.
        final int stride = heightsRequested ? Math.max(1, spec.heightSampleStride()) : 1;
        final int gridWidth = (task.tileWidth + stride - 1) / stride;
        final int gridHeight = (task.tileHeight + stride - 1) / stride;
        int[] batchHeights = null;
        if (heightsRequested && heightProbe instanceof BatchHeightProbe batch) {
            int[] filled = new int[gridWidth * gridHeight];
            java.util.Arrays.fill(filled, Integer.MIN_VALUE);
            try {
                batch.fillGrid(tileMinBlockX, tileMinBlockZ, bpp * stride,
                        gridWidth, gridHeight, filled, cancelled);
                batchHeights = filled;
            } catch (CancellationException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Terrain export interrupted");
            } catch (Exception e) {
                // A batch failure must not fail the export: drop to the
                // per-pixel probe and let the estimate cover the rest.
                batchHeights = null;
            }
        }

        // Storage fast-path band: one bulk query per tile instead of one probe
        // call per pixel; -1 cells fall back to the per-pixel probe, so the
        // hit/miss semantics stay exactly the per-pixel probe's.
        short[] storedBand = null;
        if (biomeFacts != null) {
            short[] band = new short[task.tileWidth * task.tileHeight];
            java.util.Arrays.fill(band, (short) -1);
            try {
                if (biomeFacts.probe().biomeIdBand(tileMinBlockX, tileMinBlockZ, bpp,
                        task.tileWidth, task.tileHeight, band, cancelled)) {
                    storedBand = band;
                }
            } catch (CancellationException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Terrain export interrupted");
            } catch (Exception e) {
                // A bulk failure must not fail the export: per-pixel probes cover it.
                storedBand = null;
            }
        }

        for (int row = 0; row < task.tileHeight; row++) {
            checkCancelled(cancelled);
            int pixelZ = task.startY + row;
            int blockZ = minBlockZ + pixelZ * bpp;
            int rowOffset = row * task.tileWidth;
            final int quartRowBase = memoQuarts ? ((blockZ >> 2) - quartBaseZ) * quartCols : 0;

            for (int col = 0; col < task.tileWidth; col++) {
                int pixelX = task.startX + col;
                int blockX = minBlockX + pixelX * bpp;

                // Real sampled height wins; estimation is the explicit fallback.
                // Probed before the biome lookup so the storage fast path below
                // can still honor real heights.
                Integer realHeight = null;
                if (heightsRequested) {
                    if (batchHeights != null) {
                        realHeight = stride == 1
                                ? orUnknown(batchHeights[rowOffset + col])
                                : interpolateCoarse(batchHeights, gridWidth, gridHeight, stride, col, row);
                    } else if (heightProbe != null) {
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
                }

                // Storage fast path: reuse the preview storage's biome id for
                // this column when its chunk completed sampling for the same
                // worldgen context. The resolver rejects unknown ids; anything
                // else (unsampled chunk, misaligned id) falls through to the
                // noise sampler unchanged. Fact hits count as estimated heights
                // in the metadata, matching the estimate fallback below.
                if (biomeFacts != null) {
                    Short stored = null;
                    if (storedBand != null) {
                        final short banded = storedBand[rowOffset + col];
                        if (banded >= 0) {
                            stored = banded;
                        }
                    }
                    if (stored == null) {
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
                    }
                    if (stored != null && biomeFacts.resolver().known(stored)) {
                        if (heightsRequested) {
                            if (realHeight != null) {
                                // Interpolated coarse values fill non-node pixels;
                                // the node test only gates the metadata counter.
                                // Writing the estimate here instead would embed
                                // biome-constant plateaus (plains 70, ocean 50)
                                // under real node values, and every node/plateau
                                // gap straddles a minor contour level -- the
                                // lattice-locked checkerboard of spurious lines.
                                if (exactHeightNode(stride, col, row)) {
                                    realHeightCount++;
                                }
                                heights[rowOffset + col] = toHeightFieldOffset(realHeight, yMin, ySpan);
                            } else {
                                heights[rowOffset + col] = toHeightFieldOffset(
                                        biomeFacts.resolver().estimatedHeight(stored), yMin, ySpan);
                            }
                        }
                        long style = colorSource.resolveId(stored);
                        pixels[rowOffset + col] = TerrainColorSource.argbOf(style);
                        int storedBucket = TerrainColorSource.bucketOf(style);
                        if (storedBucket >= 0) {
                            statCounts[storedBucket]++;
                        }
                        continue;
                    }
                }

                Holder<Biome> biomeHolder;
                final int quartIndex = memoQuarts
                        ? quartRowBase + ((blockX >> 2) - quartBaseX)
                        : 0;
                if (memoQuarts && quartMemo.isDone(quartIndex)) {
                    biomeHolder = quartMemo.holder(quartIndex);
                } else {
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
                    if (memoQuarts) {
                        // A failure stores null on purpose: null must not be
                        // re-sampled sixteen times for the same quart.
                        quartMemo.store(quartIndex, biomeHolder);
                    }
                }

                long style = styleMemo.resolve(colorSource, biomeHolder);
                if (heightsRequested) {
                    if (realHeight != null) {
                        // Same contract as the storage fast path: interpolated
                        // coarse values fill non-node pixels, the node test only
                        // gates the metadata counter, and the estimate covers
                        // only pixels whose coarse neighborhood holds no data.
                        if (exactHeightNode(stride, col, row)) {
                            realHeightCount++;
                        }
                        heights[rowOffset + col] = toHeightFieldOffset(realHeight, yMin, ySpan);
                    } else {
                        int estimate = TerrainColorSource.estimatedHeightOf(style);
                        if (estimate == TerrainColorSource.NO_ESTIMATE) {
                            // Only reachable for external sources that fill no
                            // estimate slot; 0 matches the old null fallback.
                            estimate = 0;
                        }
                        heights[rowOffset + col] = toHeightFieldOffset(estimate, yMin, ySpan);
                    }
                }

                pixels[rowOffset + col] = TerrainColorSource.argbOf(style);
                int holderBucket = TerrainColorSource.bucketOf(style);
                if (holderBucket >= 0) {
                    statCounts[holderBucket]++;
                }
            }

            checkCancelled(cancelled);
        }

        // One report per tile: the consumer is a contended AtomicLong (and, in
        // the batch path, a formatted status string rebuilt from it). A 64x64
        // tile is far finer than the status poll can show. Cancellation
        // checks stay per row so cancel latency stays <= one row.
        progress.accept((long) task.tileWidth * task.tileHeight);

        return new TileResult(task.startX, task.startY, task.tileWidth, task.tileHeight, pixels, heights, realHeightCount,
                statCounts);
    }

    /** Integer.MIN_VALUE maps to "no height" so the estimate covers the pixel. */
    @Nullable
    private static Integer orUnknown(int batched) {
        return batched != Integer.MIN_VALUE ? batched : null;
    }

    /** Whether a pixel sits exactly on the coarse grid (its height is real, not interpolated). */
    private static boolean exactHeightNode(int stride, int col, int row) {
        return stride == 1 || (col % stride == 0 && row % stride == 0);
    }

    /**
     * Bilinear lookup in the coarse height grid the batch probe filled for
     * {@code stride > 1}. Unknown corners drop out of the weighted average; a
     * fully unknown neighborhood stays null so the biome estimate covers the
     * pixel.
     */
    @Nullable
    private static Integer interpolateCoarse(int[] coarse, int gridWidth, int gridHeight,
                                             int stride, int col, int row) {
        final int gx = col / stride;
        final int gz = row / stride;
        final int gx1 = Math.min(gx + 1, gridWidth - 1);
        final int gz1 = Math.min(gz + 1, gridHeight - 1);
        final int fx = col - gx * stride;
        final int fz = row - gz * stride;
        final int c00 = coarse[gz * gridWidth + gx];
        final int c10 = coarse[gz * gridWidth + gx1];
        final int c01 = coarse[gz1 * gridWidth + gx];
        final int c11 = coarse[gz1 * gridWidth + gx1];
        final int w00 = (stride - fx) * (stride - fz);
        final int w10 = fx * (stride - fz);
        final int w01 = (stride - fx) * fz;
        final int w11 = fx * fz;
        long sum = 0L;
        long weight = 0L;
        if (c00 != Integer.MIN_VALUE) {
            sum += (long) c00 * w00;
            weight += w00;
        }
        if (c10 != Integer.MIN_VALUE) {
            sum += (long) c10 * w10;
            weight += w10;
        }
        if (c01 != Integer.MIN_VALUE) {
            sum += (long) c01 * w01;
            weight += w01;
        }
        if (c11 != Integer.MIN_VALUE) {
            sum += (long) c11 * w11;
            weight += w11;
        }
        if (weight == 0L) {
            return null;
        }
        return (int) Math.round((double) sum / weight);
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

    /** Sink abstraction over the image fill so run merging is unit-testable. */
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
                                        boolean heightsRequested, boolean heightProbeSupplied,
                                        int realHeightPixels,
                                        @Nullable ExportContext exportContext, int yMin, int ySpan,
                                        @Nullable String heightmapFile, TerrainColorSource colorSource,
                                        long[] statCounts) {
        String heightSource;
        if (!heightsRequested) {
            heightSource = "none";
        } else if (!heightProbeSupplied) {
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
        metadata.addProperty("heightSampleStride", spec.heightSampleStride());
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

    /**
     * {@link BiomeSampler} whose result depends only on the block's quart
     * column: for a fixed quart Y, {@code sample(bx, bz)} is constant over the
     * 4x4 block square containing {@code (bx, bz)}. The vanilla wiring samples
     * through {@code QuartPos.fromBlock} at a fixed {@code spec.exportY}, so it
     * holds; the exporter then memoises one lookup per quart inside a tile
     * instead of one per pixel. Samplers without that contract must stay plain
     * {@link BiomeSampler}s so they keep their exact per-pixel behavior.
     */
    public interface QuartBiomeSampler extends BiomeSampler {}

    /** Export result. */
    public record Result(Path pngPath, Path metadataPath) {}

    /** Tile sampling task descriptor. */
    private record TileTask(int startX, int startY, int tileWidth, int tileHeight) {}

    /** Tile sampling result. */
    private record TileResult(int startX, int startY, int tileWidth, int rowCount, int[] pixels, short[] heights,
                              int realHeightCount, long[] statCounts) {}

    /**
     * Thread-confined per-tile memo of quart-column biome holders. The
     * {@code done} flag is separate from the holder because a sampling failure
     * stores null, and null must not be re-sampled once per pixel.
     */
    private static final class QuartMemo {
        private final Holder<Biome>[] holders;
        private final boolean[] done;

        @SuppressWarnings("unchecked")
        QuartMemo(int cells) {
            holders = new Holder[cells];
            done = new boolean[cells];
        }

        boolean isDone(int cell) {
            return done[cell];
        }

        Holder<Biome> holder(int cell) {
            return holders[cell];
        }

        void store(int cell, @Nullable Holder<Biome> holder) {
            holders[cell] = holder;
            done[cell] = true;
        }
    }

    /**
     * Per-tile identity memo for fused style lookups. One instance per
     * {@code sampleTile} call, never shared. Identity (not equals) on purpose:
     * biome holders are canonical in the registry, so an identity probe
     * resolves each distinct holder once; a non-canonical holder simply misses
     * and resolves per pixel, which stays correct. Past {@link #MAX_ENTRIES}
     * the memo stops caching and resolves every pixel.
     */
    private static final class StyleMemo {
        private static final int MAX_ENTRIES = 4096;

        private Holder<Biome>[] keys = new Holder[256];
        private long[] values = new long[256];
        private int size;
        private boolean nullDone;
        private long nullStyle;

        long resolve(TerrainColorSource source, @Nullable Holder<Biome> holder) {
            if (holder == null) {
                if (!nullDone) {
                    nullStyle = source.resolveHolder(null);
                    nullDone = true;
                }
                return nullStyle;
            }
            int mask = keys.length - 1;
            int slot = System.identityHashCode(holder) & mask;
            while (true) {
                Holder<Biome> key = keys[slot];
                if (key == holder) {
                    return values[slot];
                }
                if (key == null) {
                    break;
                }
                slot = (slot + 1) & mask;
            }
            long style = source.resolveHolder(holder);
            if ((size + 1) * 2 > keys.length) {
                if (keys.length >= MAX_ENTRIES) {
                    return style;
                }
                grow();
                mask = keys.length - 1;
                slot = System.identityHashCode(holder) & mask;
                while (keys[slot] != null) {
                    slot = (slot + 1) & mask;
                }
            }
            keys[slot] = holder;
            values[slot] = style;
            size++;
            return style;
        }

        @SuppressWarnings("unchecked")
        private void grow() {
            Holder<Biome>[] oldKeys = keys;
            long[] oldValues = values;
            keys = new Holder[oldKeys.length * 2];
            values = new long[oldKeys.length * 2];
            int mask = keys.length - 1;
            for (int i = 0; i < oldKeys.length; i++) {
                Holder<Biome> key = oldKeys[i];
                if (key != null) {
                    int slot = System.identityHashCode(key) & mask;
                    while (keys[slot] != null) {
                        slot = (slot + 1) & mask;
                    }
                    keys[slot] = key;
                    values[slot] = oldValues[i];
                }
            }
        }
    }

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
