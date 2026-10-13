package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.backend.analysis.WorldgenContext;
import caeruleusTait.world.preview.backend.export.TerrainCategory;
import caeruleusTait.world.preview.backend.export.TerrainExportColorMode;
import caeruleusTait.world.preview.backend.export.TerrainExportController;
import caeruleusTait.world.preview.backend.export.TerrainExportSpec;
import caeruleusTait.world.preview.backend.export.TerrainMapExporter;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.PanelRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

/**
 * Terrain map export screen: settings panel on the left, export status and
 * category legend stacked on the right — the same two-panel visual language
 * as the seed search and world analysis screens. Every control stays visible
 * at once (the former export/settings tab pair is gone), so nothing re-inits
 * on a tab switch and the fixed rows can no longer overflow small gui
 * heights such as GUI scale 4.
 * Adapted for the MC 1.21.11 GuiGraphics API.
 */
public final class TerrainExportScreen extends Screen {

    private final Screen parent;
    private final PreviewContainer previewContainer;
    private boolean hasPrefill;
    private int prefillCenterX;
    private int prefillCenterZ;
    private boolean historyOpen = false;
    private long historyCopiedAtNanos = 0L;
    private final List<HistoryRow> historyRows = new ArrayList<>();
    private final List<int[]> historyRects = new ArrayList<>();

    // History list scrolling (mirrors the analysis panels' interaction set):
    // wheel steps glide, a middle press autoscrolls browser-style, and the
    // thin scrollbar strip jumps under the cursor and drags 1:1.
    private static final int HISTORY_HIT_WIDTH = 6;
    private static final int HISTORY_WHEEL_ROWS = 3;
    /** Rendered width of the history scrollbar track/thumb. */
    private static final int SCROLLBAR_BAR_WIDTH = 3;
    private static final double HISTORY_PAN_DEADZONE_PX = 3.0;
    private static final double HISTORY_PAN_PX_PER_SEC_PER_PX = 12.0;
    private static final double HISTORY_PAN_MAX_PX_PER_SEC = 480.0;
    private static final double HISTORY_SCROLL_EASE_MS = 60.0;
    private int historyScrollOffset;
    private double historyRenderOffset;
    private long historyLastFrameMs = -1;
    private boolean historyScrollbarDragging;
    private double historyScrollbarGrabOffset;
    private boolean historyPanActive;
    private double historyPanAnchorY;

    /** One browsable export history entry (newest first). */
    private record HistoryRow(String time, String seed, String dimension, int centerX, int centerZ, String file) {}

    private int coverageRadius = TerrainExportSpec.DEFAULT_COVERAGE_RADIUS;
    private int blocksPerPixel = TerrainExportSpec.DEFAULT_BLOCKS_PER_PIXEL;
    private boolean exportContours = true;
    private int contourInterval = 10;
    private boolean batchMode = false;
    private boolean exportGrid = false;
    private int gridInterval = 128;
    private boolean exportHeightmap = false;
    private boolean biomeColors = false;

    private AbstractSliderButton radiusSlider;
    private AbstractSliderButton resolutionSlider;
    private AbstractSliderButton contourSlider;
    private AbstractSliderButton gridSlider;
    private Checkbox contourCheckbox;
    private Checkbox gridCheckbox;
    private Checkbox heightmapCheckbox;
    private CycleButton<Boolean> colorCycle;
    private CycleButton<Boolean> batchCycle;
    private Button exportButton;
    private Button cancelButton;

    // Batch export runs on its own single-thread executor, bypassing the
    // per-dimension TerrainExportController state machine.
    private final ExecutorService batchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wp-terrain-batch-export");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean batchCancelled = new AtomicBoolean(false);
    private volatile boolean batchRunning;
    private volatile double batchPct;
    private volatile Component batchStatus;
    private volatile long batchStartNanos;
    private volatile long batchFinishNanos;
    private volatile Path batchOutputDir;
    /** True once a batch run ends with a failure or partial success; paints the bar red. */
    private volatile boolean batchFailed;

    private static final int MX = 8;
    /** Content top below the title; the fixed settings rows all hang off it. */
    private static final int TOP = 26;
    private static final int SH = 20;
    private static final int LH = 12;
    /** Slider step for the coverage radius, keeping round block values reachable. */
    private static final int RADIUS_STEP = 256;
    /** Progress fill: brand teal while running, green when done, red on failure. */
    private static final int BAR_RUNNING = 0xFF2EC4B6;
    private static final int BAR_DONE = 0xFF55FF55;
    private static final int BAR_FAILED = 0xFFFF5555;

    // Fixed settings-panel rows (independent of the screen size). The grid
    // checkbox shares the contours row; the image size and center lines live
    // in the status box where they read as export inputs.
    private static final int CONTOURS_Y = TOP + 16 + 2 * (SH + 4);
    private static final int CONTOUR_SLIDER_Y = CONTOURS_Y + SH + 4;
    private static final int GRID_SLIDER_Y = CONTOUR_SLIDER_Y + SH + 4;
    private static final int HEIGHTMAP_Y = GRID_SLIDER_Y + SH + 4;
    // Status-box rows: progress bar, image size, center, status line.
    private static final int SIZE_Y = TOP + PanelRenderer.PANEL_PAD + 12 + 6;
    private static final int CENTER_Y = SIZE_Y + LH + 2;
    private static final int STATUS_LINE_Y = CENTER_Y + LH + 4;

    // Panel geometry, computed in init().
    private int actionRowY;
    private int panelsBottom;
    private int settingsW;
    private int statusX;
    private int statusW;
    private int statusH;
    private int legendY;
    private int legendH;
    private int legendRows;

    public TerrainExportScreen(Screen parent, PreviewContainer previewContainer) {
        super(WorldPreviewComponents.TERRAIN_EXPORT_TITLE);
        this.parent = parent;
        this.previewContainer = previewContainer;
    }

    /**
     * Opening from the analysis screen: center and radius are prefilled from
     * the analyzed region instead of the live preview center.
     */
    public TerrainExportScreen(Screen parent, PreviewContainer previewContainer,
                               int centerX, int centerZ, int coverageRadius) {
        this(parent, previewContainer);
        this.prefillCenterX = centerX;
        this.prefillCenterZ = centerZ;
        this.coverageRadius = Math.max(TerrainExportSpec.MIN_COVERAGE_RADIUS,
                Math.min(TerrainExportSpec.MAX_COVERAGE_RADIUS, coverageRadius));
        this.hasPrefill = true;
    }

    @Override
    protected void init() {
        clearWidgets();

        actionRowY = height - 32;
        panelsBottom = actionRowY - 8;

        // Two-panel geometry as in the seed search screen: the settings panel
        // takes 36% of the width (at least 240px), the status column the rest.
        settingsW = Math.max(240, (int) (width * 0.36f));
        statusX = MX + settingsW + 8;
        statusW = Math.max(1, width - statusX - MX);

        // The legend panel hugs the panels' bottom edge and keeps exactly the
        // height its wrapped rows need; the status box absorbs the spare.
        legendRows = computeLegendRows(statusW - 2 * PanelRenderer.PANEL_PAD);
        legendH = PanelRenderer.PANEL_PAD + LH + 4 + legendRows * LH + 4;
        legendY = panelsBottom - legendH;
        statusH = Math.max(48, legendY - 8 - TOP);

        // Settings stack, top-anchored under the panel header. The sliders
        // carry their own names in their messages, so no extra label rows.
        int x = MX + PanelRenderer.PANEL_PAD;
        int innerW = settingsW - 2 * PanelRenderer.PANEL_PAD;
        radiusSlider = createRadiusSlider(x, TOP + 16, innerW);
        addRenderableWidget(radiusSlider);
        resolutionSlider = createResolutionSlider(x, TOP + 16 + SH + 4, innerW);
        addRenderableWidget(resolutionSlider);
        contourCheckbox = Checkbox.builder(
                Component.translatable("world_preview.terrain_export.contours"),
                Minecraft.getInstance().font)
                .selected(exportContours)
                .onValueChange((box, val) -> {
                    exportContours = val;
                    if (contourSlider != null) contourSlider.active = val;
                })
                .build();
        contourCheckbox.setPosition(x, CONTOURS_Y);
        addRenderableWidget(contourCheckbox);
        gridCheckbox = Checkbox.builder(
                WorldPreviewComponents.TERRAIN_EXPORT_GRID,
                Minecraft.getInstance().font)
                .selected(exportGrid)
                .onValueChange((box, val) -> {
                    exportGrid = val;
                    if (gridSlider != null) gridSlider.active = val;
                })
                .build();
        gridCheckbox.setPosition(x + contourCheckbox.getWidth() + 6, CONTOURS_Y);
        addRenderableWidget(gridCheckbox);
        contourSlider = createContourSlider(x, CONTOUR_SLIDER_Y, innerW);
        contourSlider.active = exportContours;
        addRenderableWidget(contourSlider);
        gridSlider = createGridSlider(x, GRID_SLIDER_Y, innerW);
        gridSlider.active = exportGrid;
        addRenderableWidget(gridSlider);
        heightmapCheckbox = Checkbox.builder(
                WorldPreviewComponents.TERRAIN_EXPORT_HEIGHTMAP,
                Minecraft.getInstance().font)
                .selected(exportHeightmap)
                .onValueChange((box, val) -> exportHeightmap = val)
                .build();
        heightmapCheckbox.setPosition(x, HEIGHTMAP_Y);
        addRenderableWidget(heightmapCheckbox);
        // Color-mode toggle shares the heightmap row (the grid checkbox
        // pattern); no new fixed row, so the smallest gui heights stay safe.
        colorCycle = CycleButton.booleanBuilder(
                        WorldPreviewComponents.TERRAIN_EXPORT_COLORS_BIOMES,
                        WorldPreviewComponents.TERRAIN_EXPORT_COLORS_CATEGORIES, biomeColors)
                .create(x + heightmapCheckbox.getWidth() + 6, HEIGHTMAP_Y,
                        Math.max(60, innerW - heightmapCheckbox.getWidth() - 6), SH,
                        WorldPreviewComponents.TERRAIN_EXPORT_COLORS, (btn, val) -> biomeColors = val);
        colorCycle.setTooltip(Tooltip.create(WorldPreviewComponents.TERRAIN_EXPORT_COLORS_TOOLTIP));
        addRenderableWidget(colorCycle);

        // Batch toggle hugs the panel bottom (the seed screen's options-row
        // pattern) so it stays inside the panel at every gui height.
        // The history button derives its width from the measured label ("Export
        // History" exceeds a bare 70px button); the batch toggle keeps a 4px gap.
        int historyW = Minecraft.getInstance().font.width(WorldPreviewComponents.TERRAIN_EXPORT_HISTORY) + 12;
        batchCycle = CycleButton.booleanBuilder(CommonComponents.OPTION_ON, CommonComponents.OPTION_OFF, batchMode)
                .create(x, panelsBottom - PanelRenderer.PANEL_PAD - SH, innerW - historyW - 4, SH,
                        WorldPreviewComponents.TERRAIN_EXPORT_BATCH, (btn, val) -> batchMode = val);
        batchCycle.setTooltip(Tooltip.create(WorldPreviewComponents.TERRAIN_EXPORT_BATCH_TOOLTIP));
        addRenderableWidget(batchCycle);
        addRenderableWidget(Button.builder(WorldPreviewComponents.TERRAIN_EXPORT_HISTORY, b -> toggleHistory())
                .bounds(x + innerW - historyW, panelsBottom - PanelRenderer.PANEL_PAD - SH, historyW, SH).build());

        // Action row: start/cancel bottom-left, back bottom-right.
        exportButton = Button.builder(WorldPreviewComponents.TERRAIN_EXPORT_START, b -> doExport())
                .bounds(MX, actionRowY, 90, SH).build();
        cancelButton = Button.builder(WorldPreviewComponents.TERRAIN_EXPORT_CANCEL, b -> cancelExport())
                .bounds(MX + 94, actionRowY, 90, SH).build();
        addRenderableWidget(exportButton);
        addRenderableWidget(cancelButton);
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(width - 88, actionRowY, 80, SH).build());

        updateButtonStates();
    }

    /** Wrapped legend row count for the given inner width (mirrors renderLegendColors). */
    private int computeLegendRows(int innerW) {
        int rows = 1;
        int curX = 0;
        int colorSize = 10;
        int colorTextGap = 3;
        int itemGap = 8;
        for (TerrainCategory cat : TerrainCategory.values()) {
            Component catLabel = Component.translatable(
                    "world_preview.terrain_export.category." + cat.name().toLowerCase());
            int itemW = colorSize + colorTextGap + font.width(catLabel);
            if (curX + itemW > innerW && curX > 0) {
                rows++;
                curX = 0;
            }
            curX += itemW + itemGap;
        }
        return rows;
    }

    private int computeImageSize() {
        return (coverageRadius * 2) / blocksPerPixel;
    }

    /** Whether the current slider combination fits the shared pixel allocation cap. */
    private boolean settingsSupported() {
        return TerrainExportSpec.isSupported(coverageRadius, blocksPerPixel);
    }

    private int snapRadius(double sliderValue) {
        int min = TerrainExportSpec.MIN_COVERAGE_RADIUS;
        int max = TerrainExportSpec.MAX_COVERAGE_RADIUS;
        int raw = min + (int) Math.round(sliderValue * (max - min));
        int snapped = min + Math.round((raw - min) / (float) RADIUS_STEP) * RADIUS_STEP;
        return Math.max(min, Math.min(max, snapped));
    }

    private AbstractSliderButton createRadiusSlider(int x, int y, int w) {
        int min = TerrainExportSpec.MIN_COVERAGE_RADIUS;
        int max = TerrainExportSpec.MAX_COVERAGE_RADIUS;
        int range = max - min;
        double initial = range == 0 ? 0.0 : (double) (coverageRadius - min) / range;
        return new AbstractSliderButton(x, y, w, SH,
                Component.translatable("world_preview.terrain_export.radius_value", coverageRadius), initial) {
            @Override protected void updateMessage() {
                setMessage(Component.translatable("world_preview.terrain_export.radius_value", snapRadius(this.value)));
            }
            @Override protected void applyValue() {
                coverageRadius = snapRadius(this.value);
            }
        };
    }

    private AbstractSliderButton createResolutionSlider(int x, int y, int w) {
        int min = TerrainExportSpec.MIN_BLOCKS_PER_PIXEL;
        int max = TerrainExportSpec.MAX_BLOCKS_PER_PIXEL;
        int range = max - min;
        double initial = range == 0 ? 0.0 : (double) (blocksPerPixel - min) / range;
        return new AbstractSliderButton(x, y, w, SH,
                Component.translatable("world_preview.terrain_export.resolution_value", blocksPerPixel), initial) {
            @Override protected void updateMessage() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                setMessage(Component.translatable("world_preview.terrain_export.resolution_value", v));
            }
            @Override protected void applyValue() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                blocksPerPixel = Math.max(min, Math.min(max, v));
            }
        };
    }

    /** Flips the status box into the export-history list (newest first). */
    private void toggleHistory() {
        historyOpen = !historyOpen;
        historyRows.clear();
        historyScrollOffset = 0;
        historyRenderOffset = 0.0;
        if (!historyOpen) {
            return;
        }
        Path historyFile = WorldPreview.get().configDir().resolve("terrain_exports").resolve("history.jsonl");
        if (!Files.exists(historyFile)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(historyFile);
            // Newest first; the list scrolls, so every entry stays reachable.
            for (int i = lines.size() - 1; i >= 0; i--) {
                try {
                    com.google.gson.JsonObject entry = com.google.gson.JsonParser.parseString(lines.get(i)).getAsJsonObject();
                    String seed = entry.has("seed") ? entry.get("seed").getAsString() : "?";
                    String time = entry.has("timestamp") ? entry.get("timestamp").getAsString() : "";
                    historyRows.add(new HistoryRow(
                            time.length() > 16 ? time.substring(5, 16).replace('_', ' ') : time,
                            seed.length() > 10 ? seed.substring(0, 10) + "…" : seed,
                            entry.has("dimension") ? entry.get("dimension").getAsString() : "?",
                            entry.has("centerX") ? entry.get("centerX").getAsInt() : 0,
                            entry.has("centerZ") ? entry.get("centerZ").getAsInt() : 0,
                            entry.has("file") ? entry.get("file").getAsString() : ""));
                } catch (Exception ignored) {
                    // A malformed line must not kill the whole history view.
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read terrain export history", e);
        }
    }

    /** Prefilled center when opened from the analysis screen, else the live preview center. */
    private BlockPos exportCenter() {
        return hasPrefill
                ? new BlockPos(prefillCenterX, previewContainer.previewDisplay().center().getY(), prefillCenterZ)
                : previewContainer.previewDisplay().center();
    }

    private AbstractSliderButton createGridSlider(int x, int y, int w) {
        int min = TerrainExportSpec.MIN_GRID_INTERVAL_BLOCKS;
        int max = TerrainExportSpec.MAX_GRID_INTERVAL_BLOCKS;
        int steps = Integer.numberOfTrailingZeros(max / min);
        double initial = (double) Integer.numberOfTrailingZeros(gridInterval / min) / steps;
        return new AbstractSliderButton(x, y, w, SH,
                Component.translatable("world_preview.terrain_export.grid_value", gridInterval), initial) {
            @Override protected void updateMessage() {
                setMessage(Component.translatable("world_preview.terrain_export.grid_value", snapGrid(this.value)));
            }
            @Override protected void applyValue() {
                gridInterval = snapGrid(this.value);
            }
        };
    }

    /** Snaps the grid slider to power-of-two intervals between 16 and 4096 blocks. */
    private int snapGrid(double sliderValue) {
        int steps = Integer.numberOfTrailingZeros(TerrainExportSpec.MAX_GRID_INTERVAL_BLOCKS
                / TerrainExportSpec.MIN_GRID_INTERVAL_BLOCKS);
        int exp = (int) Math.round(sliderValue * steps);
        return TerrainExportSpec.MIN_GRID_INTERVAL_BLOCKS << exp;
    }

    private AbstractSliderButton createContourSlider(int x, int y, int w) {
        int min = 5, max = 64, range = max - min;
        double initial = range == 0 ? 0.0 : (double) (contourInterval - min) / range;
        return new AbstractSliderButton(x, y, w, SH,
                Component.translatable("world_preview.terrain_export.contour_interval", contourInterval), initial) {
            @Override protected void updateMessage() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                setMessage(Component.translatable("world_preview.terrain_export.contour_interval", v));
            }
            @Override protected void applyValue() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                contourInterval = Math.max(min, Math.min(max, v));
            }
        };
    }

    private void doExport() {
        // Belt-and-braces against stale button state: raise blocks/pixel until
        // the shared pixel cap holds so the spec constructor can never throw
        // inside the click handler.
        while (!TerrainExportSpec.isSupported(coverageRadius, blocksPerPixel)
                && blocksPerPixel < TerrainExportSpec.MAX_BLOCKS_PER_PIXEL) {
            blocksPerPixel++;
        }
        var center = exportCenter();
        var spec = new TerrainExportSpec(coverageRadius, blocksPerPixel, center.getX(), center.getZ(),
                exportContours, contourInterval, exportGrid ? gridInterval : 0, exportHeightmap,
                biomeColors ? TerrainExportColorMode.BIOMES : TerrainExportColorMode.CATEGORY,
                center.getY());
        if (batchMode && batchAvailable()) {
            startBatchExport(spec);
        } else {
            // Single-dimension path stays owned by the PreviewContainer controller.
            batchStatus = null;
            if (!previewContainer.startTerrainExport(spec)) {
                showStartRejected();
            }
        }
    }

    /**
     * Surfaces a refused single-dimension start. Zeroing the batch clock keeps
     * the timing row honest (elapsed 0) instead of showing JVM uptime or the
     * previous batch's duration.
     */
    private void showStartRejected() {
        if (batchRunning
                || previewContainer.terrainExportStatus().state() == TerrainExportController.State.RUNNING) {
            return;
        }
        batchPct = 0.0;
        batchStartNanos = System.nanoTime();
        batchFinishNanos = batchStartNanos;
        batchStatus = WorldPreviewComponents.TERRAIN_EXPORT_REJECTED;
    }

    private void cancelExport() {
        if (batchRunning) {
            batchCancelled.set(true);
        }
        previewContainer.cancelTerrainExport();
    }

    private boolean batchAvailable() {
        return previewContainer.worldPreview().workManager().worldgenContext() != null;
    }

    /**
     * Exports every available dimension sequentially with the same spec, one
     * TerrainMapExporter call per dimension. The current-dimension controller
     * flow is untouched; this branch reports through its own status fields.
     */
    private void startBatchExport(TerrainExportSpec spec) {
        if (batchRunning) {
            return;
        }
        WorldgenContext context = previewContainer.worldPreview().workManager().worldgenContext();
        List<Identifier> dimensions = previewContainer.levelStemKeys();
        if (context == null || dimensions == null || dimensions.isEmpty()
                || previewContainer.levelStemRegistry() == null) {
            // Cannot resolve worldgen state: fall back to the current dimension.
            batchStatus = null;
            if (!previewContainer.startTerrainExport(spec)) {
                showStartRejected();
            }
            return;
        }

        long totalWork = Math.max(1L, spec.totalWork() * dimensions.size());
        AtomicLong completedPixels = new AtomicLong();
        Path outputDir = WorldPreview.get().configDir().resolve("terrain_exports");
        TerrainMapExporter exporter = new TerrainMapExporter(
                previewContainer.worldPreview().workManager().threadCount());

        batchCancelled.set(false);
        batchRunning = true;
        batchPct = 0.0;
        batchStartNanos = System.nanoTime();
        batchFinishNanos = 0L;
        batchOutputDir = outputDir;
        batchFailed = false;
        batchStatus = Component.translatable("world_preview.terrain_export.batch.progress",
                1, dimensions.size(), "", String.format("%.1f", 0.0));

        CompletableFuture.runAsync(() -> runBatchExport(spec, exporter, outputDir, context,
                previewContainer.levelStemRegistry(), dimensions, totalWork, completedPixels), batchExecutor);
    }

    private void runBatchExport(
            TerrainExportSpec spec,
            TerrainMapExporter exporter,
            Path outputDir,
            WorldgenContext context,
            Registry<LevelStem> registry,
            List<Identifier> dimensions,
            long totalWork,
            AtomicLong completedPixels
    ) {
        int exported = 0;
        int failed = 0;
        try {
            long seed = context.seed();
            RegistryAccess.Frozen registryAccess = context.registryAccess().compositeAccess();
            // Biome colors are registry-level data: one table serves every
            // dimension in the batch. Missing table falls back to categories.
            TerrainMapExporter.TerrainColorSource batchColors;
            if (spec.colorMode() == TerrainExportColorMode.BIOMES) {
                TerrainMapExporter.BiomeColorTable colorTable = previewContainer.buildTerrainExportBiomeColors();
                batchColors = colorTable != null
                        ? TerrainMapExporter.biomes(colorTable)
                        : TerrainMapExporter.categories(null);
            } else {
                batchColors = TerrainMapExporter.categories(null);
            }
            // Same layer for every dimension: the one the preview shows.
            final int exportQuartY = QuartPos.fromBlock(spec.exportY());
            for (int i = 0; i < dimensions.size(); i++) {
                if (batchCancelled.get()) {
                    throw new CancellationException("Batch terrain export cancelled");
                }
                Identifier dimensionId = dimensions.get(i);
                LevelStem stem = registry.getValue(dimensionId);
                if (stem == null) {
                    continue;
                }
                try {
                    TerrainMapExporter.BiomeSampler sampler = createDimensionSampler(stem, seed, registryAccess, exportQuartY);
                    LongConsumer progress = trackDimensionProgress(
                            dimensionId, i + 1, dimensions.size(), totalWork, completedPixels);
                    progress.accept(0L);
                    int yMin = stem.type().value().minY();
                    exporter.export(spec, sampler, batchColors, null,
                            new TerrainMapExporter.ExportContext(Long.toString(seed), dimensionId.toString()),
                            null, yMin, yMin + stem.type().value().height(), outputDir,
                            sanitizeFileToken(dimensionId.getPath()) + "_", batchCancelled::get, progress);
                    exported++;
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    // One broken dimension must not sink the rest of the batch.
                    failed++;
                    LOGGER.error("Batch terrain export failed for dimension " + dimensionId, e);
                }
            }
            batchPct = 100.0;
            if (failed == 0) {
                batchStatus = WorldPreviewComponents.TERRAIN_EXPORT_COMPLETE;
            } else {
                // Partial success still paints the bar red: the user must see
                // that some dimensions did not produce a file.
                batchFailed = true;
                batchStatus = Component.translatable("world_preview.terrain_export.batch.partial",
                        exported, dimensions.size(), failed);
            }
        } catch (CancellationException e) {
            batchStatus = WorldPreviewComponents.TERRAIN_EXPORT_CANCELLED;
        } catch (Throwable e) {
            // Catch Throwable like the single-dimension controller: an Error
            // (e.g. OOM on a huge allocation) escaping this thread would be
            // swallowed by the unobserved future with no feedback at all.
            LOGGER.error("Batch terrain export failed", e);
            batchFailed = true;
            batchStatus = Component.translatable("world_preview.terrain_export.failed",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            batchFinishNanos = System.nanoTime();
            batchRunning = false;
        }
    }

    /**
     * Creates the per-dimension biome sampler. RandomState creation costs a few
     * milliseconds, so it must happen once per dimension here, never per pixel.
     * Every dimension samples at the same quart Y — the preview center's layer
     * captured by the spec — so the batch matches the single-dimension export.
     */
    private static TerrainMapExporter.BiomeSampler createDimensionSampler(LevelStem stem, long seed,
                                                                          RegistryAccess.Frozen registryAccess,
                                                                          int exportQuartY) {
        ChunkGenerator generator = stem.generator();
        RandomState randomState = generator instanceof NoiseBasedChunkGenerator noiseBased
                ? RandomState.create(noiseBased.generatorSettings().value(),
                        registryAccess.lookupOrThrow(Registries.NOISE), seed)
                : RandomState.create(NoiseGeneratorSettings.dummy(),
                        registryAccess.lookupOrThrow(Registries.NOISE), seed);
        BiomeSource biomeSource = generator.getBiomeSource();
        var noiseSampler = randomState.sampler();
        return (blockX, blockZ) -> biomeSource.getNoiseBiome(
                QuartPos.fromBlock(blockX), exportQuartY, QuartPos.fromBlock(blockZ), noiseSampler);
    }

    private LongConsumer trackDimensionProgress(
            Identifier dimensionId,
            int index,
            int totalDimensions,
            long totalWork,
            AtomicLong completedPixels
    ) {
        return pixels -> {
            long completed = Math.min(totalWork, completedPixels.addAndGet(pixels));
            batchPct = 100.0 * completed / totalWork;
            batchStatus = Component.translatable("world_preview.terrain_export.batch.progress",
                    index, totalDimensions, dimensionId.getPath(), String.format("%.1f", batchPct));
        };
    }

    /** Replaces ':' and other path-illegal characters so the token is safe as a filename prefix. */
    private static String sanitizeFileToken(String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private void updateButtonStates() {
        if (exportButton == null || cancelButton == null) return;
        TerrainExportController.Status status = previewContainer.terrainExportStatus();
        boolean running = status.state() == TerrainExportController.State.RUNNING || batchRunning;
        boolean supported = settingsSupported();
        exportButton.active = !running && supported;
        cancelButton.active = running;
        Path exportDir = WorldPreview.get().configDir().resolve("terrain_exports");
        exportButton.setTooltip(Tooltip.create(supported
                ? Component.translatable("world_preview.terrain_export.output", exportDir.toAbsolutePath().normalize())
                : WorldPreviewComponents.TERRAIN_EXPORT_UNSUPPORTED));
        if (radiusSlider != null) radiusSlider.active = !running;
        if (resolutionSlider != null) resolutionSlider.active = !running;
        if (contourCheckbox != null) contourCheckbox.active = !running;
        if (contourSlider != null) contourSlider.active = !running && exportContours;
        if (batchCycle != null) batchCycle.active = !running && batchAvailable();
        if (gridCheckbox != null) gridCheckbox.active = !running;
        if (gridSlider != null) gridSlider.active = !running && exportGrid;
        if (heightmapCheckbox != null) heightmapCheckbox.active = !running;
        if (colorCycle != null) colorCycle.active = !running;
    }

    @Override
    public void tick() {
        super.tick();
        updateButtonStates();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.centeredText(font, WorldPreviewComponents.TERRAIN_EXPORT_TITLE, width / 2, 8, 0xFFFFFFFF);

        PanelRenderer.panelBackground(graphics, MX, TOP, settingsW, panelsBottom - TOP);
        PanelRenderer.panelHeader(graphics, font, MX, TOP, settingsW,
                Component.translatable("world_preview.terrain_export.settings"), null);

        PanelRenderer.panelBackground(graphics, statusX, TOP, statusW, statusH);
        PanelRenderer.panelBackground(graphics, statusX, legendY, statusW, legendH);
        PanelRenderer.panelHeader(graphics, font, statusX, legendY, statusW,
                WorldPreviewComponents.TERRAIN_EXPORT_LEGEND, null);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (historyOpen) {
            renderHistoryList(graphics, mouseX, mouseY);
        } else {
            renderStatusBox(graphics);
        }
        renderLegendColors(graphics);
    }

    private void renderStatusBox(GuiGraphicsExtractor graphics) {
        TerrainExportController.Status status = previewContainer.terrainExportStatus();
        boolean batchActive = batchStatus != null;
        double pct = batchActive ? batchPct : status.percentage();

        int x = statusX + PanelRenderer.PANEL_PAD;
        int w = statusW - 2 * PanelRenderer.PANEL_PAD;
        int y = TOP + PanelRenderer.PANEL_PAD;

        boolean failed = batchActive ? batchFailed
                : status.state() == TerrainExportController.State.FAILED;
        boolean inProgress = batchActive ? batchRunning
                : status.state() == TerrainExportController.State.RUNNING;
        PanelRenderer.progressBar(graphics, x, y, w, pct,
                failed ? BAR_FAILED : inProgress ? BAR_RUNNING : BAR_DONE);
        String pctText = String.format("%.1f%%", pct);
        if (w >= 40 && font.width(pctText) <= w - 4) {
            graphics.text(font, Component.literal(pctText),
                    x + w - 2 - font.width(pctText), y + 2, 0xFFFFFFFF);
        }
        y += 12 + 6;

        // Image size (red when the shared pixel cap rejects the combination)
        // and the export center: export *inputs*, so they live at the top of
        // the status box instead of crowding the settings panel.
        int imgW = computeImageSize();
        graphics.text(font, Component.translatable("world_preview.terrain_export.image_size", imgW, imgW),
                x, y, settingsSupported() ? 0xFFFFFFFF : 0xFFFF5555);
        var center = exportCenter();
        graphics.text(font, Component.translatable("world_preview.terrain_export.center",
                center.getX(), center.getZ()), x, CENTER_Y, 0xFF999999);
        y = STATUS_LINE_Y;

        Component statusLine = switch (status.state()) {
            case IDLE -> WorldPreviewComponents.TERRAIN_EXPORT_IDLE;
            case RUNNING -> Component.translatable("world_preview.terrain_export.exporting",
                    String.format("%.1f", status.percentage()));
            case COMPLETED -> WorldPreviewComponents.TERRAIN_EXPORT_COMPLETE;
            case CANCELLED -> WorldPreviewComponents.TERRAIN_EXPORT_CANCELLED;
            case FAILED -> Component.translatable("world_preview.terrain_export.failed",
                    status.errorMessage() != null ? status.errorMessage() : "");
        };
        // Batch exports bypass the controller and report their own status.
        if (batchStatus != null) {
            statusLine = batchStatus;
        }
        graphics.text(font, statusLine, x, y, 0xFFFFFFFF);
        y += LH + 4;

        long elapsedNanos;
        long etaNanos;
        boolean showTiming;
        if (batchStatus != null) {
            long now = batchFinishNanos != 0L ? batchFinishNanos : System.nanoTime();
            elapsedNanos = Math.max(0L, now - batchStartNanos);
            etaNanos = batchRunning && batchPct > 0.5
                    ? (long) (elapsedNanos * (100.0 - batchPct) / batchPct) : -1L;
            showTiming = true;
        } else {
            showTiming = status.state() != TerrainExportController.State.IDLE;
            elapsedNanos = status.elapsedNanos();
            etaNanos = status.estimatedRemainingNanos();
        }
        if (showTiming) {
            String elapsed = formatDuration(elapsedNanos);
            String eta = etaNanos < 0L ? "--:--" : formatDuration(etaNanos);
            graphics.text(font,
                    Component.translatable("world_preview.terrain_export.timing", elapsed, eta),
                    x, y, 0xFF999999);
            y += LH + 4;
        }

        // While a batch runs, the controller's single-export path would be stale;
        // the shared output directory is the accurate location to show.
        Path outputPath = batchStatus != null ? batchOutputDir : status.outputPath();
        if (outputPath != null) {
            String path = outputPath.toAbsolutePath().normalize().toString();
            int maxLines = Math.max(1, (TOP + statusH - PanelRenderer.PANEL_PAD - y) / LH);
            int lineCount = 0;
            StringBuilder line = new StringBuilder();
            int lineW = 0;
            for (int i = 0; i < path.length(); i++) {
                char ch = path.charAt(i);
                int charW = font.width(String.valueOf(ch));
                if (x + lineW + charW > x + w && line.length() > 0) {
                    graphics.text(font, Component.literal(line.toString()), x, y, 0xFF999999);
                    y += LH;
                    if (++lineCount >= maxLines) {
                        line.setLength(0);
                        break;
                    }
                    line.setLength(0);
                    lineW = 0;
                }
                line.append(ch);
                lineW += charW;
            }
            if (line.length() > 0 && lineCount < maxLines) {
                graphics.text(font, Component.literal(line.toString()), x, y, 0xFF999999);
            }
        }
    }

    /** Draws the export-history list inside the status panel; clicking a row copies its file path. */
    private void renderHistoryList(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        int x = statusX + PanelRenderer.PANEL_PAD;
        int w = statusW - 2 * PanelRenderer.PANEL_PAD;
        int y = TOP + PanelRenderer.PANEL_PAD;
        graphics.text(font, WorldPreviewComponents.TERRAIN_EXPORT_HISTORY, x, y, 0xFFFFFFFF);
        if (System.nanoTime() - historyCopiedAtNanos < 2_000_000_000L) {
            String copied = WorldPreviewComponents.TERRAIN_EXPORT_HISTORY_COPIED.getString();
            graphics.text(font, copied, x + w - font.width(copied), y, 0xFF55FF55);
        }
        historyRects.clear();
        if (historyRows.isEmpty()) {
            graphics.text(font, WorldPreviewComponents.TERRAIN_EXPORT_HISTORY_EMPTY, x,
                    historyListTop(), 0xFF999999);
            return;
        }
        tickHistoryScroll(mouseY);
        historyScrollOffset = historyClamp(historyScrollOffset);
        int rowHeight = historyRowHeight();
        graphics.enableScissor(statusX + 1, TOP + 1, statusX + statusW - 1, TOP + statusH - 1);
        for (int i = Math.max(0, (int) Math.floor(historyRenderOffset / rowHeight));
                i < historyRows.size(); i++) {
            int rowY = historyListTop() + (int) Math.round(i * rowHeight - historyRenderOffset);
            if (rowY >= historyListBottom()) {
                break;
            }
            HistoryRow row = historyRows.get(i);
            String dimShort = row.dimension().contains(":")
                    ? row.dimension().substring(row.dimension().indexOf(':') + 1) : row.dimension();
            Component line = Component.literal(String.format("%s  %s  %s  %d,%d",
                    row.time(), dimShort, row.seed(), row.centerX(), row.centerZ()));
            graphics.text(font, line, x, rowY, 0xFFE0E0E0);
            historyRects.add(new int[]{x, rowY, Math.min(w, font.width(line)), font.lineHeight});
        }
        graphics.disableScissor();
        drawHistoryScrollbar(graphics, mouseX, mouseY);
    }

    // ===== History list scrolling helpers =====

    /** Top Y of the history rows area (below the header row). */
    private int historyListTop() {
        return TOP + PanelRenderer.PANEL_PAD + 12 + 4;
    }

    private int historyListBottom() {
        return TOP + statusH - PanelRenderer.PANEL_PAD;
    }

    private int historyRowHeight() {
        return font.lineHeight + 2;
    }

    private int historyScrollMax() {
        return Math.max(0, historyRows.size() * historyRowHeight()
                - Math.max(0, historyListBottom() - historyListTop()));
    }

    private int historyClamp(int value) {
        return Math.min(historyScrollMax(), Math.max(0, value));
    }

    private boolean inHistoryScrollbar(double mouseX, double mouseY) {
        return historyScrollMax() > 0
                && mouseX >= statusX + statusW - HISTORY_HIT_WIDTH && mouseX <= statusX + statusW
                && mouseY >= historyListTop() && mouseY < historyListBottom();
    }

    /**
     * Per-render-frame scroll animation, ticked from the render pass so it
     * keeps running while the mouse is still: eased wheel steps and a
     * middle-press autoscroll whose speed follows the cursor's distance from
     * the anchor (browser autoscroll feel, same direction).
     */
    private void tickHistoryScroll(double mouseY) {
        long now = System.currentTimeMillis();
        long dt = historyLastFrameMs < 0 ? 16 : Math.min(100, now - historyLastFrameMs);
        historyLastFrameMs = now;
        int max = historyScrollMax();
        if (historyPanActive) {
            double displacement = mouseY - historyPanAnchorY;
            double speed = 0.0;
            if (Math.abs(displacement) > HISTORY_PAN_DEADZONE_PX) {
                speed = Math.min(
                        HISTORY_PAN_MAX_PX_PER_SEC,
                        (displacement - Math.signum(displacement) * HISTORY_PAN_DEADZONE_PX)
                                * HISTORY_PAN_PX_PER_SEC_PER_PX);
            }
            historyRenderOffset = Math.min(max, Math.max(0, historyRenderOffset + speed * dt / 1000.0));
            historyScrollOffset = (int) Math.round(historyRenderOffset);
        } else if (historyScrollbarDragging) {
            historyRenderOffset = historyScrollOffset;
        } else {
            historyRenderOffset += (historyScrollOffset - historyRenderOffset)
                    * (1.0 - Math.exp(-dt / HISTORY_SCROLL_EASE_MS));
            if (Math.abs(historyScrollOffset - historyRenderOffset) < 0.01) {
                historyRenderOffset = historyScrollOffset;
            }
            historyRenderOffset = Math.min(max, Math.max(0, historyRenderOffset));
        }
    }

    private void scrollFromHistoryScrollbar(double mouseY) {
        int max = historyScrollMax();
        if (max <= 0) {
            return;
        }
        int trackTop = historyListTop();
        int trackLen = historyListBottom() - trackTop;
        int thumbHeight = Math.max(10, trackLen * trackLen / (historyRows.size() * historyRowHeight()));
        int travel = Math.max(1, trackLen - thumbHeight);
        double raw = Math.min(travel, Math.max(0, mouseY - historyScrollbarGrabOffset - trackTop));
        historyScrollOffset = (int) Math.round(raw * max / travel);
        historyRenderOffset = historyScrollOffset;
    }

    private void drawHistoryScrollbar(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        int max = historyScrollMax();
        if (max <= 0) {
            return;
        }
        int trackTop = historyListTop();
        int trackBottom = historyListBottom();
        int trackLen = trackBottom - trackTop;
        int thumbHeight = Math.max(10, trackLen * trackLen / (historyRows.size() * historyRowHeight()));
        int thumbY = trackTop + (int) Math.round((trackLen - thumbHeight) * historyRenderOffset / Math.max(1, max));
        int trackX = statusX + statusW - 1 - SCROLLBAR_BAR_WIDTH;
        graphics.fill(trackX, trackTop, trackX + SCROLLBAR_BAR_WIDTH, trackBottom, 0x33FFFFFF);
        graphics.fill(trackX, thumbY, trackX + SCROLLBAR_BAR_WIDTH, thumbY + thumbHeight,
                historyScrollbarDragging ? 0xB4FFFFFF : 0x8CFFFFFF);
        if (inHistoryScrollbar(mouseX, mouseY)) {
            graphics.requestCursor(historyScrollbarDragging
                    ? CursorTypes.RESIZE_NS : CursorTypes.POINTING_HAND);
        }
    }

    private void renderLegendColors(GuiGraphicsExtractor graphics) {
        int innerX = statusX + PanelRenderer.PANEL_PAD;
        int maxRight = statusX + statusW - PanelRenderer.PANEL_PAD;
        int itemY = legendY + PanelRenderer.PANEL_PAD + LH + 4;
        int colorY = itemY - 1;
        int colorSize = 10;
        int colorTextGap = 3;
        int itemGap = 8;
        int curX = innerX;

        graphics.enableScissor(statusX + 1, legendY + 1, statusX + statusW - 1, legendY + legendH - 1);
        if (biomeColors) {
            // Biome colors are data-driven; the fixed on-screen legend only
            // documents the category mode. The export's JSON sidecar carries
            // the actual per-biome color table.
            graphics.text(font, WorldPreviewComponents.TERRAIN_EXPORT_BIOME_LEGEND, innerX, itemY, 0xFFE0E0E0);
            graphics.disableScissor();
            return;
        }
        for (TerrainCategory cat : TerrainCategory.values()) {
            Component catLabel = Component.translatable(
                    "world_preview.terrain_export.category." + cat.name().toLowerCase());
            int textW = font.width(catLabel);
            int itemW = colorSize + colorTextGap + textW;

            if (curX + itemW > maxRight) {
                curX = innerX;
                itemY += 14;
                colorY = itemY - 1;
            }

            int rgb = cat.argbColor();
            graphics.fill(curX, colorY, curX + colorSize, colorY + colorSize, rgb);
            graphics.fill(curX, colorY, curX + colorSize, colorY + 1, 0xFF000000);
            graphics.fill(curX, colorY + colorSize - 1, curX + colorSize, colorY + colorSize, 0xFF000000);
            graphics.fill(curX, colorY, curX + 1, colorY + colorSize, 0xFF000000);
            graphics.fill(curX + colorSize - 1, colorY, curX + colorSize, colorY + colorSize, 0xFF000000);

            graphics.text(font, catLabel, curX + colorSize + colorTextGap, itemY, 0xFFFFFFFF);

            curX += itemW + itemGap;
        }
        graphics.disableScissor();
    }

    private static String formatDuration(long nanos) {
        long seconds = Math.max(0L, Duration.ofNanos(nanos).toSeconds());
        long hours = seconds / 3600L;
        long minutes = seconds % 3600L / 60L;
        long remainingSeconds = seconds % 60L;
        return hours > 0L
                ? String.format("%d:%02d:%02d", hours, minutes, remainingSeconds)
                : String.format("%02d:%02d", minutes, remainingSeconds);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (historyOpen) {
            // A middle press in the list area starts a browser-style autoscroll
            // (ticked from the render pass); the release is routed below.
            if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE
                    && historyScrollMax() > 0
                    && event.x() >= statusX && event.x() < statusX + statusW
                    && event.y() >= historyListTop() && event.y() < historyListBottom()) {
                historyPanActive = true;
                historyPanAnchorY = event.y();
                return true;
            }
            // The scrollbar strip wins over row clicks: jumping the thumb is
            // the point of pressing there.
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                    && inHistoryScrollbar(event.x(), event.y())) {
                historyScrollbarDragging = true;
                int trackLen = historyListBottom() - historyListTop();
                int thumbHeight = Math.max(10, trackLen * trackLen
                        / (historyRows.size() * historyRowHeight()));
                historyScrollbarGrabOffset = thumbHeight / 2.0;
                scrollFromHistoryScrollbar(event.y());
                return true;
            }
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                for (int i = 0; i < historyRects.size() && i < historyRows.size(); i++) {
                    int[] rect = historyRects.get(i);
                    double mouseX = event.x(), mouseY = event.y();
                    if (mouseX >= rect[0] && mouseX <= rect[0] + rect[2] && mouseY >= rect[1] && mouseY <= rect[1] + rect[3]) {
                        String path = WorldPreview.get().configDir().resolve("terrain_exports")
                                .resolve(historyRows.get(i).file()).toAbsolutePath().normalize().toString();
                        minecraft.keyboardHandler.setClipboard(path);
                        historyCopiedAtNanos = System.nanoTime();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (historyPanActive) {
            historyPanActive = false;
            if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
                return true;
            }
        }
        if (historyScrollbarDragging && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            historyScrollbarDragging = false;
            historyScrollbarGrabOffset = 0;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        if (historyScrollbarDragging && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            scrollFromHistoryScrollbar(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (super.mouseScrolled(mouseX, mouseY, deltaX, deltaY)) {
            return true;
        }
        if (!historyOpen || historyRows.isEmpty() || historyScrollMax() <= 0) {
            return false;
        }
        if (mouseX < statusX || mouseX >= statusX + statusW
                || mouseY < historyListTop() || mouseY >= historyListBottom()) {
            return false;
        }
        int steps = (int) deltaY;
        if (steps == 0 && deltaY != 0.0) {
            steps = deltaY > 0 ? 1 : -1;
        }
        if (historyPanActive) {
            // The wheel takes over, like a browser: cancel the autoscroll
            // and apply the step to the position it leaves behind.
            historyPanActive = false;
        }
        historyScrollOffset = historyClamp(
                historyScrollOffset - steps * HISTORY_WHEEL_ROWS * historyRowHeight());
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (super.keyPressed(event)) return true;
        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        // Interrupt any running batch export before leaving the screen.
        batchCancelled.set(true);
        batchExecutor.shutdownNow();
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
