package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.backend.analysis.AnalysisDataState;
import caeruleusTait.world.preview.backend.analysis.AnalysisProgress;
import caeruleusTait.world.preview.backend.analysis.AnalysisSession;
import caeruleusTait.world.preview.backend.analysis.AnalysisStatus;
import caeruleusTait.world.preview.backend.analysis.BiomeRarity;
import caeruleusTait.world.preview.backend.analysis.ProfileRequest;
import caeruleusTait.world.preview.backend.analysis.ProfileResult;
import caeruleusTait.world.preview.backend.analysis.Region;
import caeruleusTait.world.preview.backend.analysis.RegionMetrics;
import caeruleusTait.world.preview.backend.analysis.SpawnAdvisor;
import caeruleusTait.world.preview.backend.analysis.WorldgenContext;
import caeruleusTait.world.preview.backend.export.AnalysisReportExporter;
import caeruleusTait.world.preview.backend.export.AnalysisReportExporter.ReportInput;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.widgets.AnalysisPanel;
import caeruleusTait.world.preview.client.gui.widgets.ProfileChart;
import caeruleusTait.world.preview.client.gui.widgets.RegionSelector;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public final class WorldAnalysisScreen extends Screen {
    private static final long EXPORT_STATUS_MILLIS = 6000L;
    private static final DateTimeFormatter EXPORT_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Gson REPORT_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Vanilla water biome tags used for the spawn score's water share. */
    private static final TagKey<Biome> IS_OCEAN = TagKey.create(Registries.BIOME, Identifier.parse("minecraft:is_ocean"));
    private static final TagKey<Biome> IS_RIVER = TagKey.create(Registries.BIOME, Identifier.parse("minecraft:is_river"));
    private static final TagKey<Biome> IS_DEEP_OCEAN = TagKey.create(Registries.BIOME, Identifier.parse("minecraft:is_deep_ocean"));

    private final Screen parent;
    private final AnalysisSession session;
    private final PreviewContainer previewContainer;
    private final RegionSelector regionSelector;
    private final AnalysisPanel analysisPanel;
    private final ProfileChart profileChart;
    private final List<AbstractWidget> selectorFields;
    private final Button startButton;
    private final Button cancelButton;
    private final Button exportReportButton;
    private Button closeButton;
    private Region region;
    private boolean closed;
    private int profileRefreshCooldown;
    private boolean lastRunning;
    private Component exportStatusMessage;
    private int exportStatusColor;
    private long exportStatusUntil;
    /** Lazily built short-id → biome entry lookup for the spawn score / top biomes. */
    private Map<Short, BiomesList.BiomeEntry> biomeIdLookup;

    public WorldAnalysisScreen(Screen parent, AnalysisSession session,
                               PreviewContainer previewContainer, Region initialRegion) {
        super(WorldPreviewComponents.ANALYSIS_TITLE);
        this.parent = parent;
        this.session = session;
        this.previewContainer = previewContainer;
        this.region = initialRegion;
        this.regionSelector = new RegionSelector(Minecraft.getInstance().font, 0, 0, 300, 45,
                initialRegion, this::setRegion);
        this.analysisPanel = new AnalysisPanel(0, 0, 260, 140);
        this.profileChart = new ProfileChart(0, 0, 360, 170);
        this.selectorFields = new ArrayList<>(regionSelector.fields());
        this.startButton = Button.builder(WorldPreviewComponents.ANALYSIS_START, ignored -> startAnalysis())
                .width(90).build();
        this.cancelButton = Button.builder(WorldPreviewComponents.ANALYSIS_CANCEL, ignored -> cancelAnalysis())
                .width(90).build();
        this.cancelButton.active = false;
        this.exportReportButton = Button.builder(WorldPreviewComponents.ANALYSIS_EXPORT_REPORT,
                        ignored -> exportReport())
                .width(90).build();
        this.exportReportButton.active = false;
    }

    public AnalysisSession session() {
        return session;
    }

    public RegionSelector regionSelector() {
        return regionSelector;
    }

    public AnalysisPanel analysisPanel() {
        return analysisPanel;
    }

    public ProfileChart profileChart() {
        return profileChart;
    }

    private void setRegion(Region region) {
        this.region = region;
        profileChart.setResult(null);
    }

    private void startAnalysis() {
        session.start();
        // A fresh run invalidates the previously computed spawn/top-biome data.
        analysisPanel.setSpawnAnalysis(null, List.of());
        analysisPanel.setTopBiomes(List.of());
        ProfileRequest request = new ProfileRequest(region.minX(), region.minZ(), region.maxX(), region.maxZ(),
                session.request().y(), session.request().y(), Math.max(1, session.request().sampleStep()), false);
        profileChart.setResult(session.profile(request));
        profileRefreshCooldown = 0;
        updateControlState();
    }

    private void cancelAnalysis() {
        session.cancel();
        updateControlState();
    }

    private void updateControlState() {
        boolean running = session.isRunning();
        startButton.active = !running;
        cancelButton.active = running;
        refreshExportButton(running);
        if (closeButton != null) {
            // Closing must always remain possible.
            closeButton.active = true;
        }
        lastRunning = running;
    }

    private void refreshExportButton(boolean running) {
        // Only finished analyses have non-partial metrics worth reporting.
        RegionMetrics metrics = session.result();
        exportReportButton.active = !running
                && metrics.presentSamples() > 0
                && !metrics.biomeCounts().isEmpty();
    }

    private void exportReport() {
        RegionMetrics metrics = session.result();
        if (metrics.presentSamples() <= 0 || metrics.biomeCounts().isEmpty()) {
            return;
        }
        try {
            ReportInput input = buildReportInput(metrics);
            Path outputDir = WorldPreview.get().configDir().resolve("reports");
            List<Path> written = new AnalysisReportExporter()
                    .write(input, outputDir, buildReportBaseName(input), REPORT_GSON);
            showExportStatus(Component.translatable("world_preview.analysis.export.done",
                    written.get(0).getFileName().toString(), written.get(1).getFileName().toString()), 0xFF55FF55);
        } catch (Exception e) {
            LOGGER.error("Analysis report export failed", e);
            showExportStatus(Component.translatable("world_preview.analysis.export.failed",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), 0xFFFF5555);
        }
    }

    private ReportInput buildReportInput(RegionMetrics metrics) {
        // Map biome ids to display names once; unknown ids fall back to "biome_<id>".
        Map<Short, String> biomeNames = new HashMap<>();
        for (BiomesList.BiomeEntry entry : previewContainer.allBiomes()) {
            biomeNames.put(entry.id(), entry.name());
        }
        LinkedHashMap<String, long[]> biomeTable = new LinkedHashMap<>();
        metrics.biomeCounts().entrySet().stream()
                .sorted(Map.Entry.<Short, Long>comparingByValue().reversed())
                .forEach(e -> biomeTable.merge(
                        biomeNames.getOrDefault(e.getKey(), "biome_" + e.getKey()),
                        new long[]{e.getValue()},
                        (summed, counts) -> {
                            summed[0] += counts[0];
                            return summed;
                        }));

        Region region = session.request().region();
        String regionDescription = region.minX() + "," + region.minZ()
                + " -> " + region.maxX() + "," + region.maxZ();

        WorldgenContext context = previewContainer.worldPreview().workManager().worldgenContext();
        String seed = context != null ? Long.toString(context.seed())
                : Long.toString(session.request().seed());
        String dimension = context != null ? context.dimension() : session.request().dimension();

        return new ReportInput(seed, dimension, regionDescription,
                metrics.expectedSamples(), metrics.presentSamples(), metrics.coverage(),
                biomeTable, metrics.minHeight(), metrics.maxHeight(), metrics.meanHeight(),
                metrics.medianHeight(), metrics.standardDeviation(), metrics.meanSlope(),
                metrics.maxSlope(), metrics.flatRatio());
    }

    private String buildReportBaseName(ReportInput input) {
        return "analysis_" + sanitizeFileToken(input.seed())
                + "_" + sanitizeFileToken(input.dimension())
                + "_" + LocalDateTime.now().format(EXPORT_TIMESTAMP_FORMAT);
    }

    /** Replaces ':' and other path-illegal characters so the token is safe as a filename part. */
    private static String sanitizeFileToken(String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private void showExportStatus(Component message, int color) {
        exportStatusMessage = message;
        exportStatusColor = color;
        exportStatusUntil = System.currentTimeMillis() + EXPORT_STATUS_MILLIS;
    }

    // ===== Spawn score & top biomes =====

    /**
     * Computes the spawn score and top-biome rarity for the current metrics and
     * pushes both to the analysis panel. Called once per metrics refresh (cheap);
     * clears both sections when there is no sample data yet.
     */
    private void refreshSpawnAndBiomePanels(RegionMetrics metrics) {
        if (metrics == null || metrics.biomeCounts().isEmpty() || metrics.presentSamples() <= 0) {
            analysisPanel.setSpawnAnalysis(null, List.of());
            analysisPanel.setTopBiomes(List.of());
            return;
        }
        Map<Short, BiomesList.BiomeEntry> byId = biomeIdLookup();
        long presentSamples = metrics.presentSamples();

        long waterSamples = 0;
        for (Map.Entry<Short, Long> entry : metrics.biomeCounts().entrySet()) {
            BiomesList.BiomeEntry biome = byId.get(entry.getKey());
            if (biome != null && isWaterBiome(biome)) {
                waterSamples += entry.getValue();
            }
        }
        double waterShare = waterSamples / (double) presentSamples;
        Double meanSlope = metrics.meanSlope().isPresent() ? metrics.meanSlope().getAsDouble() : null;

        SpawnAdvisor.SpawnResult spawn = SpawnAdvisor.evaluate(
                new SpawnAdvisor.SpawnInput(waterShare, metrics.flatRatio(), meanSlope, 0));
        List<Component> reasons = new ArrayList<>(spawn.reasons().size());
        for (SpawnAdvisor.Reason reason : spawn.reasons()) {
            reasons.add(Component.translatable(reason.key(), reason.args()));
        }
        analysisPanel.setSpawnAnalysis(spawn.score(), reasons);

        analysisPanel.setTopBiomes(BiomeRarity.topBiomes(metrics.biomeCounts(),
                id -> {
                    BiomesList.BiomeEntry biome = byId.get((short) id);
                    return biome != null ? biome.name() : null;
                },
                presentSamples, 5));
    }

    /** Lazily built (main thread) short-id → biome entry lookup. */
    private Map<Short, BiomesList.BiomeEntry> biomeIdLookup() {
        Map<Short, BiomesList.BiomeEntry> lookup = biomeIdLookup;
        if (lookup == null) {
            lookup = new HashMap<>();
            for (BiomesList.BiomeEntry entry : previewContainer.allBiomes()) {
                lookup.put(entry.id(), entry);
            }
            biomeIdLookup = lookup;
        }
        return lookup;
    }

    /** Ocean, deep-ocean or river biomes count as water; unknown biomes as land. */
    private static boolean isWaterBiome(BiomesList.BiomeEntry biome) {
        return biome.entry().is(IS_OCEAN) || biome.entry().is(IS_RIVER) || biome.entry().is(IS_DEEP_OCEAN);
    }

    @Override
    protected void init() {
        // Clear and rebuild so buttons are always at the end of the click order.
        clearWidgets();
        addRenderableWidget(regionSelector);
        selectorFields.forEach(this::addRenderableWidget);
        addRenderableWidget(analysisPanel);
        addRenderableWidget(profileChart);
        addRenderableWidget(previewContainer.previewDisplay());
        addRenderableWidget(startButton);
        addRenderableWidget(cancelButton);
        addRenderableWidget(exportReportButton);
        closeButton = Button.builder(CommonComponents.GUI_BACK, ignored -> onClose())
                .width(90).build();
        addRenderableWidget(closeButton);
        layoutWidgets();
        updateControlState();
        // Force the preview display to re-render on this screen instead of
        // reusing stale cached render data from the previous screen.
        previewContainer.previewDisplay().invalidateRenderCache();
    }

    private void layoutWidgets() {
        int left = 8;
        int top = 24;
        // Reserve a footer strip so the back button is never covered by charts/panels.
        int footerTop = height - 32;
        int contentBottom = Math.max(top + 80, footerTop - 6);
        int previewWidth = Math.max(180, width / 2 - 18);
        int rightX = width / 2 + 4;
        int rightWidth = Math.max(180, width / 2 - 12);
        int rightHeight = Math.max(1, contentBottom - (top + 24));
        int previewHeight = Math.max(1, rightHeight / 2 - 4);
        int profileHeight = Math.max(1, rightHeight - previewHeight - 4);

        regionSelector.layout(new ScreenRectangle(left, top, previewWidth, 42));
        int fieldX = regionSelector.getX();
        for (int i = 0; i < selectorFields.size(); i++) {
            AbstractWidget field = selectorFields.get(i);
            field.setX(fieldX + i * Math.max(1, (previewWidth - 8) / 4));
            field.setY(top + 18);
        }
        startButton.setX(left);
        startButton.setY(top + 46);
        cancelButton.setX(left + 96);
        cancelButton.setY(top + 46);

        analysisPanel.setX(left);
        analysisPanel.setY(top + 74);
        analysisPanel.setWidth(previewWidth);
        analysisPanel.setHeight(Math.max(80, contentBottom - (top + 74)));

        previewContainer.previewDisplay().setPosition(rightX, top + 24);
        previewContainer.previewDisplay().setSize(rightWidth, previewHeight);
        profileChart.setX(rightX);
        profileChart.setY(top + 24 + previewHeight + 4);
        profileChart.setWidth(rightWidth);
        profileChart.setHeight(profileHeight);

        exportReportButton.setX(width - 190);
        exportReportButton.setY(footerTop);
        exportReportButton.setWidth(90);
        exportReportButton.setHeight(20);

        if (closeButton != null) {
            closeButton.setX(width - 96);
            closeButton.setY(footerTop);
            closeButton.setWidth(90);
            closeButton.setHeight(20);
        }
    }

    public void resize(int width, int height) {
        super.resize(width, height);
        layoutWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        // Lightweight control-state update every tick.
        boolean running = session.isRunning();
        if (running != lastRunning) {
            updateControlState();
        } else {
            startButton.active = !running;
            cancelButton.active = running;
            refreshExportButton(running);
        }
        if (exportStatusMessage != null && System.currentTimeMillis() >= exportStatusUntil) {
            exportStatusMessage = null;
        }

        // Expensive metrics/profile updates only while the analysis is active, and throttled.
        if (!running) {
            // Still refresh final metrics once after a transition to terminal.
            if (lastRunning || profileRefreshCooldown == 0) {
                AnalysisProgress progress = session.progress();
                RegionMetrics metrics = session.result();
                analysisPanel.setMetrics(metrics);
                analysisPanel.setProgress(progress);
                refreshSpawnAndBiomePanels(metrics);
                profileRefreshCooldown = 20;
            }
            return;
        }

        if (profileRefreshCooldown > 0) {
            profileRefreshCooldown--;
            return;
        }
        profileRefreshCooldown = 10; // ~0.5s at 20 TPS

        AnalysisProgress progress = session.progress();
        RegionMetrics metrics = session.result();
        analysisPanel.setMetrics(metrics);
        analysisPanel.setProgress(progress);
        if (progress.status() == AnalysisStatus.RUNNING
                || progress.status() == AnalysisStatus.QUEUED
                || metrics.state() == AnalysisDataState.PENDING) {
            ProfileResult result = profileChart.result();
            if (result != null) {
                profileChart.setResult(session.profile(result.request()));
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Draw directly to avoid triggering the screen blur more than once per frame.
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.centeredText(font, WorldPreviewComponents.ANALYSIS_TITLE, width / 2, 8, 0xFFFFFFFF);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        // Transient export status line, left of the footer buttons.
        if (exportStatusMessage != null) {
            graphics.text(font, exportStatusMessage, 8, height - 26, exportStatusColor);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // Prefer the back button even if a large display widget still overlaps it.
        if (closeButton != null && closeButton.visible && closeButton.active
                && closeButton.isMouseOver(event.x(), event.y())) {
            return closeButton.mouseClicked(event, doubleClick);
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (super.keyPressed(event)) {
            return true;
        }
        // ESC / inventory key should always leave this screen.
        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        if (closed) return;
        closed = true;
        // Always leave the screen first so a slow cleanup cannot freeze navigation.
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
        try {
            session.close();
        } catch (Throwable ignored) {
            // Ignore close errors so the screen still transitions back.
        }
    }
}
