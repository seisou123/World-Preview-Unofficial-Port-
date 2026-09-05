package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.backend.analysis.WorldgenContext;
import caeruleusTait.world.preview.backend.export.TerrainCategory;
import caeruleusTait.world.preview.backend.export.TerrainExportController;
import caeruleusTait.world.preview.backend.export.TerrainExportSpec;
import caeruleusTait.world.preview.backend.export.TerrainMapExporter;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.screens.settings.SettingsTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
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

import java.nio.file.Path;
import java.time.Duration;
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
 * Terrain map export screen (sub-screen switch mode).
 * Adapted for MC 26.2 GuiGraphics API.
 */
public final class TerrainExportScreen extends Screen {

    private enum Tab { EXPORT, SETTINGS }

    private final Screen parent;
    private final PreviewContainer previewContainer;

    private int coverageRadius = TerrainExportSpec.DEFAULT_COVERAGE_RADIUS;
    private int blocksPerPixel = TerrainExportSpec.DEFAULT_BLOCKS_PER_PIXEL;
    private boolean exportContours = true;
    private int contourInterval = 10;
    private boolean batchMode = false;

    private Tab currentTab = Tab.EXPORT;

    private AbstractSliderButton radiusSlider;
    private AbstractSliderButton resolutionSlider;
    private AbstractSliderButton contourSlider;
    private Checkbox contourCheckbox;
    private CycleButton<Boolean> batchCycle;
    private Button exportButton;
    private Button cancelButton;
    private Button tabExportBtn;
    private Button tabSettingsBtn;
    private StringWidget imageSizeLabel;

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

    private static final int MX = 8;
    private static final int TY = 28;
    private static final int SH = 20;
    private static final int LH = 12;
    private static final int GAP = 4;

    public TerrainExportScreen(Screen parent, PreviewContainer previewContainer) {
        super(WorldPreviewComponents.TERRAIN_EXPORT_TITLE);
        this.parent = parent;
        this.previewContainer = previewContainer;
    }

    @Override
    protected void init() {
        clearWidgets();

        int tabW = (width - MX * 2 - GAP) / 2;
        tabExportBtn = Button.builder(
                Component.translatable("world_preview.terrain_export.tab_export"),
                b -> switchTab(Tab.EXPORT))
                .bounds(MX, TY, tabW, SH).build();
        tabSettingsBtn = Button.builder(
                Component.translatable("world_preview.terrain_export.tab_settings"),
                b -> switchTab(Tab.SETTINGS))
                .bounds(MX + tabW + GAP, TY, tabW, SH).build();
        addRenderableWidget(tabExportBtn);
        addRenderableWidget(tabSettingsBtn);

        int contentY = TY + SH + GAP * 2;
        int contentW = Math.min(340, width - MX * 2);

        if (currentTab == Tab.EXPORT) {
            initExportTab(contentY, contentW);
        } else {
            initSettingsTab(contentY, contentW);
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(width - 90, height - 28, 80, SH).build());

        updateTabStyles();
        updateButtonStates();
    }

    private void switchTab(Tab tab) {
        currentTab = tab;
        init();
    }

    private void updateTabStyles() {
        tabExportBtn.active = currentTab != Tab.EXPORT;
        tabSettingsBtn.active = currentTab != Tab.SETTINGS;
    }

    private void initExportTab(int y, int w) {
        int x = MX;
        exportButton = Button.builder(WorldPreviewComponents.TERRAIN_EXPORT_START,
                b -> doExport()).bounds(x, y, w / 2 - 2, SH).build();
        cancelButton = Button.builder(WorldPreviewComponents.TERRAIN_EXPORT_CANCEL,
                b -> cancelExport()).bounds(x + w / 2 + 2, y, w / 2 - 2, SH).build();
        addRenderableWidget(exportButton);
        addRenderableWidget(cancelButton);
    }

    private void initSettingsTab(int y, int w) {
        int x = MX;

        addRenderableWidget(new StringWidget(x, y, w, LH,
                WorldPreviewComponents.TERRAIN_EXPORT_RADIUS, Minecraft.getInstance().font));
        y += LH + 2;
        radiusSlider = createRadiusSlider(x, y, w);
        addRenderableWidget(radiusSlider);
        y += SH + GAP + 4;

        addRenderableWidget(new StringWidget(x, y, w, LH,
                WorldPreviewComponents.TERRAIN_EXPORT_RESOLUTION, Minecraft.getInstance().font));
        y += LH + 2;
        resolutionSlider = createResolutionSlider(x, y, w);
        addRenderableWidget(resolutionSlider);
        y += SH + GAP + 4;

        int imgW = computeImageSize();
        imageSizeLabel = new StringWidget(x, y, w, LH,
                Component.translatable("world_preview.terrain_export.image_size", imgW, imgW),
                Minecraft.getInstance().font);
        addRenderableWidget(imageSizeLabel);
        y += LH + GAP + 4;

        var center = previewContainer.previewDisplay().center();
        addRenderableWidget(new StringWidget(x, y, w, LH,
                Component.translatable("world_preview.terrain_export.center",
                        center.getX(), center.getZ()), Minecraft.getInstance().font));
        y += LH + GAP + 8;

        contourCheckbox = Checkbox.builder(
                Component.translatable("world_preview.terrain_export.contours"),
                Minecraft.getInstance().font)
                .selected(exportContours)
                .onValueChange((box, val) -> {
                    exportContours = val;
                    if (contourSlider != null) contourSlider.active = val;
                })
                .build();
        contourCheckbox.setPosition(x, y);
        addRenderableWidget(contourCheckbox);
        y += SH + GAP;

        contourSlider = createContourSlider(x, y, w);
        contourSlider.active = exportContours;
        addRenderableWidget(contourSlider);
        y += SH + GAP;

        batchCycle = CycleButton.booleanBuilder(CommonComponents.OPTION_ON, CommonComponents.OPTION_OFF, batchMode)
                .create(x, y, w, SH, WorldPreviewComponents.TERRAIN_EXPORT_BATCH, (btn, val) -> batchMode = val);
        batchCycle.setTooltip(Tooltip.create(WorldPreviewComponents.TERRAIN_EXPORT_BATCH_TOOLTIP));
        addRenderableWidget(batchCycle);
    }

    private int computeImageSize() {
        return (coverageRadius * 2) / blocksPerPixel;
    }

    private void refreshImageSizeLabel() {
        if (imageSizeLabel != null) {
            int imgW = computeImageSize();
            imageSizeLabel.setMessage(Component.translatable("world_preview.terrain_export.image_size", imgW, imgW));
        }
    }

    private AbstractSliderButton createRadiusSlider(int x, int y, int w) {
        int min = TerrainExportSpec.MIN_COVERAGE_RADIUS;
        int max = TerrainExportSpec.MAX_COVERAGE_RADIUS;
        int range = max - min;
        double initial = range == 0 ? 0.0 : (double) (coverageRadius - min) / range;
        return new AbstractSliderButton(x, y, w, SH,
                Component.translatable("world_preview.terrain_export.radius_value", coverageRadius), initial) {
            @Override protected void updateMessage() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                setMessage(Component.translatable("world_preview.terrain_export.radius_value", v));
            }
            @Override protected void applyValue() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                coverageRadius = Math.max(min, Math.min(max, v));
                refreshImageSizeLabel();
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
                refreshImageSizeLabel();
            }
        };
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
        var center = previewContainer.previewDisplay().center();
        var spec = new TerrainExportSpec(coverageRadius, blocksPerPixel, center.getX(), center.getZ(),
                exportContours, contourInterval);
        if (batchMode && batchAvailable()) {
            startBatchExport(spec);
        } else {
            // Single-dimension path stays owned by the PreviewContainer controller.
            batchStatus = null;
            previewContainer.startTerrainExport(spec);
        }
        if (currentTab != Tab.EXPORT) switchTab(Tab.EXPORT);
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
            previewContainer.startTerrainExport(spec);
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
        try {
            long seed = context.seed();
            RegistryAccess.Frozen registryAccess = context.registryAccess().compositeAccess();
            for (int i = 0; i < dimensions.size(); i++) {
                if (batchCancelled.get()) {
                    throw new CancellationException("Batch terrain export cancelled");
                }
                Identifier dimensionId = dimensions.get(i);
                LevelStem stem = registry.getValue(dimensionId);
                if (stem == null) {
                    continue;
                }
                TerrainMapExporter.BiomeSampler sampler = createDimensionSampler(stem, seed, registryAccess);
                LongConsumer progress = trackDimensionProgress(
                        dimensionId, i + 1, dimensions.size(), totalWork, completedPixels);
                progress.accept(0L);
                int yMin = stem.type().value().minY();
                exporter.export(spec, sampler, yMin, yMin + stem.type().value().height(), outputDir,
                        sanitizeFileToken(dimensionId.getPath()) + "_", batchCancelled::get, progress);
            }
            batchPct = 100.0;
            batchStatus = WorldPreviewComponents.TERRAIN_EXPORT_COMPLETE;
        } catch (CancellationException e) {
            batchStatus = WorldPreviewComponents.TERRAIN_EXPORT_CANCELLED;
        } catch (Exception e) {
            LOGGER.error("Batch terrain export failed", e);
            batchStatus = Component.translatable("world_preview.terrain_export.failed",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            batchRunning = false;
        }
    }

    /**
     * Creates the per-dimension biome sampler. RandomState creation costs a few
     * milliseconds, so it must happen once per dimension here, never per pixel.
     */
    private static TerrainMapExporter.BiomeSampler createDimensionSampler(LevelStem stem, long seed,
                                                                          RegistryAccess.Frozen registryAccess) {
        ChunkGenerator generator = stem.generator();
        RandomState randomState = generator instanceof NoiseBasedChunkGenerator noiseBased
                ? RandomState.create(noiseBased.generatorSettings().value(),
                        registryAccess.lookupOrThrow(Registries.NOISE), seed)
                : RandomState.create(NoiseGeneratorSettings.dummy(),
                        registryAccess.lookupOrThrow(Registries.NOISE), seed);
        BiomeSource biomeSource = generator.getBiomeSource();
        var noiseSampler = randomState.sampler();
        int quartY = QuartPos.fromBlock(64);
        return (blockX, blockZ) -> biomeSource.getNoiseBiome(
                QuartPos.fromBlock(blockX), quartY, QuartPos.fromBlock(blockZ), noiseSampler);
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
        exportButton.active = !running;
        cancelButton.active = running;
        if (radiusSlider != null) radiusSlider.active = !running;
        if (resolutionSlider != null) resolutionSlider.active = !running;
        if (contourCheckbox != null) contourCheckbox.active = !running;
        if (contourSlider != null) contourSlider.active = !running && exportContours;
        if (batchCycle != null) batchCycle.active = !running && batchAvailable();
    }

    @Override
    public void tick() {
        super.tick();
        updateButtonStates();
        updateTabStyles();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, SettingsTheme.BACKGROUND);
        graphics.drawCenteredString(font, WorldPreviewComponents.TERRAIN_EXPORT_TITLE, width / 2, 8, SettingsTheme.TEXT);
        super.render(graphics, mouseX, mouseY, partialTick);

        if (currentTab == Tab.EXPORT) {
            renderProgressBar(graphics);
            renderStatus(graphics);
            renderLegendColors(graphics);
        }
    }

    private int exportContentStartY() {
        return TY + SH + GAP * 2;
    }

    private void renderProgressBar(GuiGraphics graphics) {
        int x = MX;
        int y = exportContentStartY() + SH + GAP + 4;
        int w = Math.min(340, width - MX * 2);

        TerrainExportController.Status status = previewContainer.terrainExportStatus();
        boolean batchActive = batchStatus != null;
        double pct = batchActive ? batchPct : status.percentage();

        graphics.fill(x, y, x + w, y + 12, 0xFF333344);
        int fillW = (int) (w * pct / 100.0);
        if (fillW > 0) {
            boolean inProgress = batchActive ? batchRunning
                    : status.state() == TerrainExportController.State.RUNNING;
            int color = inProgress ? SettingsTheme.PRIMARY : SettingsTheme.SUCCESS;
            graphics.fill(x, y, x + fillW, y + 12, color);
        }
    }

    private void renderStatus(GuiGraphics graphics) {
        int x = MX;
        int y = exportContentStartY() + SH + GAP + 4 + 16;

        TerrainExportController.Status status = previewContainer.terrainExportStatus();

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
        graphics.drawString(font, statusLine, x, y, SettingsTheme.TEXT);
        y += 14;

        if (status.state() != TerrainExportController.State.IDLE) {
            String elapsed = formatDuration(status.elapsedNanos());
            String eta = status.estimatedRemainingNanos() < 0L
                    ? "--:--" : formatDuration(status.estimatedRemainingNanos());
            graphics.drawString(font,
                    Component.translatable("world_preview.terrain_export.timing", elapsed, eta),
                    x, y, SettingsTheme.TEXT_DIM);
            y += 14;
        }

        if (status.outputPath() != null) {
            String path = status.outputPath().toAbsolutePath().normalize().toString();
            int screenMaxW = (width * 3) / 4;
            int drawX = x;
            int drawY = y;
            StringBuilder line = new StringBuilder();
            int lineW = 0;

            for (int i = 0; i < path.length(); i++) {
                char ch = path.charAt(i);
                int charW = font.width(String.valueOf(ch));
                if (drawX + lineW + charW > x + screenMaxW && line.length() > 0) {
                    graphics.drawString(font, Component.literal(line.toString()), drawX, drawY, SettingsTheme.TEXT_DIM);
                    drawY += 12;
                    line.setLength(0);
                    lineW = 0;
                }
                line.append(ch);
                lineW += charW;
            }
            if (line.length() > 0) {
                graphics.drawString(font, Component.literal(line.toString()), drawX, drawY, SettingsTheme.TEXT_DIM);
            }
        }
    }

    private void renderLegendColors(GuiGraphics graphics) {
        int x = MX;
        int y = exportContentStartY() + SH + GAP + 4 + 16 + 14 * 4 + 8;

        Component legendTitle = WorldPreviewComponents.TERRAIN_EXPORT_LEGEND;
        graphics.drawString(font, legendTitle, x, y, SettingsTheme.TEXT);
        int titleW = font.width(legendTitle);

        int itemStartX = x + titleW + 8;
        int colorSize = 10;
        int colorTextGap = 3;
        int itemGap = 8;
        int itemY = y;
        int colorY = itemY - 1;

        TerrainCategory[] cats = TerrainCategory.values();
        int curX = itemStartX;
        int maxRight = width - MX;

        for (int i = 0; i < cats.length; i++) {
            Component catLabel = Component.translatable(
                    "world_preview.terrain_export.category." + cats[i].name().toLowerCase());
            int textW = font.width(catLabel);
            int itemW = colorSize + colorTextGap + textW;

            if (curX + itemW > maxRight) {
                curX = itemStartX;
                itemY += 14;
                colorY = itemY - 1;
            }

            int rgb = cats[i].pixelColor();
            graphics.fill(curX, colorY, curX + colorSize, colorY + colorSize, rgb);
            graphics.fill(curX, colorY, curX + colorSize, colorY + 1, 0xFF000000);
            graphics.fill(curX, colorY + colorSize - 1, curX + colorSize, colorY + colorSize, 0xFF000000);
            graphics.fill(curX, colorY, curX + 1, colorY + colorSize, 0xFF000000);
            graphics.fill(curX + colorSize - 1, colorY, curX + colorSize, colorY + colorSize, 0xFF000000);

            graphics.drawString(font, catLabel, curX + colorSize + colorTextGap, itemY, SettingsTheme.TEXT);

            curX += itemW + itemGap;
        }
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
