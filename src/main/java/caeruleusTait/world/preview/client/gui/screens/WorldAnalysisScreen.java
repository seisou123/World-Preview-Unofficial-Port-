package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.backend.analysis.AnalysisDataState;
import caeruleusTait.world.preview.backend.analysis.AnalysisProgress;
import caeruleusTait.world.preview.backend.analysis.AnalysisRequest;
import caeruleusTait.world.preview.backend.analysis.AnalysisSession;
import caeruleusTait.world.preview.backend.analysis.AnalysisStatus;
import caeruleusTait.world.preview.backend.analysis.BiomeRarity;
import caeruleusTait.world.preview.backend.analysis.ProfileRequest;
import caeruleusTait.world.preview.backend.analysis.ProfileResult;
import caeruleusTait.world.preview.backend.analysis.Region;
import caeruleusTait.world.preview.backend.analysis.RegionAnalyzer;
import caeruleusTait.world.preview.backend.analysis.RegionInsights;
import caeruleusTait.world.preview.backend.analysis.RegionMetrics;
import caeruleusTait.world.preview.backend.analysis.SpawnAdvisor;
import caeruleusTait.world.preview.backend.export.AnalysisReportExporter;
import caeruleusTait.world.preview.backend.export.AnalysisReportExporter.ReportInput;
import caeruleusTait.world.preview.backend.export.TerrainCategory;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.PanelRenderer;
import caeruleusTait.world.preview.client.gui.widgets.AnalysisOverviewPanel;
import caeruleusTait.world.preview.client.gui.widgets.ProfileChart;
import caeruleusTait.world.preview.client.gui.widgets.RegionSelector;
import caeruleusTait.world.preview.client.gui.widgets.TranslucentButton;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

/**
 * Panelized region analysis screen: region selector + action toolbar on top,
 * the {@link AnalysisOverviewPanel} on the left, and map preview + profile
 * chart on the right (the tab row between them lands with the chart tasks).
 *
 * <p>Session semantics are unchanged from the pre-redesign screen: stale
 * detection on worldgen-context replacement (lock controls + error line),
 * detach-on-close with background re-attach, and the export lineage gate.</p>
 */
public final class WorldAnalysisScreen extends Screen {
    private static final long STATUS_MILLIS = 6000L;
    /** Toolbar top (the region selector label row). */
    private static final int TOOLBAR_TOP = 24;
    private static final DateTimeFormatter EXPORT_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Gson REPORT_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Shared status bar colors (info yellow matches PanelRenderer.statusBar's text color). */
    private static final int STATUS_INFO = 0xFFFFFF55;
    private static final int STATUS_GOOD = 0xFF55FF55;
    private static final int STATUS_BAD = 0xFFFF5555;

    /** Region side presets cycled by the size button, in blocks. */
    private static final int[] REGION_PRESETS = {256, 512, 1024, 2048, 4096};

    /**
     * Structure types probed for the "nearest structures" section. A
     * LinkedHashSet keeps the probe request (and thus result insertion) order
     * stable. Pure worldgen-math probe, safe on the render thread.
     */
    private static final Set<Identifier> ANALYSIS_STRUCTURES = new LinkedHashSet<>(List.of(
            Identifier.parse("minecraft:village"),
            Identifier.parse("minecraft:pillager_outpost"),
            Identifier.parse("minecraft:ancient_city"),
            Identifier.parse("minecraft:trial_chambers"),
            Identifier.parse("minecraft:ocean_monument"),
            Identifier.parse("minecraft:woodland_mansion"),
            Identifier.parse("minecraft:desert_pyramid"),
            Identifier.parse("minecraft:jungle_pyramid"),
            Identifier.parse("minecraft:igloo"),
            Identifier.parse("minecraft:swamp_hut")));

    private final Screen parent;
    private final PreviewContainer previewContainer;
    private final RegionSelector regionSelector;
    private final AnalysisOverviewPanel overviewPanel;
    private final ProfileChart profileChart;
    private final List<AbstractWidget> selectorFields;
    /**
     * Non-final: starting the analysis on a changed region swaps in a fresh
     * session (the old one is closed by the container's restart).
     */
    private AnalysisSession session;
    private Region region;

    private final TranslucentButton startButton;
    private final TranslucentButton pauseButton;
    private final TranslucentButton cancelButton;
    private final TranslucentButton alignViewportButton;
    private final TranslucentButton presetButton;
    private final TranslucentButton boxSelectButton;
    private final TranslucentButton locateButton;
    private final TranslucentButton directionButton;
    private final TranslucentButton exportReportButton;
    private TranslucentButton closeButton;

    /** Profile line preset cycled by {@link #directionButton} (label shows the current one). */
    private ProfileChart.Direction profileDirection = ProfileChart.Direction.DIAGONAL;

    private boolean closed;
    /** True once the worldgen context this session belongs to has been replaced. */
    private boolean stale;
    private int profileRefreshCooldown;
    private boolean lastRunning;
    /**
     * Running state as of the previous tick. Transition detection must use
     * this, not {@link #lastRunning}: updateControlState() (also invoked
     * directly by the start/cancel/pause handlers between ticks) syncs
     * lastRunning eagerly, which would swallow the running→terminal
     * transition and leave the panel on its last in-flight snapshot.
     */
    private boolean prevTickRunning;

    /** Shared status line (validation / clamping / export), expires after {@link #STATUS_MILLIS}. */
    @Nullable private Component statusMessage;
    private int statusColor = STATUS_INFO;
    private long statusUntil;
    private int footerRowY;

    /** Preset index shown on the size button; pressing applies it and advances. */
    private int presetIndex;

    /** Nearest structure per probe type; null = analytic probing unavailable. */
    @Nullable private Map<Identifier, BlockPos> nearestStructures;
    /** Number of distinct structure types the last probe found (spawn bonus input). */
    private int structuresNearby;
    /** Top-5 biome rarity rows, kept for the upcoming share panel (chart task). */
    private List<BiomeRarity.RarityRow> topBiomes = List.of();
    /** Lazily built short-id → biome entry lookup for insights / top biomes. */
    @Nullable private Map<Short, BiomesList.BiomeEntry> biomeIdLookup;

    public WorldAnalysisScreen(Screen parent, AnalysisSession session,
                               PreviewContainer previewContainer, Region initialRegion) {
        super(WorldPreviewComponents.ANALYSIS_TITLE);
        this.parent = parent;
        this.session = session;
        this.previewContainer = previewContainer;
        this.region = initialRegion;
        this.regionSelector = new RegionSelector(Minecraft.getInstance().font, 0, 0, 300, 45,
                initialRegion, this::setRegion);
        this.overviewPanel = new AnalysisOverviewPanel(0, 0, 260, 140);
        this.profileChart = new ProfileChart(0, 0, 360, 170);
        this.selectorFields = new ArrayList<>(regionSelector.fields());
        Font font = Minecraft.getInstance().font;
        this.startButton = new TranslucentButton(font, 0, 0, 78, 20,
                WorldPreviewComponents.ANALYSIS_START, ignored -> startAnalysis());
        this.pauseButton = new TranslucentButton(font, 0, 0, 66, 20,
                WorldPreviewComponents.ANALYSIS_ACTION_PAUSE, ignored -> togglePause());
        this.cancelButton = new TranslucentButton(font, 0, 0, 66, 20,
                WorldPreviewComponents.ANALYSIS_CANCEL, ignored -> cancelAnalysis());
        this.alignViewportButton = new TranslucentButton(font, 0, 0, 80, 20,
                WorldPreviewComponents.ANALYSIS_ACTION_ALIGN, ignored -> alignViewport());
        this.presetButton = new TranslucentButton(font, 0, 0, 82, 20,
                presetLabel(), ignored -> cyclePreset());
        // Box-select / locate MODE wiring lands with the map-interaction task;
        // the buttons exist so the layout is final.
        this.boxSelectButton = new TranslucentButton(font, 0, 0, 90, 20,
                WorldPreviewComponents.ANALYSIS_ACTION_BOXSELECT, ignored -> { });
        this.locateButton = new TranslucentButton(font, 0, 0, 90, 20,
                WorldPreviewComponents.ANALYSIS_ACTION_LOCATE, ignored -> { });
        this.directionButton = new TranslucentButton(font, 0, 0, 90, 20,
                profileDirectionLabel(), ignored -> cycleProfileDirection());
        this.exportReportButton = new TranslucentButton(font, 0, 0, 90, 20,
                WorldPreviewComponents.ANALYSIS_EXPORT_REPORT, ignored -> exportReport());
        this.exportReportButton.active = false;
    }

    public AnalysisSession session() {
        return session;
    }

    public RegionSelector regionSelector() {
        return regionSelector;
    }

    public AnalysisOverviewPanel overviewPanel() {
        return overviewPanel;
    }

    public ProfileChart profileChart() {
        return profileChart;
    }

    private Component presetLabel() {
        return Component.translatable("world_preview.analysis.action.preset", REGION_PRESETS[presetIndex]);
    }

    private void setRegion(Region region) {
        this.region = region;
        // No profile clear here: clearing while the user is still typing wiped
        // the chart on every keystroke. The profile only rebuilds on
        // startAnalysis / direction switch / the running-throttle re-poll.
    }

    /** Aligns the analysis region to the preview viewport (same recipe as the container's open). */
    private void alignViewport() {
        var display = previewContainer.previewDisplay();
        BlockPos center = display.center();
        // Symmetric ±radius gives 2*radius+1 wide, so cap at 2047 to stay
        // within RegionSelector.MAX_DIMENSION (4096).
        int radius = Math.max(1, Math.min(2047, display.getWidth() * 4));
        Region next = Region.of(center.getX() - radius, center.getZ() - radius,
                center.getX() + radius, center.getZ() + radius);
        regionSelector.setRegion(next);
        setRegion(next);
    }

    /** Applies the current region-size preset centered on the region, then advances the preset. */
    private void cyclePreset() {
        int size = REGION_PRESETS[presetIndex];
        Region base = regionSelector.currentRegion().orElse(region);
        int cx = (base.minX() + base.maxX()) / 2;
        int cz = (base.minZ() + base.maxZ()) / 2;
        int minX = cx - (size - 1) / 2;
        int minZ = cz - (size - 1) / 2;
        Region next = Region.of(minX, minZ, minX + size - 1, minZ + size - 1);
        regionSelector.setRegion(next);
        setRegion(next);
        presetIndex = (presetIndex + 1) % REGION_PRESETS.length;
        presetButton.setMessage(presetLabel());
    }

    private void startAnalysis() {
        if (stale) {
            return;
        }
        Region target = regionSelector.currentRegion().orElse(regionSelector.lastValidRegion());
        if (regionSelector.hasError()) {
            showStatus(WorldPreviewComponents.ANALYSIS_REGION_INVALID, STATUS_BAD);
        }
        target = clampToMaxBlocks(target);
        Region started = restartTo(target);
        if (started == null) {
            return;
        }
        session.start();
        // A fresh run invalidates the previously computed spawn/top-biome data.
        overviewPanel.setSpawn(null, null, List.of());
        topBiomes = List.of();
        rebuildProfile();
        refreshStructures();
        profileRefreshCooldown = 0;
        updateControlState();
    }

    /**
     * Swaps in a fresh session when the target region differs from the
     * running/finished session's region; the container closes and replaces the
     * owned session. Returns the session's (normalized) region, or null when
     * the restart failed (status shown, old session left untouched).
     */
    private @Nullable Region restartTo(Region target) {
        if (target.equals(session.request().region())) {
            return session.request().region();
        }
        AnalysisRequest old = session.request();
        AnalysisRequest fresh = new AnalysisRequest(old.seed(), old.dimension(), target,
                old.y(), old.sampleStep(), old.includeHeight(), old.includeIntersections(), old.includeNoise());
        AnalysisSession restarted = previewContainer.restartAnalysisSession(fresh);
        if (restarted == null) {
            showStatus(WorldPreviewComponents.ANALYSIS_RESTART_FAILED, STATUS_BAD);
            return null;
        }
        session = restarted;
        stale = false;
        profileChart.setResult(null);
        return restarted.request().region();
    }

    /**
     * Region cap for the restart path: {@code WorkManager.analysisRequest()}
     * clamps against {@code analysisMaxRegionBlocks}, but a restarted session
     * bypasses it — clamp here (same formula) and tell the user. Applied
     * before the restart so exactly one session swap happens per start.
     */
    private Region clampToMaxBlocks(Region target) {
        long maxSide = (long) Math.floor(Math.sqrt(Math.max(1.0,
                (double) WorldPreview.get().cfg().analysisMaxRegionBlocks)));
        long w = (long) target.maxX() - target.minX() + 1;
        long d = (long) target.maxZ() - target.minZ() + 1;
        if (w <= maxSide && d <= maxSide) {
            return target;
        }
        int half = (int) Math.min(maxSide / 2L, Integer.MAX_VALUE / 4L);
        int cx = (target.minX() + target.maxX()) / 2;
        int cz = (target.minZ() + target.maxZ()) / 2;
        Region clamped = Region.of(cx - half, cz - half, cx + half, cz + half);
        showStatus(Component.translatable("world_preview.analysis.region.clamped",
                (long) clamped.maxX() - clamped.minX() + 1,
                (long) clamped.maxZ() - clamped.minZ() + 1,
                session.request().sampleStep()), STATUS_INFO);
        return clamped;
    }

    /**
     * Rebuilds the profile chart from the session's current request along the
     * selected direction: diagonal = region diagonal, E-W / N-S = the
     * horizontal / vertical region center lines (at the fixed analysis Y).
     */
    private void rebuildProfile() {
        AnalysisRequest request = session.request();
        Region r = request.region();
        int y = request.y();
        int step = Math.max(1, request.sampleStep());
        ProfileRequest profile = switch (profileDirection) {
            case DIAGONAL -> new ProfileRequest(r.minX(), r.minZ(), r.maxX(), r.maxZ(), y, y, step, false);
            case EAST_WEST -> new ProfileRequest(r.minX(), (r.minZ() + r.maxZ()) / 2, r.maxX(),
                    (r.minZ() + r.maxZ()) / 2, y, y, step, false);
            case NORTH_SOUTH -> new ProfileRequest((r.minX() + r.maxX()) / 2, r.minZ(),
                    (r.minX() + r.maxX()) / 2, r.maxZ(), y, y, step, false);
        };
        profileChart.setResult(session.profile(profile));
    }

    private Component profileDirectionLabel() {
        return switch (profileDirection) {
            case DIAGONAL -> WorldPreviewComponents.ANALYSIS_DIR_DIAGONAL;
            case EAST_WEST -> WorldPreviewComponents.ANALYSIS_DIR_EASTWEST;
            case NORTH_SOUTH -> WorldPreviewComponents.ANALYSIS_DIR_NORTHSOUTH;
        };
    }

    /** Cycles 对角 → 东西 → 南北 and rebuilds the profile along the new line. */
    private void cycleProfileDirection() {
        profileDirection = switch (profileDirection) {
            case DIAGONAL -> ProfileChart.Direction.EAST_WEST;
            case EAST_WEST -> ProfileChart.Direction.NORTH_SOUTH;
            case NORTH_SOUTH -> ProfileChart.Direction.DIAGONAL;
        };
        directionButton.setMessage(profileDirectionLabel());
        rebuildProfile();
    }

    /**
     * One-shot analytic structure probe around the region center; feeds the
     * overview panel and the spawn score's structure bonus.
     */
    private void refreshStructures() {
        Region r = session.request().region();
        int cx = (r.minX() + r.maxX()) / 2;
        int cz = (r.minZ() + r.maxZ()) / 2;
        int radius = Math.max((r.maxX() - r.minX()) / 2, (r.maxZ() - r.minZ()) / 2);
        int maxDistance = Math.min(4096, Math.max(1024, radius * 2));
        Map<Identifier, BlockPos> found = previewContainer.probeNearestStructures(
                ANALYSIS_STRUCTURES, new BlockPos(cx, session.request().y(), cz), maxDistance);
        nearestStructures = found;
        structuresNearby = found == null ? 0 : found.size();
        overviewPanel.setStructures(found, cx, cz);
    }

    private void togglePause() {
        if (stale) {
            return;
        }
        if (session.progress().status() == AnalysisStatus.PAUSED) {
            session.resume();
        } else {
            session.pause();
        }
        updateControlState();
    }

    private void cancelAnalysis() {
        session.cancel();
        updateControlState();
    }

    private void updateControlState() {
        boolean running = session.isRunning();
        boolean paused = session.progress().status() == AnalysisStatus.PAUSED;
        startButton.active = !running && !stale;
        pauseButton.active = running && !stale;
        pauseButton.setMessage(paused
                ? WorldPreviewComponents.ANALYSIS_ACTION_RESUME
                : WorldPreviewComponents.ANALYSIS_ACTION_PAUSE);
        cancelButton.active = running && !stale;
        directionButton.active = !stale;
        refreshExportButton(running);
        if (closeButton != null) {
            // Closing must always remain possible.
            closeButton.active = true;
        }
        lastRunning = running;
    }

    private void refreshExportButton(boolean running) {
        // Only finished analyses have non-partial metrics worth reporting.
        // hasExportableData() is an O(1) check; session.result() would
        // rebuild (and, while running, re-sort) the full metrics snapshot
        // every tick on the client thread.
        exportReportButton.active = !stale && !running && session.hasExportableData();
    }

    private void exportReport() {
        RegionMetrics metrics = session.result();
        if (metrics.presentSamples() <= 0 || metrics.biomeCounts().isEmpty()) {
            return;
        }
        // Lineage gate: reports may only be produced from metrics that were
        // computed under the currently active worldgen context.
        if (stale || session.isStale(previewContainer.workManager().epoch())) {
            LOGGER.warn("Analysis report export rejected: session belongs to a replaced worldgen context");
            showStatus(Component.translatable("world_preview.analysis.export.stale"), STATUS_BAD);
            return;
        }
        try {
            ReportInput input = buildReportInput(metrics);
            Path outputDir = WorldPreview.get().configDir().resolve("reports");
            List<Path> written = new AnalysisReportExporter()
                    .write(input, outputDir, buildReportBaseName(session.request().region()), REPORT_GSON);
            showStatus(Component.translatable("world_preview.analysis.export.done",
                    written.get(0).getFileName().toString(), written.get(1).getFileName().toString()), STATUS_GOOD);
        } catch (Exception e) {
            LOGGER.error("Analysis report export failed", e);
            showStatus(Component.translatable("world_preview.analysis.export.failed",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), STATUS_BAD);
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

        // Diversity + terrain insights; the holder resolver maps short biome
        // ids back to their registry holders via the lazily built lookup.
        RegionInsights insights = RegionAnalyzer.fromBiomeCounts(metrics.biomeCounts(),
                id -> {
                    BiomesList.BiomeEntry entry = biomeIdLookup().get((short) id);
                    return entry != null ? entry.entry() : null;
                });
        LinkedHashMap<String, Long> terrainTable = new LinkedHashMap<>();
        for (Map.Entry<TerrainCategory, Long> e : insights.terrainCounts().entrySet()) {
            terrainTable.put(e.getKey().name(), e.getValue());
        }

        // Nearest-structure distances in blocks (Manhattan, from the region center).
        LinkedHashMap<String, Long> structureTable = new LinkedHashMap<>();
        if (nearestStructures != null) {
            Region r = session.request().region();
            int cx = (r.minX() + r.maxX()) / 2;
            int cz = (r.minZ() + r.maxZ()) / 2;
            for (Map.Entry<Identifier, BlockPos> e : nearestStructures.entrySet()) {
                int distance = Math.abs(e.getValue().getX() - cx) + Math.abs(e.getValue().getZ() - cz);
                structureTable.put(structureDisplayName(e.getKey()), (long) distance);
            }
        }

        Region region = session.request().region();
        String regionDescription = region.minX() + "," + region.minZ()
                + " -> " + region.maxX() + "," + region.maxZ();

        // Lineage: report the seed/dimension/context the analysis itself was
        // created with. Reading the live context here would mislabel metrics
        // when the world changed after the analysis ran.
        String seed = Long.toString(session.request().seed());
        String dimension = session.request().dimension();
        String contextId = session.originIdentityKey() != null ? session.originIdentityKey() : "unknown";

        return new ReportInput(seed, dimension, regionDescription,
                metrics.expectedSamples(), metrics.presentSamples(), metrics.coverage(),
                biomeTable, metrics.minHeight(), metrics.maxHeight(), metrics.meanHeight(),
                metrics.medianHeight(), metrics.standardDeviation(), metrics.meanSlope(),
                metrics.maxSlope(), metrics.flatRatio(), contextId,
                metrics.waterShare(), insights.shannonDiversity(), insights.effectiveBiomeCount(),
                terrainTable, structureTable,
                metrics.heightHistogram(), metrics.histogramMinY());
    }

    /** Structure label: {@code world_preview.structure.<path>} when translated, else the raw path. */
    private static String structureDisplayName(Identifier id) {
        String key = "world_preview.structure." + id.getPath();
        return Language.getInstance().has(key)
                ? Component.translatable(key).getString()
                : id.getPath();
    }

    /** Report file name with seed/dimension and the analyzed region's corner tokens. */
    private String buildReportBaseName(Region region) {
        return "analysis_" + sanitizeFileToken(Long.toString(session.request().seed()))
                + "_" + sanitizeFileToken(session.request().dimension())
                + "_" + region.minX() + "_" + region.minZ()
                + "_" + region.maxX() + "_" + region.maxZ()
                + "_" + LocalDateTime.now().format(EXPORT_TIMESTAMP_FORMAT);
    }

    /** Replaces ':' and other path-illegal characters so the token is safe as a filename part. */
    private static String sanitizeFileToken(String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private void showStatus(Component message, int color) {
        statusMessage = message;
        statusColor = color;
        statusUntil = System.currentTimeMillis() + STATUS_MILLIS;
    }

    // ===== Spawn score & top biomes =====

    /**
     * Computes the spawn score (with its four part scores) and top-biome
     * rarity for the current metrics and pushes both to the overview panel.
     * Called once per metrics refresh — including the mid-run 0.5s cadence,
     * so the score now moves while the analysis runs.
     */
    private void refreshSpawnAndBiomePanels(RegionMetrics metrics) {
        if (metrics == null || metrics.biomeCounts().isEmpty() || metrics.presentSamples() <= 0) {
            overviewPanel.setSpawn(null, null, List.of());
            topBiomes = List.of();
            return;
        }
        double waterShare = metrics.waterShare();
        // Slope must be normalized per sample step for the spawn score (the
        // old code fed the per-block slope in, double-normalizing it against
        // SpawnAdvisor's per-step threshold).
        Double meanSlopePerStep = metrics.meanSlope().isPresent()
                ? metrics.meanSlope().getAsDouble() * session.request().sampleStep()
                : null;
        int spawnStructures = Math.min(2, structuresNearby);
        SpawnAdvisor.SpawnResult spawn = SpawnAdvisor.evaluate(
                new SpawnAdvisor.SpawnInput(waterShare, metrics.flatRatio(), meanSlopePerStep, spawnStructures));

        // SpawnResult has no part accessor, so the screen computes the four
        // part scores itself with the same formula and public constants.
        int flatPart = (int) Math.round(SpawnAdvisor.FLAT_WEIGHT * metrics.flatRatio());
        int slopePart = meanSlopePerStep == null
                ? SpawnAdvisor.SLOPE_WEIGHT / 2
                : (int) Math.round(SpawnAdvisor.SLOPE_WEIGHT
                        * (1.0 - Math.min(1.0, Math.max(0.0, meanSlopePerStep / SpawnAdvisor.STEEP_SLOPE))));
        int waterPart = (int) Math.round(SpawnAdvisor.WATER_WEIGHT * (1.0 - waterShare));
        int structurePart = (int) Math.min(SpawnAdvisor.STRUCTURE_WEIGHT,
                (double) structuresNearby * SpawnAdvisor.STRUCTURE_POINTS_EACH);
        int[] parts = {flatPart, slopePart, waterPart, structurePart};

        List<Component> reasons = new ArrayList<>(spawn.reasons().size());
        for (SpawnAdvisor.Reason reason : spawn.reasons()) {
            reasons.add(Component.translatable(reason.key(), reason.args()));
        }
        overviewPanel.setSpawn(spawn.score(), parts, reasons);

        // Kept for the biome share panel (chart task).
        topBiomes = BiomeRarity.topBiomes(metrics.biomeCounts(),
                id -> {
                    BiomesList.BiomeEntry biome = biomeIdLookup().get((short) id);
                    return biome != null ? biome.name() : null;
                },
                metrics.presentSamples(), 5);
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

    @Override
    protected void init() {
        // Clear and rebuild so buttons are always at the end of the click order.
        clearWidgets();
        addRenderableWidget(regionSelector);
        selectorFields.forEach(this::addRenderableWidget);
        addRenderableWidget(overviewPanel);
        addRenderableWidget(profileChart);
        addRenderableWidget(previewContainer.previewDisplay());
        addRenderableWidget(startButton);
        addRenderableWidget(pauseButton);
        addRenderableWidget(cancelButton);
        addRenderableWidget(boxSelectButton);
        addRenderableWidget(locateButton);
        addRenderableWidget(alignViewportButton);
        addRenderableWidget(presetButton);
        addRenderableWidget(directionButton);
        addRenderableWidget(exportReportButton);
        closeButton = new TranslucentButton(Minecraft.getInstance().font, 0, 0, 90, 20,
                CommonComponents.GUI_BACK, ignored -> onClose());
        addRenderableWidget(closeButton);
        layoutWidgets();
        updateControlState();
        // Biome colors/names for the profile bands + tooltip come from the
        // biome list lookup (built lazily, main thread); unknown ids fall back
        // inside ProfileChart (gray band / "biome_<id>").
        profileChart.setPalette(this::profileBiomeColor, this::profileBiomeName);
        Integer seaLevel = previewContainer.analysisSeaLevel();
        profileChart.setSeaLevel(seaLevel != null ? seaLevel : -1);
        // Force the preview display to re-render on this screen instead of
        // reusing stale cached render data from the previous screen.
        previewContainer.previewDisplay().invalidateRenderCache();
    }

    /** ARGB band color for a short biome id; null (chart falls back to gray) when unknown. */
    private @Nullable Integer profileBiomeColor(int id) {
        BiomesList.BiomeEntry entry = biomeIdLookup().get((short) id);
        // entry.color() is stored without the alpha byte (the biome lists draw
        // it through WorldPreview.nativeColor), so force an opaque ARGB here.
        return entry != null ? entry.color() | 0xFF000000 : null;
    }

    /** Display name for a short biome id; null (chart falls back to "biome_<id>") when unknown. */
    private @Nullable String profileBiomeName(int id) {
        BiomesList.BiomeEntry entry = biomeIdLookup().get((short) id);
        return entry != null ? entry.name() : null;
    }

    private static void place(AbstractWidget widget, int x, int y, int width, int height) {
        widget.setX(x);
        widget.setY(y);
        widget.setWidth(width);
        widget.setHeight(height);
    }

    private void layoutWidgets() {
        int left = 8;
        int top = 24;
        // Reserve a footer strip so the action row is never covered by panels.
        int footerTop = height - 32;
        footerRowY = footerTop;
        int panelBottom = footerTop - 8;
        // Below the two toolbar rows. (Adapted from the brief's 74: the second
        // action row occupies y 68..88, so 74 would put the panels under it.)
        int panelsTop = 94;
        int leftW = Math.max(240, width * 36 / 100);
        int rightX = left + leftW + 6;
        int rightW = Math.max(60, width - rightX - 8);

        // Row 1: region selector fields + Match-View / size buttons. The
        // selector keeps a minimum width on narrow screens; the buttons are
        // right-shifted (never past the screen edge) so they cannot overlap.
        int row1ButtonsW = 80 + 4 + 82;
        int selectorRight = Math.max(left + 150, left + leftW - row1ButtonsW - 4);
        selectorRight = Math.min(selectorRight, width - 8 - row1ButtonsW - 6);
        int selectorW = Math.max(120, selectorRight - left);
        regionSelector.layout(new ScreenRectangle(left, top, selectorW, 42));
        int row1X = Math.min(left + selectorW + 6, width - 8 - row1ButtonsW);
        place(alignViewportButton, row1X, top + 18, 80, 20);
        place(presetButton, row1X + 84, top + 18, 82, 20);

        // Row 2: analysis actions.
        int row2Y = top + 44;
        place(startButton, left, row2Y, 78, 20);
        place(pauseButton, left + 82, row2Y, 66, 20);
        place(cancelButton, left + 152, row2Y, 66, 20);
        place(boxSelectButton, left + 222, row2Y, 90, 20);
        place(locateButton, left + 316, row2Y, 90, 20);

        overviewPanel.setX(left);
        overviewPanel.setY(panelsTop);
        overviewPanel.setWidth(leftW);
        overviewPanel.setHeight(Math.max(60, panelBottom - panelsTop));

        int mapH = Math.max(120, (int) ((panelBottom - panelsTop) * 0.45));
        previewContainer.previewDisplay().setPosition(rightX, panelsTop);
        previewContainer.previewDisplay().setSize(rightW, mapH);
        // The chart tab row will sit at tabsY (inserted by the chart task);
        // the profile chart starts 22px below it. The direction button takes
        // the right end of the reserved tab-row band until then.
        int tabsY = panelsTop + mapH + 4;
        place(directionButton, Math.max(rightX, rightX + rightW - 90), tabsY, 90, 20);
        profileChart.setX(rightX);
        profileChart.setY(tabsY + 22);
        profileChart.setWidth(rightW);
        profileChart.setHeight(Math.max(60, panelBottom - tabsY - 22));

        place(exportReportButton, width - 190, footerTop, 90, 20);
        if (closeButton != null) {
            place(closeButton, width - 96, footerTop, 90, 20);
        }
    }

    public void resize(int width, int height) {
        super.resize(width, height);
        layoutWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        // Stale detection: the session keeps running after the screen closes;
        // when the worldgen context it was created under has been replaced,
        // cancel it and lock the controls instead of letting it write results
        // (or reports) belonging to another world.
        if (!stale && session.isStale(previewContainer.workManager().epoch())) {
            stale = true;
            session.cancel();
            AnalysisProgress progress = session.progress();
            overviewPanel.setSessionData(progress, session.request().y(), session.request().sampleStep());
            overviewPanel.setMetrics(session.result());
            overviewPanel.setError(Component.translatable("world_preview.analysis.stale"));
            showStatus(Component.translatable("world_preview.analysis.stale"), STATUS_BAD);
            updateControlState();
            return;
        }
        if (stale) {
            return;
        }

        // Lightweight control-state update every tick.
        boolean running = session.isRunning();
        // Compare against the PREVIOUS tick's value, captured before anything
        // else can sync state for this tick: updateControlState() writes
        // lastRunning at its end, so a lastRunning-based check below would
        // always see "no transition" on the very tick the analysis ends.
        boolean transitioned = prevTickRunning != running;
        prevTickRunning = running;
        if (running != lastRunning) {
            updateControlState();
        } else {
            startButton.active = !running;
            pauseButton.active = running;
            cancelButton.active = running;
            refreshExportButton(running);
        }
        if (statusMessage != null && System.currentTimeMillis() >= statusUntil) {
            statusMessage = null;
        }

        // Expensive metrics/profile updates only while the analysis is active, and throttled.
        if (!running) {
            // Push the final snapshot exactly once on a running→terminal
            // transition (completion / cancel / failure — including cancels
            // issued by the button handler between ticks), and once at screen
            // open (the cooldown starts at 0). Otherwise stay idle: a finished
            // session's data cannot change, so there is no periodic repoll.
            if (transitioned || profileRefreshCooldown == 0) {
                AnalysisProgress progress = session.progress();
                RegionMetrics metrics = session.result();
                overviewPanel.setSessionData(progress, session.request().y(), session.request().sampleStep());
                overviewPanel.setMetrics(metrics);
                profileChart.setMeanHeight(metrics.meanHeight().isPresent()
                        ? metrics.meanHeight().getAsDouble() : null);
                overviewPanel.setError(progress.error() != null
                        ? Component.translatable("world_preview.analysis.error", progress.error())
                        : null);
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
        overviewPanel.setSessionData(progress, session.request().y(), session.request().sampleStep());
        overviewPanel.setMetrics(metrics);
        profileChart.setMeanHeight(metrics.meanHeight().isPresent()
                ? metrics.meanHeight().getAsDouble() : null);
        overviewPanel.setError(progress.error() != null
                ? Component.translatable("world_preview.analysis.error", progress.error())
                : null);
        // Spawn score/top biomes refresh at the same 0.5s cadence as the
        // metrics — the old screen computed them only once, so the score and
        // the biome shares never updated while the analysis ran.
        refreshSpawnAndBiomePanels(metrics);
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw directly to avoid triggering the screen blur more than once per frame.
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.drawCenteredString(font, WorldPreviewComponents.ANALYSIS_TITLE, width / 2, 8, 0xFFFFFFFF);
        // Gray summary of the analysis plane (Y layer + sample step), right-aligned in the toolbar area.
        Component viewInfo = Component.translatable("world_preview.analysis.y_step",
                session.request().y(), session.request().sampleStep());
        graphics.drawString(font, viewInfo, Math.max(0, width - 8 - font.width(viewInfo)), TOOLBAR_TOP + 6, 0xFF999999);
        super.render(graphics, mouseX, mouseY, partialTick);
        // Shared status line (region validation / clamping / export), 6s expiry.
        // Task 10 draws its box-select hint bar above the map here when active.
        if (statusMessage != null && System.currentTimeMillis() < statusUntil) {
            if (statusColor == STATUS_INFO) {
                PanelRenderer.statusBar(graphics, font, width, footerRowY, statusMessage);
            } else {
                // Colored status (red/green): same band geometry as
                // PanelRenderer.statusBar, only the text color differs.
                graphics.fill(0, footerRowY + 20, width, height, 0xAA000000);
                graphics.drawString(font, statusMessage, 8, height - 11, statusColor);
            }
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
        // Detach only: the session is owned by the PreviewContainer so a
        // running analysis keeps going in the background and is re-attached
        // (or cancelled on a world change) by the container.
    }
}
