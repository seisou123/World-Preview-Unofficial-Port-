// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.export.TerrainCategory;
import caeruleusTait.world.preview.backend.export.TerrainClassifier;
import caeruleusTait.world.preview.backend.export.TerrainExportColorMode;
import caeruleusTait.world.preview.backend.export.TerrainExportController;
import caeruleusTait.world.preview.backend.export.TerrainExportSpec;
import caeruleusTait.world.preview.backend.export.TerrainMapExporter;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

/**
 * Terrain-export wiring owned by the {@link PreviewContainer}: the export
 * controller plus the volatile worldgen probes captured per setup
 * (quart-space noise biome query and live height sampler). The container
 * delegates its public export methods here; the per-export assembly
 * (biome facts, color table, height fallback) lives in this class.
 */
final class TerrainExportGateway {
    /**
     * Quart-space biome query over the worldgen context's noise resolver.
     * The export layer is chosen per export (the preview center's Y at start
     * time), so only this resolver-shaped query is captured at setup; the
     * per-export {@code BiomeSampler} is assembled in {@link #start}.
     */
    @FunctionalInterface
    interface NoiseBiomeQuery {
        Holder<Biome> get(int quartX, int quartY, int quartZ);
    }

    private final TerrainExportController controller;
    private final WorkManager workManager;
    private volatile NoiseBiomeQuery noise;
    /**
     * Live worldgen height sampler for terrain export, or null when the
     * dimension's generator is not noise-based. Replaces the storage-backed
     * probe whenever it is available: the storage only covers the viewport, so
     * a large export would otherwise be sampled from the biome-based estimate
     * outside that rectangle.
     */
    private volatile TerrainMapExporter.HeightProbe height;

    TerrainExportGateway(WorkManager workManager, int threadCount) {
        this.workManager = workManager;
        this.controller = new TerrainExportController(threadCount);
    }

    boolean isReady() {
        return noise != null;
    }

    /** Captures the per-setup probes built by the container's search-context setup. */
    void updateContext(NoiseBiomeQuery noise, TerrainMapExporter.HeightProbe height) {
        this.noise = noise;
        this.height = height;
    }

    /** Drops the stale probes first so no new export can capture them, then cancels. */
    void invalidate() {
        noise = null;
        height = null;
        controller.cancel();
    }

    void cancel() {
        controller.cancel();
    }

    TerrainExportController.Status status() {
        return controller.status();
    }

    void close() {
        controller.close();
    }

    /** @return false when the export was not started (busy, no sampler, or stale worldgen context). */
    boolean start(TerrainExportSpec spec, WorldPreviewConfig cfg, @Nullable PreviewData previewData,
                  Path outputDir, long samplerContextEpoch) {
        if (controller.isRunning()) return false;
        // Capture the query once: a world switch mid-export nulls the field,
        // but the running export must keep sampling its own captured context
        // until the epoch guard and cancellation stop it.
        NoiseBiomeQuery noise = this.noise;
        if (noise == null) return false;
        // Lineage guard: the sampler was built for a specific worldgen context.
        // After a seed/dimension/generator switch the old sampler must not
        // produce exports labeled with (or sampling) the new world.
        if (samplerContextEpoch != workManager.epoch()) {
            LOGGER.warn("Terrain export rejected: worldgen context changed since the sampler was built");
            return false;
        }
        // The export evaluates biomes at the Y layer the preview is scrolled
        // to (spec.exportY, captured at start time), so the exported map
        // matches the viewed layer and the storage fast path below hits the
        // sampled layer.
        final int exportQuartY = net.minecraft.core.QuartPos.fromBlock(spec.exportY());
        // QuartBiomeSampler: the noise query answers per quart column, so the
        // exporter can share one lookup across the pixels of a quart cell.
        TerrainMapExporter.QuartBiomeSampler sampler = (blockX, blockZ) -> noise.get(
                net.minecraft.core.QuartPos.fromBlock(blockX), exportQuartY,
                net.minecraft.core.QuartPos.fromBlock(blockZ));
        TerrainMapExporter.BiomeFacts biomeFacts = buildBiomeFacts(spec, cfg, previewData);
        TerrainMapExporter.TerrainColorSource colorSource;
        if (spec.colorMode() == TerrainExportColorMode.BIOMES) {
            TerrainMapExporter.BiomeColorTable colorTable = buildBiomeColors(previewData);
            colorSource = colorTable != null
                    ? TerrainMapExporter.biomes(colorTable)
                    : TerrainMapExporter.categories(biomeFacts == null ? null : biomeFacts.resolver());
        } else {
            colorSource = TerrainMapExporter.categories(biomeFacts == null ? null : biomeFacts.resolver());
        }
        // Live worldgen sampling gives every pixel a real height, so the export
        // no longer depends on how much of the map the viewport happened to
        // sample. The storage probe stays as the fallback for dimensions whose
        // generator is not noise-based.
        TerrainMapExporter.HeightProbe resolvedHeightProbe = height;
        if (resolvedHeightProbe == null) {
            resolvedHeightProbe = heightProbe();
        }
        return controller.start(spec, sampler, colorSource, resolvedHeightProbe, exportOrigin(),
                biomeFacts, workManager.yMin(), workManager.yMax(), outputDir);
    }

    /**
     * Storage-backed biome facts for terrain export: reuses the preview
     * storage's already-sampled biome ids (same seed, same worldgen epoch as
     * the export sampler) instead of re-running the noise sampler per pixel.
     * Returns {@code null} — pure noise-sampling fallback — whenever the
     * facts cannot be trusted (no storage, no preview data, no worldgen
     * context, or table build failure). Storage access is strictly read-only.
     */
    @Nullable
    TerrainMapExporter.BiomeFacts buildBiomeFacts(TerrainExportSpec spec, WorldPreviewConfig cfg,
                                                  @Nullable PreviewData previewData) {
        final var storage = workManager.previewStorage();
        if (storage == null || previewData == null || workManager.worldgenContext() == null) {
            return null;
        }

        // Per-id tables: storage biome ids index into previewData's biome
        // list. A null slot marks an id that must not be trusted (misaligned
        // storage id or unregistered biome) and sends the exporter back to
        // noise sampling for that pixel.
        final PreviewData.BiomeData[] biomeDataById = previewData.biomeId2BiomeData();
        final TerrainCategory[] categoryById = new TerrainCategory[biomeDataById.length];
        final short[] estimatedHeightById = new short[biomeDataById.length];
        try {
            Registry<Biome> biomeRegistry = workManager.worldgenContext().registryAccess()
                    .compositeAccess().lookupOrThrow(Registries.BIOME);
            Map<Identifier, Holder.Reference<Biome>> registryHolders = biomeRegistry.listElements()
                    .collect(Collectors.toMap(x -> x.key().identifier(), x -> x));
            for (int id = 0; id < biomeDataById.length; id++) {
                final PreviewData.BiomeData biomeData = biomeDataById[id];
                if (biomeData == null || biomeData.tag() == null) {
                    continue;
                }
                Holder.Reference<Biome> holder = registryHolders.get(biomeData.tag());
                if (holder == null) {
                    // Biome is in the biome source but not in the registry;
                    // standalone holders classify through the id-keyword
                    // fallback, matching the sidebar biome list behavior.
                    holder = Holder.Reference.createStandAlone(
                            biomeRegistry, ResourceKey.create(Registries.BIOME, biomeData.tag()));
                }
                final TerrainCategory category = TerrainClassifier.classify(holder);
                categoryById[id] = category;
                estimatedHeightById[id] = TerrainClassifier.categoryHeight(category);
            }
        } catch (Exception e) {
            LOGGER.warn("Terrain export biome facts unavailable; falling back to noise sampling", e);
            return null;
        }

        // The export sampler evaluates biomes at the spec's export Y (the
        // preview center's Y at start time), so reuse the storage's matching
        // biome layer. Completion bits live on the primary section layer:
        // y=0 with full-vertical chunk sampling, the sampled layer itself
        // otherwise. An unsampled or partially sampled chunk makes the probe
        // answer null (noise-sampling fallback for that pixel) — never a
        // stale or missing value.  Only FULL-RESOLUTION completion counts:
        // a chunk the drag tier filled with its coarse sampler is treated as
        // unsampled here, so the export falls back to sampling instead of
        // baking a 4x4-quart block into the image.
        final int dataQuartY = net.minecraft.core.QuartPos.fromBlock(spec.exportY());
        final int completionQuartY = cfg.buildFullVertChunk
                ? net.minecraft.core.QuartPos.fromBlock(0) : dataQuartY;
        TerrainMapExporter.BiomeIdProbe probe = new TerrainMapExporter.BiomeIdProbe() {
            @Override
            public Short biomeIdAt(int blockX, int blockZ) {
                int qx = net.minecraft.core.QuartPos.fromBlock(blockX);
                int qz = net.minecraft.core.QuartPos.fromBlock(blockZ);
                if (!storage.isChunkSampledFine(qx, completionQuartY, qz,
                        caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_BIOME)) {
                    return null;
                }
                short v = storage.getRawData4(qx, dataQuartY, qz,
                        caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_BIOME);
                return v == Short.MIN_VALUE ? null : v;
            }

            @Override
            public boolean biomeIdBand(int minBlockX, int minBlockZ, int step, int width, int height,
                                       short[] out, java.util.function.BooleanSupplier cancelled) {
                // -1 is the exporter's "unknown" sentinel; storage keeps
                // Short.MIN_VALUE, so unmapped cells translate after the read.
                java.util.Arrays.fill(out, Short.MIN_VALUE);
                storage.getRawData4Band(net.minecraft.core.QuartPos.fromBlock(minBlockX),
                        net.minecraft.core.QuartPos.fromBlock(minBlockZ), step >> 2, width, height,
                        dataQuartY, completionQuartY,
                        caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_BIOME, out);
                for (int i = 0; i < out.length; i++) {
                    if (out[i] == Short.MIN_VALUE) {
                        out[i] = -1;
                    }
                }
                return true;
            }
        };

        return new TerrainMapExporter.BiomeFacts(
                probe, new TerrainMapExporter.IdTableResolver(categoryById, estimatedHeightById));
    }

    /**
     * Per-id biome color table for the export's biome color mode: preview
     * color, registry tag and display name for every registered biome id,
     * sharing the storage id space with {@link #buildBiomeFacts}.
     * Registry-level data with no storage dependency, so the batch path (no
     * facts) can use it too. Returns {@code null} when preview data is
     * missing or the table cannot be built; callers fall back to the
     * category color source.
     */
    @Nullable
    TerrainMapExporter.BiomeColorTable buildBiomeColors(@Nullable PreviewData previewData) {
        if (previewData == null) {
            return null;
        }
        try {
            final PreviewData.BiomeData[] biomeDataById = previewData.biomeId2BiomeData();
            final int[] colorById = new int[biomeDataById.length];
            final String[] tagById = new String[biomeDataById.length];
            final String[] nameById = new String[biomeDataById.length];
            for (int id = 0; id < biomeDataById.length; id++) {
                final PreviewData.BiomeData biomeData = biomeDataById[id];
                if (biomeData == null || biomeData.tag() == null) {
                    continue;
                }
                // The preview engine paints biome pixels as color|0xFF000000
                // (textureColor), so the export uses the same opaque ARGB.
                colorById[id] = biomeData.color() | 0xFF000000;
                tagById[id] = biomeData.tag().toString();
                nameById[id] = biomeData.name();
            }
            return new TerrainMapExporter.BiomeColorTable(colorById, tagById, nameById);
        } catch (Exception e) {
            LOGGER.warn("Terrain export biome color table unavailable; falling back to category colors", e);
            return null;
        }
    }

    /** Real-height probe over the live preview storage; null when unavailable. */
    @Nullable
    private TerrainMapExporter.HeightProbe heightProbe() {
        var storage = workManager.previewStorage();
        if (storage == null) {
            return null;
        }
        return (blockX, blockZ) -> {
            short h = storage.getRawData4(
                    net.minecraft.core.QuartPos.fromBlock(blockX), 0,
                    net.minecraft.core.QuartPos.fromBlock(blockZ),
                    caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_HEIGHT);
            return h == Short.MIN_VALUE ? null : (int) h;
        };
    }

    /** World lineage written into terrain export metadata. */
    @Nullable
    private TerrainMapExporter.ExportContext exportOrigin() {
        var ctx = workManager.worldgenContext();
        return ctx == null ? null : new TerrainMapExporter.ExportContext(Long.toString(ctx.seed()), ctx.dimension());
    }
}
