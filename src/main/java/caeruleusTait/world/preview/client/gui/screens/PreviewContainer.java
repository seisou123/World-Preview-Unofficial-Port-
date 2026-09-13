// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.analysis.AnalysisSession;
import caeruleusTait.world.preview.backend.analysis.AnalysisRequest;
import caeruleusTait.world.preview.backend.analysis.LightweightSeedSampler;
import caeruleusTait.world.preview.backend.analysis.Region;
import caeruleusTait.world.preview.backend.analysis.SeedSearchRequest;
import caeruleusTait.world.preview.backend.analysis.SeedSearchResult;
import caeruleusTait.world.preview.backend.analysis.SeedSearchService;
import caeruleusTait.world.preview.backend.analysis.WorldgenContext;
import caeruleusTait.world.preview.client.gui.widgets.RegionSelector;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.backend.color.ColorMap;
import caeruleusTait.world.preview.backend.color.NoiseColorProvider;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.color.PreviewMappingData;
import caeruleusTait.world.preview.client.gui.PreviewContainerDataProvider;
import caeruleusTait.world.preview.client.gui.PreviewDisplayDataProvider;
import caeruleusTait.world.preview.client.gui.widgets.OldStyleImageButton;
import caeruleusTait.world.preview.client.gui.widgets.PreviewDisplay;
import caeruleusTait.world.preview.client.gui.widgets.ToggleButton;
import caeruleusTait.world.preview.client.gui.widgets.TranslucentButton;
import caeruleusTait.world.preview.client.gui.widgets.lists.BaseObjectSelectionList;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import caeruleusTait.world.preview.client.gui.widgets.lists.StructuresList;
import caeruleusTait.world.preview.mixin.client.ScreenAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import caeruleusTait.world.preview.backend.export.TerrainCategory;
import caeruleusTait.world.preview.backend.export.TerrainClassifier;
import caeruleusTait.world.preview.backend.export.TerrainExportController;
import caeruleusTait.world.preview.backend.export.TerrainExportSpec;
import caeruleusTait.world.preview.backend.export.TerrainMapExporter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static caeruleusTait.world.preview.RenderSettings.RenderMode.BIOMES;
import static caeruleusTait.world.preview.RenderSettings.RenderMode.HEIGHTMAP;
import static caeruleusTait.world.preview.RenderSettings.RenderMode.INTERSECTIONS;
import static caeruleusTait.world.preview.RenderSettings.RenderMode.NOISE_CONTINENTALNESS;
import static caeruleusTait.world.preview.RenderSettings.RenderMode.NOISE_DEPTH;
import static caeruleusTait.world.preview.RenderSettings.RenderMode.NOISE_EROSION;
import static caeruleusTait.world.preview.RenderSettings.RenderMode.NOISE_HUMIDITY;
import static caeruleusTait.world.preview.RenderSettings.RenderMode.NOISE_PEAKS_AND_VALLEYS;
import static caeruleusTait.world.preview.RenderSettings.RenderMode.NOISE_TEMPERATURE;
import static caeruleusTait.world.preview.RenderSettings.RenderMode.NOISE_WEIRDNESS;
import static caeruleusTait.world.preview.WorldPreview.LOGGER;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_CAVES;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_CYCLE_NOISE;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_HOME;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_RANDOM;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_RESET_STRUCTURES;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_RESET_STRUCTURES_TOOLTIP;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_SAVE_SEED;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_SET_SPAWN;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_SET_SPAWN_PLACED;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_SET_SPAWN_TOOLTIP;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_SETTINGS;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_TOGGLE_BIOMES;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_TOGGLE_EXPAND;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_TOGGLE_HEIGHTMAP;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_TOGGLE_HEIGHTMAP_DISABLED;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_TOGGLE_INTERSECT;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_TOGGLE_INTERSECT_DISABLED;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_TOGGLE_NOISE;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_TOGGLE_NOISE_DISABLED;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_TOGGLE_STRUCTURES;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_TOGGLE_STRUCTURES_DISABLED;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.SEED_FIELD;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.SEED_LABEL;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.TITLE;

public class PreviewContainer implements AutoCloseable, PreviewDisplayDataProvider {

    public static final TagKey<Biome> C_CAVE = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("c", "caves"));
    public static final TagKey<Biome> C_IS_CAVE = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("c", "is_cave"));
    public static final TagKey<Biome> FORGE_CAVE = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("forge", "caves"));
    public static final TagKey<Biome> FORGE_IS_CAVE = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("forge", "is_cave"));
    public static final TagKey<Structure> DISPLAY_BY_DEFAULT = TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath("c", "display_on_map_by_default"));

    public static final Identifier BUTTONS_TEXTURE = Identifier.parse("world_preview:textures/gui/buttons.png");
    public static final int BUTTONS_TEX_WIDTH = 400;
    public static final int BUTTONS_TEX_HEIGHT = 60;

    public static final int LINE_HEIGHT = 20;
    public static final int LINE_VSPACE = 2;

    private final PreviewContainerDataProvider dataProvider;
    private final Screen parentScreen;
    private final Minecraft minecraft;
    private final WorldPreview worldPreview;
    private final WorldPreviewConfig cfg;
    private final WorkManager workManager;
    private final RenderSettings renderSettings;
    private final PreviewMappingData previewMappingData;
    private PreviewData previewData;
    private SeedSearchService seedSearchService;
    private TerrainExportController terrainExportController;
    private volatile TerrainMapExporter.BiomeSampler terrainExportSampler;
    /** Worldgen epoch the current terrainExportSampler/seedSearchFactory were built for. */
    private volatile long samplerContextEpoch = -1;
    private volatile SeedSearchService.SeedContextFactory seedSearchFactory;
    // Analysis session lifecycle: the container owns the session so closing the
    // WorldAnalysisScreen only detaches the view — a running analysis keeps
    // going and is re-attached when the screen is reopened for the same world.
    @Nullable private AnalysisSession activeAnalysisSession;
    private long activeAnalysisSessionEpoch = -1;
    // Analytic structure probe for the current worldgen context's seed (see
    // probeNearestStructures): built lazily on the render thread and cached per
    // worldgen epoch, so world switches never reuse a stale sampler.
    @Nullable private volatile LightweightSeedSampler structureSampler;
    private volatile long structureSamplerEpoch = -1;
    // Seed search listener indirection: a search started on one screen keeps
    // running in the background, and a reopened screen can take over the
    // progress/completion callbacks. Callbacks always fire on the main thread
    // (via minecraftExecute), so reassigning the listeners is race-free.
    private volatile java.util.function.Consumer<SeedSearchResult> seedSearchCompleteListener = r -> { };
    private volatile java.util.function.Consumer<Integer> seedSearchProgressListener = a -> { };
    @Nullable private volatile SeedSearchResult lastSeedSearchResult;
    @Nullable private volatile String lastSeedSearchCriteria;
    /** Session-level advanced search options (anchor/distances/attempts/hits); survives screen round-trips, not persisted. */
    private final SeedSearchOptions seedSearchOptions;
    private final NoiseColorProvider noiseColorProvider = new NoiseColorProvider();

    /**
     * Get WorldPreview instance
     */
    public WorldPreview worldPreview() {
        return worldPreview;
    }

    private List<Identifier> levelStemKeys;
    private Registry<LevelStem> levelStemRegistry;

    private EditBox seedEdit;
    private Button randomSeedButton;
    private Button saveSeed;
    private TranslucentButton openAnalysis;
    private TranslucentButton seedSearchButton;
    private Button settings;
    private Button resetToZeroZero;
    private ToggleButton toggleCaves;
    private ToggleButton toggleShowStructures;
    private ToggleButton toggleBiomes;
    private ToggleButton toggleNoise;
    private ToggleButton toggleHeightmap;
    private ToggleButton toggleIntersections;
    private ToggleButton toggleExpand;
    private CycleButton<RenderSettings.RenderMode> noiseCycleButton;
    private Button resetDefaultStructureVisibility;
    private Button switchBiomes;
    private Button switchStructures;
    private Button toggleSetSpawn;
    private boolean spawnPinActive = false;
    private Button toggleWaypoints;
    private Button toggleMeasure;
    private caeruleusTait.world.preview.client.gui.widgets.WaypointOverlayRenderer waypointOverlay;

    // === Slide-out rail system ===
    // When true, the sidebar collapses to a narrow 28px icon rail and the map
    // expands to fill the freed space.  Clicking a rail icon slides out a
    // floating semi-transparent panel that overlays the map.
    private boolean sidebarCollapsed = true;  // collapsed by default for max map area
    // Which floating panel is currently shown over the map:
    // -1 = none, 0 = biomes, 1 = structures
    private int floatingPanel = -1;
    // Width of the floating panel when collapsed
    private static final int RAIL_WIDTH = 28;
    private static final int FLOATING_PANEL_WIDTH = 180;

    /** Grid step (px) used to lay out the 20x20 toolbar buttons with 2px gaps. */
    private static final int BUTTON_GRID_STEP = 22;

    private final PreviewDisplay previewDisplay;
    private BiomesList biomesList;
    private StructuresList structuresList;
    private PreviewContainerTabManager tabManager;
    private BiomesList.BiomeEntry[] allBiomes;
    private StructuresList.StructureEntry[] allStructures;
    private NativeImage[] allStructureIcons;
    private NativeImage playerIcon;
    private NativeImage spawnIcon;
    private ScreenRectangle lastScreenRectangle;

    private boolean inhibitUpdates = true;
    private boolean isUpdating = false;
    private boolean setupFailed = false;
    private volatile boolean cacheLoading = false;
    private volatile boolean closed = false;
    private final ScheduledExecutorService reloadExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Executor serverThreadPoolExecutor;
    private final AtomicInteger reloadRevision = new AtomicInteger(0);

    // === A5: settings-round-trip suspend/resume ===
    // Snapshot of the structural (worldgen-shaping) settings taken when the
    // settings screen suspends sampling.  On close, the snapshot is compared
    // against the live settings: unchanged means the retained preview storage
    // and worldgen context can be resumed as-is (cheap), changed means a full
    // rebuild is required.  structuralPpcSnapshot == -1 means "no snapshot".
    private boolean structuralFullVertSnapshot;
    private int structuralPpcSnapshot = -1;
    private RenderSettings.SamplerType structuralSamplerSnapshot;
    private Identifier structuralDimensionSnapshot;

    // Seed edits trigger a full world reload; debounce them so typing (or a
    // randomize+edit burst) starts exactly one reload after the input pauses.
    private static final long SEED_EDIT_DEBOUNCE_MS = 300;
    private ScheduledFuture<?> pendingSeedEditCommit;
    /** Newest seed awaiting the debounced commit; consumed by commitSeedEdit. */
    private String pendingSeedEditValue;

    // The sidebar biome list is re-sorted and rebuilt on every visible-count
    // change; while sampling runs this used to fire per heavy render (dozens
    // of times per second). Coalesce it to at most ~4 Hz with a trailing
    // flush so the final counts are never dropped.
    private static final long BIOMES_LIST_MIN_INTERVAL_NANOS = 250_000_000L;
    private long lastBiomesListUpdateNanos = 0;
    private volatile Short2LongMap pendingVisibleBiomes;
    private ScheduledFuture<?> pendingBiomesListFlush;

    private final List<AbstractWidget> toRender = new ArrayList<>();

    public PreviewContainer(Screen screen, PreviewContainerDataProvider previewContainerDataProvider) {
        final Font font = ((ScreenAccessor) screen).getFont();
        dataProvider = previewContainerDataProvider;
        parentScreen = screen;
        minecraft = Minecraft.getInstance();
        allBiomes = new BiomesList.BiomeEntry[0];
        worldPreview = WorldPreview.get();
        cfg = worldPreview.cfg();
        seedSearchOptions = SeedSearchOptions.fromConfig(cfg);
        workManager = worldPreview.workManager();
        previewMappingData = worldPreview.biomeColorMap();
        renderSettings = worldPreview.renderSettings();
        serverThreadPoolExecutor = worldPreview.serverThreadPoolExecutor();

        createServices();
        createSeedBar(font);
        // CRITICAL: previewDisplay must be the FIRST widget in toRender.
        // In MC 1.21.11+/26.x, ContainerEventHandler dispatches mouse events in
        // FORWARD order (first-to-last).  By placing previewDisplay first, every
        // other widget (buttons, floating panels, edit boxes) gets event priority
        // over the map.  This prevents clicks on buttons and panels that overlap
        // the map from being intercepted by the map widget.
        //
        // Rendering also follows this order: previewDisplay is rendered first
        // (behind), and all other widgets render on top.
        previewDisplay = new PreviewDisplay(minecraft, this, TITLE);
        toRender.add(previewDisplay);
        // Waypoint overlay: draws markers for the current seed+dimension.
        waypointOverlay = new caeruleusTait.world.preview.client.gui.widgets.WaypointOverlayRenderer(
                previewDisplay,
                worldPreview.waypointStore(),
                () -> {
                    var ctx = workManager.worldgenContext();
                    return ctx != null ? ctx.seed() : null;
                },
                () -> {
                    var ctx = workManager.worldgenContext();
                    return ctx != null ? ctx.dimension() : null;
                });
        previewDisplay.setWaypointRenderer(waypointOverlay);
        // Seed bar widgets join right after the map so they keep event priority.
        toRender.add(seedEdit);
        toRender.add(randomSeedButton);
        toRender.add(saveSeed);

        createTopActionButtons(screen);
        createRailButtons(font);
        createListsAndTabs();
        createViewToggles();
        createSpawnControls();
        createMapToolButtons();

        wireCallbacks();

        tabManager.resetTabs();
        selectViewMode(BIOMES);

        // Initialize settings to trigger data generation
        // Note: inhibitUpdates is initially true, so we need to set it to false before calling updateSettings
        // Register the listener AFTER setting inhibitUpdates to false to avoid race conditions
        inhibitUpdates = false;
        dataProvider.registerSettingsChangeListener(this::updateSettings);
        updateSettings();
    }

    private void createServices() {
        this.seedSearchService = new SeedSearchService(minecraft, workManager.threadCount());
        this.terrainExportController = new TerrainExportController(workManager.threadCount());
    }

    /** Creates the seed edit box plus the randomize/save buttons (not yet added to {@code toRender}). */
    private void createSeedBar(Font font) {
        seedEdit = new EditBox(font, 0, 0, 100, LINE_HEIGHT - 2, SEED_FIELD);
        seedEdit.setHint(SEED_FIELD);
        seedEdit.setValue(dataProvider.seed());
        seedEdit.setResponder(this::setSeed);
        seedEdit.setTooltip(Tooltip.create(SEED_LABEL));
        seedEdit.active = dataProvider.seedIsEditable();

        randomSeedButton = iconButton(0, 20, this::randomizeSeed);
        randomSeedButton.setTooltip(Tooltip.create(BTN_RANDOM));
        randomSeedButton.active = dataProvider.seedIsEditable();

        saveSeed = iconButton(20, 20, this::saveCurrentSeed);
        saveSeed.setTooltip(Tooltip.create(BTN_SAVE_SEED));
        saveSeed.active = false;
    }

    /** Creates the top action buttons (analysis, settings, home, structure reset). */
    private void createTopActionButtons(Screen screen) {
        // Analysis and seed search buttons: built like the sidebar rail
        // buttons (same translucent style) so they match Biomes/Structures.
        openAnalysis = new TranslucentButton(
                ((ScreenAccessor) screen).getFont(), 0, 0, RAIL_WIDTH - 2, LINE_HEIGHT - 2,
                WorldPreviewComponents.ANALYSIS_OPEN, ignored -> openAnalysisScreen());
        openAnalysis.setTooltip(Tooltip.create(WorldPreviewComponents.ANALYSIS_OPEN_TOOLTIP));
        openAnalysis.active = false;
        openAnalysis.visible = cfg.showAnalysisButton;
        toRender.add(openAnalysis);

        seedSearchButton = new TranslucentButton(
                ((ScreenAccessor) screen).getFont(), 0, 0, RAIL_WIDTH - 2, LINE_HEIGHT - 2,
                WorldPreviewComponents.SEARCH_OPEN, x -> openSeedSearchScreen(null, null, false));
        seedSearchButton.setTooltip(Tooltip.create(WorldPreviewComponents.SEARCH_OPEN_TOOLTIP));
        seedSearchButton.visible = cfg.showSeedSearchButton;
        toRender.add(seedSearchButton);

        settings = iconButton(60, 20, x -> {
            suspendForSettings();
            minecraft.gui.setScreen(new caeruleusTait.world.preview.client.gui.screens.settings.SettingsScreen(screen, this));
        });
        settings.setTooltip(Tooltip.create(BTN_SETTINGS));
        settings.active = false; // Do not allow clicking away until we loaded levelStemKeys
        toRender.add(settings);

        resetToZeroZero = iconButton(120, 20, x -> renderSettings.resetCenter());
        resetToZeroZero.setTooltip(Tooltip.create(BTN_HOME));
        toRender.add(resetToZeroZero);

        resetDefaultStructureVisibility = Button
                .builder(BTN_RESET_STRUCTURES, x -> Arrays.stream(allStructures).forEach(StructuresList.StructureEntry::reset))
                .build();
        resetDefaultStructureVisibility.setTooltip(Tooltip.create(BTN_RESET_STRUCTURES_TOOLTIP));
        resetDefaultStructureVisibility.visible = false;
        toRender.add(resetDefaultStructureVisibility);
    }

    /** Creates the two sidebar rail buttons (biomes / structures). The seeds
     *  surface lives in {@link SeedSearchScreen}, which also manages saved seeds. */
    private void createRailButtons(Font font) {
        switchBiomes = railButton(font, PreviewContainerTabManager.DisplayType.BIOMES, 0,
                x -> tabManager.onTabButtonChange(x, PreviewContainerTabManager.DisplayType.BIOMES));
        switchStructures = railButton(font, PreviewContainerTabManager.DisplayType.STRUCTURES, 1,
                x -> tabManager.onTabButtonChange(x, PreviewContainerTabManager.DisplayType.STRUCTURES));

        toRender.add(switchBiomes);
        toRender.add(switchStructures);
    }

    /**
     * Rail button behavior shared by all three sidebar tabs: while the sidebar
     * is collapsed the button toggles the floating panel; otherwise it switches tabs.
     */
    private TranslucentButton railButton(Font font, PreviewContainerTabManager.DisplayType type,
                                         int panelIndex, java.util.function.Consumer<Button> tabSwitch) {
        return new TranslucentButton(
                font, 0, 0, RAIL_WIDTH - 2, LINE_HEIGHT - 2,
                type.component(),
                x -> {
                    if (sidebarCollapsed) {
                        floatingPanel = (floatingPanel == panelIndex) ? -1 : panelIndex;
                        doLayout(lastScreenRectangle);
                    } else {
                        tabSwitch.accept(x);
                    }
                });
    }

    /** Creates the three sidebar lists and the tab manager that drives them. */
    private void createListsAndTabs() {
        biomesList = new BiomesList(this, minecraft, 200, 300, 4, 100, true);
        biomesList.setRightClickListener(this::onBiomeRightClick);

        structuresList = new StructuresList(minecraft, 200, 300, 4, 100);
        structuresList.setRightClickListener(entry -> openSeedSearchScreen(null, entry, true));

        tabManager = new PreviewContainerTabManager(
                cfg,
                biomesList,
                structuresList,
                switchBiomes,
                switchStructures,
                resetDefaultStructureVisibility
        );

        // Lists are added after previewDisplay (which is now at index 0 in
        // toRender).  In forward-order event dispatch, this gives the lists
        // priority over the map.  They also render on top of the map.
        toRender.add(biomesList);
        toRender.add(structuresList);
    }

    /** Creates the view-mode toggles (caves, structures, biomes/noise/heightmap/intersections, expand). */
    private void createViewToggles() {
        toggleCaves = new ToggleButton(
                0, 0, 20, 20, /* x, y, width, height */
                80, 20, 20, 20, /* xTexStart, yTexStart, xDiffTex, yDiffTex */
                BUTTONS_TEXTURE, BUTTONS_TEX_WIDTH, BUTTONS_TEX_HEIGHT, /* Identifier, textureWidth, textureHeight*/
                x -> {
                    biomesList.setSelected(null);
                    previewDisplay.setSelectedBiomeId((short) -1);
                    previewDisplay.setHighlightCaves(((ToggleButton) x).selected);
                }
        );
        toggleCaves.setTooltip(Tooltip.create(BTN_CAVES));
        toRender.add(toggleCaves);

        toggleShowStructures = new ToggleButton(
                0, 0, 20, 20,
                140, 20, 20, 20,
                BUTTONS_TEXTURE, BUTTONS_TEX_WIDTH, BUTTONS_TEX_HEIGHT,
                x -> renderSettings.hideAllStructures = !((ToggleButton) x).selected
        );
        toggleShowStructures.selected = !renderSettings.hideAllStructures;
        toggleShowStructures.active = false; // Deactivate first in case sampleStructures is off
        toRender.add(toggleShowStructures);

        toggleBiomes = new ToggleButton(
                0, 0, 20, 20,
                360, 20, 20, 20,
                BUTTONS_TEXTURE, BUTTONS_TEX_WIDTH, BUTTONS_TEX_HEIGHT,
                x -> selectViewMode(BIOMES)
        );
        toggleBiomes.visible = false;
        toggleBiomes.active = true;
        toggleBiomes.setTooltip(Tooltip.create(BTN_TOGGLE_BIOMES));
        toRender.add(toggleBiomes);

        toggleNoise = new ToggleButton(
                0, 0, 20, 20,
                280, 20, 20, 20,
                BUTTONS_TEXTURE, BUTTONS_TEX_WIDTH, BUTTONS_TEX_HEIGHT,
                x -> selectViewMode(renderSettings.lastNoise)
        );
        toggleNoise.visible = false;
        toggleNoise.active = false;
        toggleNoise.setTooltip(Tooltip.create(BTN_TOGGLE_NOISE));
        toRender.add(toggleNoise);

        toggleHeightmap = new ToggleButton(
                0, 0, 20, 20,
                200, 20, 20, 20,
                BUTTONS_TEXTURE, BUTTONS_TEX_WIDTH, BUTTONS_TEX_HEIGHT,
                x -> selectViewMode(HEIGHTMAP)
        );
        toggleHeightmap.visible = false;
        toggleHeightmap.active = false;
        toRender.add(toggleHeightmap);

        toggleIntersections = new ToggleButton(
                0, 0, 20, 20,
                240, 20, 20, 20,
                BUTTONS_TEXTURE, BUTTONS_TEX_WIDTH, BUTTONS_TEX_HEIGHT,
                x -> selectViewMode(INTERSECTIONS)
        );
        toggleIntersections.active = false;
        toggleIntersections.visible = false;
        toRender.add(toggleIntersections);

        noiseCycleButton = CycleButton
                .builder(RenderSettings.RenderMode::toComponent, renderSettings.lastNoise)
                .withValues(List.of(NOISE_TEMPERATURE, NOISE_HUMIDITY, NOISE_DEPTH, NOISE_CONTINENTALNESS, NOISE_WEIRDNESS, NOISE_EROSION, NOISE_PEAKS_AND_VALLEYS))
                .create(0, 0, 200, 20, BTN_CYCLE_NOISE, (btn, val) -> selectViewMode(val));
        noiseCycleButton.active = false;
        noiseCycleButton.visible = false;
        toRender.add(noiseCycleButton);

        toggleExpand = new ToggleButton(
                0, 0, 20, 20,
                320, 20, 20, 20,
                BUTTONS_TEXTURE, BUTTONS_TEX_WIDTH, BUTTONS_TEX_HEIGHT,
                x -> {
                    // Show/hide view toggle buttons.
                    // In collapsed mode, they appear in the rail below this button.
                    // In expanded mode, they appear at the top of the preview area.
                    doLayout(lastScreenRectangle);
                }
        );
        toggleExpand.setTooltip(Tooltip.create(BTN_TOGGLE_EXPAND));
        toRender.add(toggleExpand);
    }

    /** Creates the spawn-pin toggle button and wires its config round-trip. */
    private void createSpawnControls() {
        // Spawn pin text button -- shows spawn text, toggles spawn pin mode.
        toggleSetSpawn = Button.builder(BTN_SET_SPAWN, btn -> {
            spawnPinActive = !spawnPinActive;
            previewDisplay.setSpawnPinMode(spawnPinActive);
            if (spawnPinActive) {
                if (cfg.spawnOverrideEnabled) {
                    previewDisplay.setSpawnPinPos(new BlockPos(cfg.spawnOverrideX, 0, cfg.spawnOverrideZ));
                }
            } else {
                previewDisplay.setSpawnPinPos(null);
            }
        }).size(20, 20).build();
        toggleSetSpawn.setTooltip(Tooltip.create(BTN_SET_SPAWN_TOOLTIP));
        toRender.add(toggleSetSpawn);

        // Wire up the spawn pin callback to update config
        previewDisplay.setSpawnPinCallback(pos -> {
            if (pos != null) {
                cfg.spawnOverrideEnabled = true;
                cfg.spawnOverrideX = pos.getX();
                cfg.spawnOverrideZ = pos.getZ();
                toggleSetSpawn.setMessage(BTN_SET_SPAWN_PLACED);
            } else {
                cfg.spawnOverrideEnabled = false;
                toggleSetSpawn.setMessage(BTN_SET_SPAWN);
            }
        });
    }

    /** Creates the waypoint and measure-tool buttons plus their map callbacks. */
    private void createMapToolButtons() {
        toggleWaypoints = Button.builder(WorldPreviewComponents.BTN_WAYPOINTS, btn -> {
            boolean enable = !previewDisplay.isWaypointMode();
            previewDisplay.setWaypointMode(enable);
            if (enable) {
                previewDisplay.setMeasureMode(false);
            }
        }).size(44, LINE_HEIGHT).build();
        toggleWaypoints.setTooltip(Tooltip.create(WorldPreviewComponents.BTN_WAYPOINTS_TOOLTIP));
        toRender.add(toggleWaypoints);

        toggleMeasure = Button.builder(WorldPreviewComponents.BTN_MEASURE, btn -> {
            boolean enable = !previewDisplay.isMeasureMode();
            previewDisplay.setMeasureMode(enable);
            if (enable) {
                previewDisplay.setWaypointMode(false);
            }
        }).size(44, LINE_HEIGHT).build();
        toggleMeasure.setTooltip(Tooltip.create(WorldPreviewComponents.BTN_MEASURE_TOOLTIP));
        toRender.add(toggleMeasure);

        previewDisplay.setWaypointPlaceCallback(pos -> {
            if (workManager.worldgenContext() == null) {
                return;
            }
            minecraft.gui.setScreen(new WaypointNameScreen(parentScreen, this, pos));
        });
        previewDisplay.setWaypointEditCallback(waypoint ->
            // Editing an existing waypoint: the callback hands us the exact
            // stored object, so the dialog can pre-fill values and offer delete.
            minecraft.gui.setScreen(new WaypointNameScreen(parentScreen, this, waypoint)));

        // Double-click a structure entry to center the map on its nearest
        // rendered instance.
        structuresList.setDoubleClickListener(entry -> {
            if (!previewDisplay.locateStructure(entry.id())) {
                previewDisplay.showHud(Component.translatable("world_preview.preview.locate_not_visible"));
            }
        });
    }

    /** Stores a newly placed waypoint for the current seed+dimension. */
    public void addWaypoint(String name, BlockPos pos, int color) {
        var ctx = workManager.worldgenContext();
        if (ctx == null) {
            return;
        }
        worldPreview.waypointStore().add(caeruleusTait.world.preview.domain.waypoint.Waypoint.create(
                name, pos.getX(), pos.getY(), pos.getZ(), ctx.dimension(), color, ctx.seed()));
    }

    /** Updates an existing waypoint's label and color, keeping its position and identity scope. */
    public void updateWaypoint(caeruleusTait.world.preview.domain.waypoint.Waypoint existing, String name, int color) {
        worldPreview.waypointStore().remove(existing.id());
        worldPreview.waypointStore().add(caeruleusTait.world.preview.domain.waypoint.Waypoint.create(
                name, existing.x(), existing.y(), existing.z(), existing.dimension(), color, existing.seed()));
    }

    /** Removes an existing waypoint by its stable id. */
    public void deleteWaypoint(caeruleusTait.world.preview.domain.waypoint.Waypoint waypoint) {
        worldPreview.waypointStore().remove(waypoint.id());
    }

    /** Wires cross-widget callbacks that depend on several groups being built. */
    private void wireCallbacks() {        biomesList.setBiomeChangeListener(x -> {
            previewDisplay.setSelectedBiomeId(x == null ? -1 : x.id());
            toggleCaves.selected = x == null && toggleCaves.selected;
            previewDisplay.setHighlightCaves(x == null && toggleCaves.selected);
        });

        // Wire up the occluding-widgets supplier so PreviewDisplay yields mouse
        // priority to buttons and panels that overlap the map area.
        previewDisplay.setOccludingWidgetsSupplier(() -> toRender);
    }

    /** Factory for the standard 20x20 icon button used across the toolbar. */
    private static OldStyleImageButton iconButton(int texX, int texY, Button.OnPress onPress) {
        return new OldStyleImageButton(
                0, 0, 20, 20, /* x, y, width, height */
                texX, texY, 20, /* xTexStart, yTexStart, yDiffTex */
                BUTTONS_TEXTURE, BUTTONS_TEX_WIDTH, BUTTONS_TEX_HEIGHT, /* Identifier, textureWidth, textureHeight*/
                onPress
        );
    }


    public void patchColorData() {
        Map<Identifier, PreviewMappingData.ColorEntry> configured = Arrays.stream(allBiomes)
                .filter(x -> x.dataSource() == PreviewData.DataSource.CONFIG)
                .collect(
                        Collectors.toMap(
                                x -> x.entry().key().identifier(),
                                x -> new PreviewMappingData.ColorEntry(PreviewData.DataSource.MISSING, x.color(), x.isCave(), x.name())
                        )
                );

        Map<Identifier, PreviewMappingData.ColorEntry> defaults = Arrays.stream(allBiomes)
                .filter(x -> x.dataSource() == PreviewData.DataSource.RESOURCE)
                .collect(
                        Collectors.toMap(
                                x -> x.entry().key().identifier(),
                                x -> new PreviewMappingData.ColorEntry(PreviewData.DataSource.RESOURCE, x.color(), x.isCave(), x.name())
                        )
                );

        Map<Identifier, PreviewMappingData.ColorEntry> missing = Arrays.stream(allBiomes)
                .filter(x -> x.dataSource() == PreviewData.DataSource.MISSING)
                .collect(
                        Collectors.toMap(
                                x -> x.entry().key().identifier(),
                                x -> new PreviewMappingData.ColorEntry(PreviewData.DataSource.CONFIG, x.color(), x.isCave(), x.name())
                        )
                );

        previewMappingData.update(missing);
        previewMappingData.update(defaults);
        previewMappingData.update(configured);
        updateSettings();
    }

    private void selectViewMode(RenderSettings.RenderMode mode) {
        toggleBiomes.selected = false;
        toggleHeightmap.selected = false;
        toggleIntersections.selected = false;
        toggleNoise.selected = false;
        noiseCycleButton.active = false;

        synchronized (renderSettings) {
            switch (mode) {
                case BIOMES -> toggleBiomes.selected = true;
                case HEIGHTMAP -> toggleHeightmap.selected = true;
                case INTERSECTIONS -> toggleIntersections.selected = true;
                case NOISE_TEMPERATURE, NOISE_HUMIDITY, NOISE_CONTINENTALNESS, NOISE_EROSION, NOISE_DEPTH,
                     NOISE_WEIRDNESS, NOISE_PEAKS_AND_VALLEYS -> {
                    renderSettings.lastNoise = mode;
                    toggleNoise.selected = true;
                    noiseCycleButton.active = true;
                }
            }
            renderSettings.mode = mode;
        }
        // Invalidate the render cache so the preview re-renders with the new mode.
        // Without this, the render-skip optimization sees an unchanged center and
        // write counter, so it reuses stale cached data from the previous mode.
        previewDisplay.invalidateRenderCache();
        // The new mode reads a different storage flag (e.g. FLAG_INTERSECT for the
        // y-intersections view), and that layer is only written while a range pass
        // runs with its feature toggle enabled.  Clear the WorkManager dedup guard
        // so the next frame re-issues the viewport even though its range is
        // unchanged; queueForLevel skips completed chunks, so only the missing
        // layer gets sampled.  Without this, a view whose layer was never sampled
        // here renders permanently black: the empty sections render as 0xFF000000
        // and the frozen write counter keeps re-drawing that black frame.
        workManager.resetQueuedRange();
    }

    private synchronized void updateSettings() {
        if (inhibitUpdates) {
            return;
        }
        inhibitUpdates = true;
        try {
            final int revision;
            synchronized (reloadRevision) {
                revision = reloadRevision.incrementAndGet();
            }
            isUpdating = true;
            CompletableFuture
                    .supplyAsync(() -> {
                        // Check if we are the latest update
                        if (reloadRevision.get() > revision) {
                            return null;
                        }
                        return dataProvider.previewWorldCreationContext();
                    }, reloadExecutor)
                    .thenAcceptAsync(x -> {
                        // Check if we are the latest update
                        if (reloadRevision.get() > revision) {
                            return;
                        }
                        updateSettings_real(x);
                        synchronized (reloadRevision) {
                            if (reloadRevision.get() <= revision) {
                                isUpdating = false;
                            }
                        }
                    }, minecraft)
                    .handle((r, e) -> {
                        if (e == null) {
                            setupFailed = false;
                        } else {
                            e.printStackTrace();
                            setupFailed = true;
                        }
                        // Always reset isUpdating when this update completes
                        // (success or failure), provided no newer update has
                        // superseded us.  Without this, a failed
                        // updateSettings_real() would leave isUpdating stuck
                        // at true forever, locking the preview into the
                        // "loading" state even though setupFailed is true.
                        synchronized (reloadRevision) {
                            if (reloadRevision.get() <= revision) {
                                isUpdating = false;
                            }
                        }
                        return null;
                    });
        } finally {
            inhibitUpdates = false;
        }
    }

    private void updateSettings_real(@Nullable WorldCreationContext wcContext) {
        LOGGER.info("[WP-Update] updateSettings_real start: wcContext={}", wcContext != null ? "non-null" : "null");
        updateSeedAndConfigUI();

        WorldDataConfiguration worldDataConfiguration = dataProvider.worldDataConfiguration(wcContext);
        Registry<Biome> biomeRegistry = dataProvider.registryAccess(wcContext).lookupOrThrow(Registries.BIOME);
        Registry<Structure> strucutreRegistry = dataProvider.registryAccess(wcContext).lookupOrThrow(Registries.STRUCTURE);
        levelStemRegistry = dataProvider.levelStemRegistry(wcContext);
        levelStemKeys = levelStemRegistry.keySet().stream().sorted(Comparator.comparing(Object::toString)).toList();

        // Now that the level stem keys are loaded, allow the user to go into properties!
        settings.active = true;
        openAnalysis.active = true;
        if (renderSettings.dimension == null || !levelStemRegistry.containsKey(renderSettings.dimension)) {
            if (levelStemRegistry.containsKey(LevelStem.OVERWORLD)) {
                renderSettings.dimension = LevelStem.OVERWORLD.identifier();
            } else {
                renderSettings.dimension = levelStemRegistry.keySet().iterator().next();
            }
        }
        LevelStem levelStem = levelStemRegistry.getValue(renderSettings.dimension);

        Set<Identifier> caveBiomes = collectCaveBiomes(biomeRegistry);
        Set<Identifier> allKnownBiomes = collectKnownBiomes(biomeRegistry, levelStem);

        previewData = previewMappingData.generateMapData(
                allKnownBiomes,
                caveBiomes,
                strucutreRegistry.keySet(),
                StreamSupport.stream(strucutreRegistry.getTagOrEmpty(DISPLAY_BY_DEFAULT).spliterator(), false)
                        .map(x -> x.unwrapKey().orElseThrow().identifier())
                        .collect(Collectors.toSet())
        );

        // Check whether we have a valid colormap stored
        ColorMap colorMap = previewData.colorMaps().get(cfg.colorMap);
        if (colorMap == null) {
            cfg.colorMap = "world_preview:inferno";
        }

        LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess = dataProvider.layeredRegistryAccess(wcContext);
        setupWorkManager(levelStem, layeredRegistryAccess, worldDataConfiguration, wcContext);
        setupSearchContext();
        queueEarlyPreviewRange();
        // Run biome entry building and icon loading in parallel to reduce
        // total initialization time.  buildBiomeEntries() must run on the
        // main thread (it updates UI lists), but loadAllIcons() is I/O-bound
        // and safe to run on a virtual thread.  We then wait for icons to
        // finish before buildStructureEntries() which needs them.
        java.util.concurrent.Future<?> iconFuture;
        try (var vthreadExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            iconFuture = vthreadExecutor.submit(this::loadAllIcons);
            // buildBiomeEntries runs on the current (main) thread while icons
            // load in parallel on a virtual thread.
            buildBiomeEntries(biomeRegistry);
        }
        // Wait for icon loading to complete
        try {
            iconFuture.get();
        } catch (Exception e) {
            LOGGER.warn("Parallel icon loading failed, falling back", e);
            // Ensure icons are at least initialized even if parallel load failed
            if (allStructureIcons == null) {
                loadAllIcons();
            }
        }
        buildStructureEntries(strucutreRegistry, layeredRegistryAccess);
        applySamplingFeatureToggles();

        previewDisplay.reloadData();
        previewDisplay.invalidateRenderCache();
        previewDisplay.setSelectedBiomeId((short) -1);
        previewDisplay.setHighlightCaves(false);
        previewDisplay.resetGenerationTimer();
        toggleCaves.selected = false;
        LOGGER.info("[WP-Update] updateSettings_real complete: previewStorage={}", workManager.previewStorage() != null);
    }

    private void updateSeedAndConfigUI() {
        saveSeed.active = !dataProvider.seed().isEmpty() && !cfg.savedSeeds.contains(dataProvider.seed());
        seedEdit.setValue(dataProvider.seed());
        if (!seedEdit.isFocused()) {
            seedEdit.moveCursorToStart(false);
        }

        // Range validation
        if (cfg.heightmapMinY == cfg.heightmapMaxY) {
            cfg.heightmapMaxY++;
        } else if (cfg.heightmapMaxY < cfg.heightmapMinY) {
            int tmp = cfg.heightmapMaxY;
            cfg.heightmapMaxY = cfg.heightmapMinY;
            cfg.heightmapMinY = tmp;
        }
    }

    private Set<Identifier> collectCaveBiomes(Registry<Biome> biomeRegistry) {
        Set<Identifier> caveBiomes = new HashSet<>();
        for (TagKey<Biome> tagKey : List.of(C_CAVE, C_IS_CAVE, FORGE_CAVE, FORGE_IS_CAVE)) {
            caveBiomes.addAll(
                    StreamSupport.stream(biomeRegistry.getTagOrEmpty(tagKey).spliterator(), false)
                            .map(x -> x.unwrapKey().orElseThrow().identifier())
                            .toList()
            );
        }
        return caveBiomes;
    }

    private Set<Identifier> collectKnownBiomes(Registry<Biome> biomeRegistry, @Nullable LevelStem levelStem) {
        // Merge biomes from both the biome registry AND the biome source's
        // possibleBiomes().  Some mods use custom biome sources or register
        // biomes in a different registry context, so biomeRegistry.keySet()
        // alone may not include every biome the source can actually generate.
        Set<Identifier> allKnownBiomes = new HashSet<>(biomeRegistry.keySet());
        if (levelStem != null) {
            levelStem.generator().getBiomeSource().possibleBiomes().forEach(holder -> {
                holder.unwrapKey().ifPresent(key -> allKnownBiomes.add(key.identifier()));
            });
        }
        return allKnownBiomes;
    }

    private void setupWorkManager(
            LevelStem levelStem,
            LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess,
            WorldDataConfiguration worldDataConfiguration,
            @Nullable WorldCreationContext wcContext
    ) {
        // World-scoped background tasks and their captured context snapshots
        // are about to be invalidated: cancel exports/searches and detach the
        // analysis session BEFORE the old worldgen context is torn down, so
        // stale work can neither run on nor write into the new world.
        invalidateWorldScopedTasks();
        workManager.cancel();
        Runnable changeWorldGenState = () -> {
            workManager.changeWorldGenState(
                    levelStem,
                    layeredRegistryAccess,
                    previewData,
                    dataProvider.worldOptions(wcContext),
                    worldDataConfiguration,
                    dataProvider,
                    minecraft.getProxy(),
                    dataProvider.tempDataPackDir(),
                    dataProvider.minecraftServer()
            );
        };

        // Some forge mods require running the server setup in a specific thread pool to switch
        // to the server specific logic (`EffectiveSide.get().isClient()`)
        if (serverThreadPoolExecutor != null) {
            try {
                CompletableFuture.runAsync(changeWorldGenState, serverThreadPoolExecutor).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        } else {
            changeWorldGenState.run();
        }

        // Do NOT run this in the lambda because this call might change screens
        workManager.postChangeWorldGenState();
    }

    /**
     * Cancels every background task bound to the outgoing worldgen context and
     * drops its captured snapshots (sampler factories). Called on every world
     * identity change, before the old context is cancelled.
     */
    private void invalidateWorldScopedTasks() {
        // Drop the stale factories first so no new task can capture them.
        terrainExportSampler = null;
        seedSearchFactory = null;
        samplerContextEpoch = -1;
        closeStructureSampler();
        terrainExportController.cancel();
        seedSearchService.cancel();
        closeActiveAnalysisSession();
    }

    /** Drops the cached analytic structure probe so the next probe rebuilds it for the new epoch. */
    private void closeStructureSampler() {
        LightweightSeedSampler sampler = structureSampler;
        structureSampler = null;
        structureSamplerEpoch = -1;
        if (sampler != null) {
            try {
                // close() is a no-op today (see LightweightSeedSampler), but
                // staying AutoCloseable-clean keeps that free to change.
                sampler.close();
            } catch (Throwable ignored) {
                // Never block world switches on probe cleanup.
            }
        }
    }

    /** Closes and forgets the currently owned analysis session, if any. */
    private void closeActiveAnalysisSession() {
        AnalysisSession session = activeAnalysisSession;
        activeAnalysisSession = null;
        activeAnalysisSessionEpoch = -1;
        if (session != null) {
            try {
                session.close();
            } catch (Throwable ignored) {
                // Never block world switches on session cleanup.
            }
        }
    }

    /**
     * Closes the current analysis session and opens a fresh one for the given
     * request, stamping the worldgen epoch of the new session so later screen
     * opens can re-attach to it.
     *
     * @return the new session, or {@code null} when the work manager is not
     *         set up or the session could not be opened
     */
    public @Nullable AnalysisSession restartAnalysisSession(AnalysisRequest request) {
        if (!workManager.isSetup()) return null;
        try {
            closeActiveAnalysisSession();
            AnalysisSession session = workManager.openAnalysisSession(request);
            activeAnalysisSession = session;
            activeAnalysisSessionEpoch = workManager.epoch();
            return session;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to restart analysis session for region {}", request.region(), e);
            closeActiveAnalysisSession();
            return null;
        }
    }

    /**
     * Sea level of the current worldgen context, for reference lines in the
     * analysis screen. {@code null} when there is no live worldgen context or
     * the sea level cannot be derived (e.g. non-noise generators).
     */
    public @Nullable Integer analysisSeaLevel() {
        WorldgenContext context = workManager.worldgenContext();
        return context != null ? AnalysisSession.deriveSeaLevel(context) : null;
    }

    private void setupSearchContext() {
        if (workManager.worldgenContext() == null) return;
        var worldgenContext = workManager.worldgenContext();

        // Extract shared components to avoid full server infra per seed
        final var biomeSource = worldgenContext.biomeSource();
        final var chunkGenerator = worldgenContext.chunkGenerator();
        // compositeAccess() returns RegistryAccess.Frozen, supports lookupOrThrow
        final var compositeRegistryAccess = worldgenContext.registryAccess().compositeAccess();

        // Structure probing (vanilla /locate core) is optional infrastructure:
        // when the template manager cannot be built, structure criteria fail cleanly.
        final var probeRegistries = new caeruleusTait.world.preview.backend.analysis.LightweightSeedSampler.RegistryAccessBundle(
                compositeRegistryAccess,
                compositeRegistryAccess.lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE),
                compositeRegistryAccess.lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE_SET));
        net.minecraft.world.level.LevelHeightAccessor probeHeight;
        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager probeTemplates;
        try {
            // Reuse the WorkManager-owned SampleUtils: it already carries the
            // template manager and height accessor for this worldgen context.
            var sampleUtils = worldgenContext.createSampleUtils();
            probeHeight = sampleUtils.levelHeightAccessor();
            probeTemplates = sampleUtils.structureTemplateManager();
        } catch (Exception e) {
            LOGGER.warn("Structure probing disabled: failed to obtain structure template manager", e);
            probeHeight = net.minecraft.world.level.LevelHeightAccessor.create(
                    worldgenContext.dimensionType().minY(), worldgenContext.dimensionType().height());
            probeTemplates = null;
        }
        final var probeRegistriesFinal = probeRegistries;
        final var probeHeightFinal = probeHeight;
        final var probeTemplatesFinal = probeTemplates;

        seedSearchFactory = seed -> {
            // Create lightweight RandomState per seed, no DummyMinecraftServer needed
            net.minecraft.world.level.levelgen.RandomState randomState;
            if (chunkGenerator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noiseBasedChunkGenerator) {
                randomState = net.minecraft.world.level.levelgen.RandomState.create(
                    noiseBasedChunkGenerator.generatorSettings().value(),
                    compositeRegistryAccess.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE),
                    seed
                );
            } else {
                randomState = net.minecraft.world.level.levelgen.RandomState.create(
                    net.minecraft.world.level.levelgen.NoiseGeneratorSettings.dummy(),
                    compositeRegistryAccess.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE),
                    seed
                );
            }

            return new caeruleusTait.world.preview.backend.analysis.LightweightSeedSampler(
                    biomeSource, chunkGenerator, probeRegistriesFinal, randomState, seed, probeHeightFinal, probeTemplatesFinal);
        };

        // Set up terrain export sampler
        final long currentSeed = worldgenContext.seed();
        final var terrainRandomState = (chunkGenerator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator nbg2)
            ? net.minecraft.world.level.levelgen.RandomState.create(
                nbg2.generatorSettings().value(),
                compositeRegistryAccess.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE),
                currentSeed)
            : net.minecraft.world.level.levelgen.RandomState.create(
                net.minecraft.world.level.levelgen.NoiseGeneratorSettings.dummy(),
                compositeRegistryAccess.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE),
                currentSeed);
        final var terrainSampler = terrainRandomState.sampler();
        final int terrainQuartY = net.minecraft.core.QuartPos.fromBlock(64);
        terrainExportSampler = (blockX, blockZ) ->
            biomeSource.getNoiseBiome(
                net.minecraft.core.QuartPos.fromBlock(blockX),
                terrainQuartY,
                net.minecraft.core.QuartPos.fromBlock(blockZ),
                terrainSampler);

        // Remember which worldgen epoch these snapshots belong to; background
        // tasks started later verify this before they run or publish.
        samplerContextEpoch = workManager.epoch();
    }

    /**
     * Analytic nearest-structure probe for the current preview seed: pure
     * worldgen math (the vanilla /locate placement pipeline), no storage and
     * no server infrastructure, so it is safe to call on the render thread
     * when the analysis screen opens.
     * <p>
     * The backing {@link LightweightSeedSampler} is built lazily on first use
     * (at most one build attempt per worldgen epoch) and cached until
     * {@link #invalidateWorldScopedTasks()} drops it on a world switch. Only
     * render-thread callers are expected; the fields are volatile so an
     * accidental cross-thread use stays memory-safe without locking.
     *
     * @return found structure id to its located position (ids that could not
     *         be located within the distance cap are simply omitted), or
     *         {@code null} when probing is unavailable — no live worldgen
     *         context, or the probe sampler could not be built
     */
    public @Nullable Map<Identifier, BlockPos> probeNearestStructures(Set<Identifier> types, BlockPos anchor, int maxDistanceBlocks) {
        var context = workManager.worldgenContext();
        if (context == null) return null;
        long epoch = workManager.epoch();
        if (structureSamplerEpoch != epoch) {
            structureSampler = buildStructureSampler(context);
            structureSamplerEpoch = epoch;
        }
        if (structureSampler == null) return null;
        Map<Identifier, BlockPos> out = new LinkedHashMap<>();
        for (Identifier id : types) {
            try {
                BlockPos found = structureSampler.nearestStructure(Set.of(id), anchor, maxDistanceBlocks);
                if (found != null) out.put(id, found);
            } catch (Exception e) {
                LOGGER.debug("structure probe failed for {}", id, e);
            }
        }
        return out;
    }

    /**
     * Builds the analytic structure probe sampler for the given worldgen
     * context, following the same assembly recipe as
     * {@link #setupSearchContext()} (shared biome source / chunk generator /
     * composite registry access, a per-seed {@link RandomState}, and the
     * height accessor + template manager from the context's sample utilities)
     * but fixed to the context's current seed instead of a per-seed factory.
     *
     * @return the sampler, or {@code null} when probing is unavailable — most
     *         notably when the structure template manager cannot be obtained
     */
    @Nullable
    private LightweightSeedSampler buildStructureSampler(WorldgenContext worldgenContext) {
        try {
            final var biomeSource = worldgenContext.biomeSource();
            final var chunkGenerator = worldgenContext.chunkGenerator();
            // compositeAccess() returns RegistryAccess.Frozen, supports lookupOrThrow
            final var compositeRegistryAccess = worldgenContext.registryAccess().compositeAccess();
            final var probeRegistries = new LightweightSeedSampler.RegistryAccessBundle(
                    compositeRegistryAccess,
                    compositeRegistryAccess.lookupOrThrow(Registries.STRUCTURE),
                    compositeRegistryAccess.lookupOrThrow(Registries.STRUCTURE_SET));
            final LevelHeightAccessor probeHeight;
            final StructureTemplateManager probeTemplates;
            try {
                // Reuse the WorkManager-owned SampleUtils: it already carries the
                // template manager and height accessor for this worldgen context.
                var sampleUtils = worldgenContext.createSampleUtils();
                probeHeight = sampleUtils.levelHeightAccessor();
                probeTemplates = sampleUtils.structureTemplateManager();
            } catch (Exception e) {
                LOGGER.warn("Structure probing disabled: failed to obtain structure template manager", e);
                return null;
            }
            final long seed = worldgenContext.seed();
            RandomState randomState;
            if (chunkGenerator instanceof NoiseBasedChunkGenerator noiseBasedChunkGenerator) {
                randomState = RandomState.create(
                    noiseBasedChunkGenerator.generatorSettings().value(),
                    compositeRegistryAccess.lookupOrThrow(Registries.NOISE),
                    seed
                );
            } else {
                randomState = RandomState.create(
                    NoiseGeneratorSettings.dummy(),
                    compositeRegistryAccess.lookupOrThrow(Registries.NOISE),
                    seed
                );
            }
            return new LightweightSeedSampler(
                    biomeSource, chunkGenerator, probeRegistries, randomState, seed, probeHeight, probeTemplates);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to build structure probe sampler", e);
            return null;
        }
    }

    public void onBiomeRightClick(BiomesList.BiomeEntry entry) {
        if (seedSearchService.isSearching()) {
            seedSearchService.cancel();
        }
        openSeedSearchScreen(entry, null, true);
    }

    /** Immutable snapshot of the current viewport sampling parameters. */
    public record SeedSearchViewport(
            String dimension,
            BlockPos center,
            int viewMinX,
            int viewMaxX,
            int viewMinZ,
            int viewMaxZ,
            int sampleStep,
            String contextFingerprint
    ) {}

    /** Captures the current viewport so search requests survive camera moves. */
    public SeedSearchViewport currentSearchViewport() {
        var center = previewDisplay.center();
        var blockScale = renderSettings.toScaleSpec().blockScale();
        int halfW = (int) (previewDisplay.getTexWidth() * blockScale / 2.0);
        int halfH = (int) (previewDisplay.getTexHeight() * blockScale / 2.0);
        var worldgenContext = workManager.worldgenContext();
        return new SeedSearchViewport(
                worldgenContext != null ? worldgenContext.dimension() : "",
                center,
                center.getX() - halfW, center.getX() + halfW,
                center.getZ() - halfH, center.getZ() + halfH,
                renderSettings.quartStride() * 4,
                worldgenContext != null ? worldgenContext.fingerprint() : ""
        );
    }

    /** Whether a seed search is currently running. */
    public boolean isSeedSearchRunning() {
        return seedSearchService.isSearching();
    }

    /** Cancels the running seed search, if any. */
    public void cancelSeedSearch() {
        seedSearchService.cancel();
    }

    /**
     * Starts an advanced (multi-criteria) seed search. The search runs in the
     * background; its callbacks can be re-targeted to another screen later via
     * {@link #reattachSeedSearchListener(Consumer, Consumer)}.
     *
     * @param criteriaLabel label stored alongside the latest result so a
     *                      reopened screen can display it
     * @return false when the search context is missing or a search is running
     */
    public boolean startSeedSearch(
            SeedSearchRequest request,
            String criteriaLabel,
            java.util.function.Consumer<SeedSearchResult> onComplete,
            java.util.function.Consumer<Integer> onProgress
    ) {
        if (seedSearchFactory == null) {
            LOGGER.warn("Search context not available");
            return false;
        }
        boolean started = seedSearchService.startSearch(request, seedSearchFactory,
                null, result -> seedSearchCompleteListener.accept(result), attempts -> seedSearchProgressListener.accept(attempts));
        if (started) {
            // Only after a successful start: discard the previous result and
            // route the running search's callbacks through the new listeners,
            // recording the outcome for later reopens.
            lastSeedSearchResult = null;
            lastSeedSearchCriteria = null;
            seedSearchCompleteListener = result -> {
                lastSeedSearchResult = result;
                lastSeedSearchCriteria = criteriaLabel;
                onComplete.accept(result);
            };
            seedSearchProgressListener = onProgress;
        }
        return started;
    }

    /** Makes the given listener the target of the running search's progress/completion callbacks. */
    public void reattachSeedSearchListener(
            java.util.function.Consumer<SeedSearchResult> onComplete,
            java.util.function.Consumer<Integer> onProgress
    ) {
        seedSearchCompleteListener = result -> {
            lastSeedSearchResult = result;
            onComplete.accept(result);
        };
        seedSearchProgressListener = onProgress;
    }

    /** The most recent completed seed search result, or null before the first completion. */
    @Nullable
    public SeedSearchResult lastSeedSearchResult() {
        return lastSeedSearchResult;
    }

    /** The criteria label stored alongside {@link #lastSeedSearchResult()}, or null. */
    @Nullable
    public String lastSeedSearchCriteria() {
        return lastSeedSearchCriteria;
    }

    /** The session-level advanced search options edited by the seed search screens. */
    public SeedSearchOptions seedSearchOptions() {
        return seedSearchOptions;
    }

    private void queueEarlyPreviewRange() {
        // Early queue: start sampling the center region immediately so the
        // worker threads are busy while we set up the rest of the GUI.
        renderSettings.resetCenter();
        // Invalidate the render cache and queued-range guard so the next
        // render frame sees a genuinely fresh center and re-uploads new data.
        // Without this, lastQueuedRange still holds the previous drag range,
        // causing queueGeneration() to return early and the preview stays black.
        previewDisplay.invalidateRenderCache();
        if (lastScreenRectangle == null) {
            // Display not laid out yet (tab never opened): texWidth/texHeight
            // are still the 100x100 constructor defaults, so a range computed
            // now would be the wrong size and get cancelled wholesale by the
            // real viewport's first queue pass.  The first render frame queues
            // the actual viewport instead (throttle.needsInitialQueue).
            return;
        }
        final BlockPos earlyCenter = renderSettings.center();
        final double earlyScale = renderSettings.toScaleSpec().blockScale();
        // Use getTexWidth()/getTexHeight() so the early-queue range matches
        // the range computed by queueGeneration() (which uses texWidth/texHeight
        // via currentMapping()).  Previously this used getWidth()*guiScale,
        // which differed from texWidth before setSize() was called, causing
        // a range mismatch that triggered unnecessary batch cancellation.
        final int earlyW = previewDisplay.getTexWidth();
        final int earlyH = previewDisplay.getTexHeight();
        final BlockPos earlyTopLeft = new BlockPos(
                earlyCenter.getX() - (int)(earlyW * earlyScale / 2.0) - 1,
                earlyCenter.getY(),
                earlyCenter.getZ() - (int)(earlyH * earlyScale / 2.0) - 1
        );
        final BlockPos earlyBotRight = new BlockPos(
                earlyCenter.getX() + (int)(earlyW * earlyScale / 2.0) + 1,
                earlyCenter.getY(),
                earlyCenter.getZ() + (int)(earlyH * earlyScale / 2.0) + 1
        );
        workManager.queueRange(earlyTopLeft, earlyBotRight);
    }

    private void buildBiomeEntries(Registry<Biome> biomeRegistry) {
        List<String> missing = Arrays.stream(previewData.biomeId2BiomeData())
                .filter(x -> x.dataSource() == PreviewData.DataSource.MISSING)
                .map(PreviewData.BiomeData::tag)
                .map(Identifier::toString)
                .toList();
        worldPreview.writeMissingColors(missing);

        // Build a lookup from Identifier -> Holder.Reference<Biome> for fast access.
        // Some biomes may be in the biome source but not in the biome registry
        // (e.g. when a mod uses a custom biome source or a different registry
        // context).  For those, we create a standalone Holder.Reference so they
        // still appear in the biome list and sidebar.
        Map<Identifier, Holder.Reference<Biome>> registryHolders = biomeRegistry.listElements()
                .collect(Collectors.toMap(x -> x.key().identifier(), x -> x));

        allBiomes = new BiomesList.BiomeEntry[previewData.biomeId2BiomeData().length];
        for (PreviewData.BiomeData biomeData : previewData.biomeId2BiomeData()) {
            final short id = (short) biomeData.id();
            Holder.Reference<Biome> holder = registryHolders.get(biomeData.tag());
            if (holder == null) {
                // Biome is in the biome source but not in the registry.
                // Create a standalone reference so it can still be displayed.
                holder = Holder.Reference.createStandAlone(biomeRegistry, ResourceKey.create(Registries.BIOME, biomeData.tag()));
            }
            allBiomes[id] = biomesList.createEntry(
                    holder, id,
                    biomeData.color(), biomeData.resourceOnlyColor(),
                    biomeData.isCave(), biomeData.resourceOnlyIsCave(),
                    biomeData.name(), biomeData.dataSource()
            );
        }
        Arrays.sort(allBiomes, Comparator.comparing(BiomesList.BiomeEntry::id));

        // Initialize biomes list with all biomes
        biomesList.replaceEntries(Arrays.asList(allBiomes));
        biomesList.setSelected(null);
    }

    private void loadAllIcons() {
        freeStructureIcons();
        final ResourceManager builtinResourceManager = minecraft.getResourceManager();
        final ResourceManager sampleResourceManager = workManager.sampleResourceManager();
        // Fingerprint of the current resource packs; a change (pack
        // added/removed/reordered) automatically generations the IconCache.
        final String fp = IconCache.fingerprint(builtinResourceManager, sampleResourceManager);
        allStructureIcons = new NativeImage[previewData.structId2StructData().length];

        // Collect unique icon identifiers to avoid loading the same icon twice.
        // Use a LinkedHashMap to preserve insertion order while deduplicating.
        final java.util.LinkedHashMap<Identifier, Integer> uniqueIconIds = new java.util.LinkedHashMap<>();
        for (int i = 0; i < previewData.structId2StructData().length; ++i) {
            PreviewData.StructureData data = previewData.structId2StructData()[i];
            Identifier iconId = data.icon();
            if (iconId == null) {
                iconId = Identifier.parse("world_preview:textures/structure/unknown.png");
            }
            uniqueIconIds.putIfAbsent(iconId, i);
        }

        // Load all unique icons in parallel using virtual threads (Java 21+).
        // Icon loading is I/O-bound (file reads + PNG decoding), so virtual
        // threads provide significant speedup with minimal overhead.
        // The ConcurrentHashMap ensures thread-safe results aggregation.
        final java.util.concurrent.ConcurrentHashMap<Identifier, NativeImage> loadedIcons =
                new java.util.concurrent.ConcurrentHashMap<>();
        final int iconCount = uniqueIconIds.size();
        if (iconCount > 0) {
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                final java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>(iconCount);
                for (Identifier iconId : uniqueIconIds.keySet()) {
                    futures.add(executor.submit(() -> {
                        NativeImage img = IconCache.getOrLoadCopy(iconId, fp, builtinResourceManager, sampleResourceManager);
                        loadedIcons.put(iconId, img);
                    }));
                }
                // Wait for all icons to load
                for (var f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        LOGGER.warn("Failed to load icon in parallel", e);
                    }
                }
            }
        }

        // Assign loaded icons to the array (deduplication: same icon ID -> same NativeImage)
        for (int i = 0; i < previewData.structId2StructData().length; ++i) {
            PreviewData.StructureData data = previewData.structId2StructData()[i];
            Identifier iconId = data.icon();
            if (iconId == null) {
                iconId = Identifier.parse("world_preview:textures/structure/unknown.png");
            }
            allStructureIcons[i] = loadedIcons.get(iconId);
        }

        // Load player and spawn icons in parallel as well
        freePlayerAndSpawnIcons();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var playerFuture = executor.submit(() -> {
                try {
                    final Optional<Resource> playerResource = builtinResourceManager.getResource(
                            Identifier.parse("world_preview:textures/etc/player.png"));
                    try (InputStream in = playerResource.orElseThrow().open()) {
                        return NativeImage.read(in);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return new NativeImage(16, 16, true);
                }
            });
            var spawnFuture = executor.submit(() -> {
                try {
                    final Optional<Resource> spawnResource = builtinResourceManager.getResource(
                            Identifier.parse("world_preview:textures/etc/bed.png"));
                    try (InputStream in = spawnResource.orElseThrow().open()) {
                        return NativeImage.read(in);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return new NativeImage(16, 16, true);
                }
            });
            try {
                playerIcon = playerFuture.get();
                spawnIcon = spawnFuture.get();
            } catch (Exception e) {
                playerIcon = new NativeImage(16, 16, true);
                spawnIcon = new NativeImage(16, 16, true);
                e.printStackTrace();
            }
        }
    }

    /**
     * Loads a single structure icon from the builtin or sample resource manager.
     * Thread-safe: creates a new NativeImage and does not modify shared state.
     * Package-visible: also used as the decode routine by {@link IconCache},
     * which caches the decoded master and hands out private copies.
     */
    static NativeImage loadSingleIcon(Identifier iconId,
                                               ResourceManager builtinResourceManager,
                                               ResourceManager sampleResourceManager) {
        Optional<Resource> resource = builtinResourceManager.getResource(iconId);
        if (resource.isEmpty()) {
            resource = sampleResourceManager.getResource(iconId);
        }
        if (resource.isEmpty()) {
            LOGGER.error("Failed to load structure icon: '{}'", iconId);
            resource = builtinResourceManager.getResource(
                    Identifier.parse("world_preview:textures/structure/unknown.png"));
        }
        if (resource.isEmpty()) {
            LOGGER.error("FATAL ERROR LOADING: '{}' -- unable to load fallback!", iconId);
            return new NativeImage(16, 16, true);
        }
        try {
            try (InputStream in = resource.get().open()) {
                return NativeImage.read(in);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return new NativeImage(16, 16, true);
        }
    }

    private void buildStructureEntries(Registry<Structure> strucutreRegistry,
                                       LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess) {
        List<String> missing = Arrays.stream(previewData.structId2StructData())
                .filter(x -> x.dataSource() == PreviewData.DataSource.MISSING)
                .map(PreviewData.StructureData::tag)
                .map(Identifier::toString)
                .toList();
        worldPreview.writeMissingStructures(missing);

        Registry<Item> itemRegistry = layeredRegistryAccess.compositeAccess().lookupOrThrow(Registries.ITEM);
        allStructures = strucutreRegistry.listElements()
                .map(x -> {
                    final short id = previewData.struct2Id().getShort(x.key().identifier().toString());
                    if (id < 0 || id >= previewData.structId2StructData().length) {
                        return null;
                    }
                    final PreviewData.StructureData structureData = previewData.structId2StructData()[id];
                    return structuresList.createEntry(
                            id,
                            x.key().identifier(),
                            allStructureIcons[id],
                            structureData.item() == null ? null : itemRegistry.getValue(structureData.item()),
                            structureData.name(),
                            structureData.showByDefault(),
                            structureData.showByDefault()
                    );
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(StructuresList.StructureEntry::id))
                .toArray(StructuresList.StructureEntry[]::new);

        structuresList.replaceEntries(new ArrayList<>());
    }

    private void applySamplingFeatureToggles() {
        // Finalize the GUI
        // (renderSettings.resetCenter() already called earlier for early queue)

        if (cfg.sampleStructures) {
            toggleShowStructures.active = true;
            toggleShowStructures.setTooltip(Tooltip.create(BTN_TOGGLE_STRUCTURES));
        } else {
            toggleShowStructures.active = false;
            toggleShowStructures.setTooltip(Tooltip.create(BTN_TOGGLE_STRUCTURES_DISABLED));
        }

        if (cfg.sampleHeightmap) {
            toggleHeightmap.active = true;
            toggleHeightmap.setTooltip(Tooltip.create(BTN_TOGGLE_HEIGHTMAP));
        } else {
            toggleHeightmap.active = false;
            toggleHeightmap.setTooltip(Tooltip.create(BTN_TOGGLE_HEIGHTMAP_DISABLED));
            renderSettings.mode = renderSettings.mode == HEIGHTMAP ? BIOMES : renderSettings.mode;
        }

        if (cfg.sampleIntersections) {
            toggleIntersections.active = true;
            toggleIntersections.setTooltip(Tooltip.create(BTN_TOGGLE_INTERSECT));
        } else {
            toggleIntersections.active = false;
            toggleIntersections.setTooltip(Tooltip.create(BTN_TOGGLE_INTERSECT_DISABLED));
            renderSettings.mode = renderSettings.mode == INTERSECTIONS ? BIOMES : renderSettings.mode;
        }

        if (cfg.storeNoiseSamples) {
            toggleNoise.active = true;
            toggleNoise.setTooltip(Tooltip.create(BTN_TOGGLE_NOISE));
        } else {
            toggleNoise.active = false;
            toggleNoise.setTooltip(Tooltip.create(BTN_TOGGLE_NOISE_DISABLED));
            renderSettings.mode = renderSettings.mode.isNoise() ? BIOMES : renderSettings.mode;
        }

        selectViewMode(renderSettings.mode);
    }

    @Override
    public void onVisibleBiomesChanged(Short2LongMap visibleBiomes) {
        long now = System.nanoTime();
        long elapsed = now - lastBiomesListUpdateNanos;
        if (elapsed >= BIOMES_LIST_MIN_INTERVAL_NANOS) {
            applyVisibleBiomes(visibleBiomes);
            return;
        }
        // Inside the coalesce window: remember the newest counts and schedule
        // one trailing flush so the final state is never dropped.
        pendingVisibleBiomes = visibleBiomes;
        if (pendingBiomesListFlush == null || pendingBiomesListFlush.isDone()) {
            long delayMs = Math.max(1, (BIOMES_LIST_MIN_INTERVAL_NANOS - elapsed) / 1_000_000L);
            pendingBiomesListFlush = reloadExecutor.schedule(() -> minecraft.execute(() -> {
                // close() only cancels the outer schedule; an inner lambda
                // already queued on the main thread still runs. Guard at the
                // point of effect (same thread that runs close()).
                if (closed) {
                    return;
                }
                Short2LongMap pending = pendingVisibleBiomes;
                pendingVisibleBiomes = null;
                if (pending != null) {
                    applyVisibleBiomes(pending);
                }
            }), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void applyVisibleBiomes(Short2LongMap visibleBiomes) {
        lastBiomesListUpdateNanos = System.nanoTime();
        pendingVisibleBiomes = null;
        // Update visible count for all biomes
        for (BiomesList.BiomeEntry biome : allBiomes) {
            long count = visibleBiomes.getOrDefault(biome.id(), 0L);
            biome.setVisibleCount(count);
        }
        // Show all biomes: visible by count desc, invisible by name asc
        List<BiomesList.BiomeEntry> res = Arrays.stream(allBiomes)
                .sorted(Comparator.<BiomesList.BiomeEntry, Boolean>comparing(b -> b.visibleCount() == 0)
                        .thenComparing((b1, b2) -> {
                            if (b1.visibleCount() == 0 && b2.visibleCount() == 0) {
                                // Invisible biomes sorted by name
                                return b1.name().compareToIgnoreCase(b2.name());
                            } else {
                                // Visible biomes by count desc
                                return Long.compare(b2.visibleCount(), b1.visibleCount());
                            }
                        }))
                .toList();
        biomesList.replaceEntries(res);
    }

    @Override
    public void onVisibleStructuresChanged(Short2LongMap visibleStructures) {
        List<StructuresList.StructureEntry> res = visibleStructures.short2LongEntrySet()
                .stream()
                .sorted(Comparator.comparing(Short2LongMap.Entry::getLongValue))
                .map(Short2LongMap.Entry::getShortKey)
                .map(x -> allStructures[x])
                .toList();

        structuresList.replaceEntries(res);
    }

    /**
     * Applies a seed change. Called on every keystroke of the seed edit box
     * and by the randomize/apply flows; the (expensive) world reload is
     * debounced so a burst of calls starts exactly one reload.
     */
    public void setSeed(String seed) {
        if (Objects.equals(dataProvider.seed(), seed) || !dataProvider.seedIsEditable()) {
            return;
        }
        pendingSeedEditValue = seed;
        if (pendingSeedEditCommit != null) {
            pendingSeedEditCommit.cancel(false);
        }
        pendingSeedEditCommit = reloadExecutor.schedule(
                () -> minecraft.execute(() -> setSeedImmediate(seed)),
                SEED_EDIT_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Generates a fresh random string seed, applies it to the preview and
     * returns it so callers can sync their own seed edit boxes immediately
     * (the debounced commit only updates dataProvider.seed() ~300ms later).
     */
    public String randomizeSeed(Button btn) {
        UUID uuid = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.allocate(Long.BYTES * 2);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());

        String uuidSeed = Base64.getEncoder().encodeToString(bb.array()).substring(0, 16);
        setSeed(uuidSeed);
        // setSeed(String.valueOf(WorldOptions.randomSeed()));
        return uuidSeed;
    }

    /**
     * Applies a pending debounced seed edit immediately instead of waiting out
     * the debounce window.  Used by the seed screen's save flow so the saved
     * seed is the one currently typed, not the previously applied one.
     */
    public void commitSeedEdit() {
        if (pendingSeedEditCommit != null) {
            pendingSeedEditCommit.cancel(false);
            pendingSeedEditCommit = null;
        }
        if (pendingSeedEditValue != null) {
            String seed = pendingSeedEditValue;
            pendingSeedEditValue = null;
            setSeedImmediate(seed);
        }
    }

    /** Saves the current seed into the config's savedSeeds list (deduplicated). */
    public void saveCurrentSeed(Button btn) {
        if (dataProvider.seed().isEmpty()) {
            return;
        }
        if (!cfg.savedSeeds.contains(dataProvider.seed())) {
            cfg.savedSeeds.add(dataProvider.seed());
            saveSeed.active = false;
            // Persist immediately: a crash before the host screen closes must
            // not lose the just-saved seed.
            worldPreview().saveConfig();
        }
    }

    public void deleteSeed(String seed) {
        if (cfg.savedSeeds.remove(seed)) {
            worldPreview().saveConfig();
        }
        // Deleting the current seed re-enables the seed bar's save button.
        saveSeed.active = !dataProvider.seed().isEmpty() && !cfg.savedSeeds.contains(dataProvider.seed());
    }

    private void setSeedImmediate(String seed) {
        if (closed || Objects.equals(dataProvider.seed(), seed) || !dataProvider.seedIsEditable()) {
            return;
        }

        boolean initialInhibitUpdates = inhibitUpdates;
        inhibitUpdates = true;
        try {
            dataProvider.updateSeed(seed);
        } finally {
            inhibitUpdates = initialInhibitUpdates;
        }
        updateSettings();
    }

    public void resetTabs() {
        tabManager.resetTabs();
    }

    // === A5: settings-round-trip suspend/resume ===

    /** Refreshes the structural snapshot from the current live settings. */
    public synchronized void markStructuralRebuildApplied() {
        structuralFullVertSnapshot = cfg.buildFullVertChunk;
        structuralPpcSnapshot = renderSettings.pixelsPerChunk();
        structuralSamplerSnapshot = renderSettings.samplerType;
        structuralDimensionSnapshot = renderSettings.dimension;
    }

    /** True when the live structural settings differ from the snapshot taken at suspend time. */
    private boolean structuralSnapshotDiffers() {
        return structuralPpcSnapshot == -1
                || structuralFullVertSnapshot != cfg.buildFullVertChunk
                || structuralPpcSnapshot != renderSettings.pixelsPerChunk()
                || structuralSamplerSnapshot != renderSettings.samplerType
                || !Objects.equals(structuralDimensionSnapshot, renderSettings.dimension);
    }

    /**
     * Kills any in-flight updateSettings chain by bumping the reload revision
     * (the async chain aborts at its revision checks) and clearing the
     * isUpdating flag.  Prevents a stale chain from reviving the worker pools
     * while sampling is suspended.
     */
    private void invalidatePendingUpdates() {
        synchronized (reloadRevision) {
            reloadRevision.incrementAndGet();
        }
        isUpdating = false;
    }

    /**
     * Opens the settings screen: suspends sampling without destroying the
     * worldgen state, remembering the structural settings so the close path
     * can decide between a cheap resume and a full rebuild.
     */
    private void suspendForSettings() {
        invalidatePendingUpdates();
        structuralFullVertSnapshot = cfg.buildFullVertChunk;
        structuralPpcSnapshot = renderSettings.pixelsPerChunk();
        structuralSamplerSnapshot = renderSettings.samplerType;
        structuralDimensionSnapshot = renderSettings.dimension;
        workManager.suspend();
    }

    /**
     * Settings screen closed (Done or Cancel).  Routes between a cheap resume
     * (nothing structural changed, no biome color edits), a full rebuild
     * (structural settings changed and/or color edits must be pushed) and the
     * legacy full-apply path when a settings-page rebuild already resumed the
     * WorkManager.
     *
     * @param colorsChanged true when biome entries were edited in the settings
     *                      session (they must be pushed into the color mapping)
     */
    public synchronized void onSettingsClosed(boolean colorsChanged) {
        if (!workManager.isSuspended()) {
            // A settings page (e.g. ResolutionSettingsPage) already rebuilt the
            // preview via its own cancel+start.  The pending structural values
            // only land on the live settings when onDone applies them (after
            // that rebuild), and biome color edits still need to be pushed into
            // the color mapping -- so fall back to the existing full-apply path.
            if (colorsChanged) {
                patchColorData();
            } else if (structuralSnapshotDiffers()) {
                updateSettings();
            }
            return;
        }
        boolean structural = structuralSnapshotDiffers();
        if (structural || colorsChanged) {
            resumeForRebuild(colorsChanged);
        } else {
            resumeLight();
        }
    }

    /** Resumes sampling on the retained worldgen state without a rebuild. */
    private void resumeLight() {
        if (!workManager.resumeAfterSuspend()) {
            resumeForRebuild(false);
            return;
        }
        if (isUpdating) {
            return;
        }
        applySamplingFeatureToggles();
        // Clear the WorkManager-side dedup guard so the next viewport queue
        // pass re-issues its range; the display's 250ms unsampled-viewport
        // probe fills in any areas whose batches were killed at suspend time.
        workManager.resetQueuedRange();
        previewDisplay.invalidateRenderCache();
    }

    /** Resumes the WorkManager and then runs the existing full rebuild path. */
    private void resumeForRebuild(boolean colorsChanged) {
        // Clear the suspended flag (and rebuild the pools); the old preview
        // storage is disposed by cancel() inside the updateSettings chain.
        workManager.resumeAfterSuspend();
        invalidatePendingUpdates();
        if (colorsChanged) {
            patchColorData();   // ends with updateSettings()
        } else {
            updateSettings();
        }
        // Refresh the snapshot so a same-frame re-entry (e.g. the tab manager
        // re-registering the preview tab right after Done) does not trigger a
        // second full rebuild for changes already being applied.
        markStructuralRebuildApplied();
    }

/**
* Called when the parent screen is re-entered from a sub-screen
     * (e.g. WorldAnalysisScreen, SettingsScreen).
*
* <p>Invalidates the preview display's render cache so the preview
* is fully re-rendered on the next frame instead of reusing stale
* cached data from the sub-screen.  Without this, the preview
* appears transparent until the user clicks on it.
*
* <p>Also re-applies the current sidebar tab state so that the
* correct list (biomes / structures / seeds) is visible and
* properly positioned after the screen is re-initialised.
*/
public void onScreenReentry() {
    LOGGER.info("[WP-Reentry] onScreenReentry called: previewStorage={}, isUpdating={}, setupFailed={}, workManager.isSetup={}, suspended={}",
            workManager.previewStorage() != null,
            isUpdating,
            setupFailed,
            workManager.isSetup(),
            workManager.isSuspended());
    previewDisplay.invalidateRenderCache();
    // A5 fallback: if the WorkManager is still suspended when the screen comes
    // back (e.g. an unusual screen chain that bypassed the SettingsScreen
    // Done/Cancel handlers), resume here instead of falling through to the
    // previewStorage==null probe, which would tear down the preserved state.
    if (workManager.isSuspended()) {
        if (structuralSnapshotDiffers()) {
            resumeForRebuild(false);
        } else {
            resumeLight();
        }
        tabManager.reapplyCurrentTab();
        return;
    }
    // CRITICAL FIX: After returning from a sub-screen (e.g. TerrainExportScreen),
    // the WorkManager's previewStorage may be null because cancel() was called
    // when the Settings button was pressed.  Without previewStorage, the map
    // won't re-render during drag (needRerender=false when storage==null) and
    // tooltips won't work (shouldShowHoverData returns false when storage==null).
    // Detect this and re-trigger updateSettings() to rebuild the storage.
    if (workManager.previewStorage() == null && !isUpdating) {
        LOGGER.info("[WP-Reentry] previewStorage is null, re-triggering updateSettings()");
        updateSettings();
    } else if (workManager.previewStorage() == null && isUpdating) {
        LOGGER.warn("[WP-Reentry] previewStorage is null but isUpdating=true, skipping updateSettings (already in progress)");
    }
    // Re-apply the current tab so list visibility/active states are correct.
    tabManager.reapplyCurrentTab();
}

    // Sidebar lists share the same layout slot; visibility/active alone hide inactive
    // tabs. The old +/-4096 off-screen moveList hack is no longer used (P2).

    /**
     * Start generating the biome data
     */
    public synchronized void start() {
        LOGGER.info("Start generating biome data...");
        if (dataProvider.seed().isEmpty()) {
            randomizeSeed(null);
        }
        inhibitUpdates = false;
        if (workManager.isSuspended()) {
            // Resuming after stop(): the world settings may have changed while
            // updates were inhibited (tab was switched away), so invalidate the
            // structural snapshot and take the full rebuild path.
            structuralPpcSnapshot = -1;
            if (structuralSnapshotDiffers()) {
                resumeForRebuild(false);
            } else {
                resumeLight();
            }
            return;
        }
        if (workManager.isSetup() && workManager.previewStorage() != null && !structuralSnapshotDiffers()) {
            // A5: re-entry after a sub-screen round trip -- the tab manager
            // re-registers the preview tab and calls start() again.  When
            // onSettingsClosed already resumed the suspended WorkManager and no
            // structural setting changed, a full updateSettings() here would
            // tear the preserved state down again (and kill running analysis
            // sessions) for nothing.  Only re-apply the sampling feature
            // toggles; the viewport is re-queued by the render loop.
            applySamplingFeatureToggles();
            return;
        }
        updateSettings();
    }

    /**
     * Stop processing
     */
    public synchronized void stop() {
        LOGGER.info("Stop generating biome data...");
        // Prevent an in-flight updateSettings chain from reviving the worker
        // pools while sampling is suspended.
        invalidatePendingUpdates();
        inhibitUpdates = true;
        workManager.suspend();
    }

    public void doLayout(ScreenRectangle screenRectangle) {
        if (screenRectangle == null) {
            screenRectangle = minecraft.gui.screen().getRectangle();
        }
        lastScreenRectangle = screenRectangle;

        if (sidebarCollapsed) {
            doLayoutCollapsed(screenRectangle);
        } else {
            doLayoutExpanded(screenRectangle);
        }
    }

    /**
     * Collapsed layout: narrow 28px icon rail on the left edge.
     * The map fills the rest of the screen.  Clicking a rail icon
     * slides out a floating semi-transparent panel over the map.
     */
    private void doLayoutCollapsed(ScreenRectangle screenRectangle) {
        final int left = screenRectangle.left() + 2;
        final int railLeft = left;
        // Issue 6: Expand map left boundary to cover rail buttons
        final int mapLeft = left;
        final int top = screenRectangle.top() + 2;
        // Bug 4: reduced bottom margin from 28 to BUTTON_GRID_STEP to use more vertical space
        final int bottom = screenRectangle.bottom() - BUTTON_GRID_STEP;
        final int mapWidth = screenRectangle.right() - mapLeft - 4;
        final int mapHeight = bottom - top;

        // --- Preview display: extends from left edge to right edge ---
        previewDisplay.setPosition(mapLeft, top);
        previewDisplay.setSize(mapWidth, mapHeight);

        // Bug 2 fix: explicitly set all control buttons visible and active
        settings.visible = true;
        resetToZeroZero.visible = true;
        toggleShowStructures.visible = true;
        toggleCaves.visible = true;
        toggleExpand.visible = true;
        toggleSetSpawn.visible = (dataProvider.minecraftServer() == null);

        // --- Top control buttons (right-aligned over the map) ---
        int ctrlRight = screenRectangle.right() - 4;
        toggleExpand.setPosition(ctrlRight - BUTTON_GRID_STEP, top);
        resetToZeroZero.setPosition(ctrlRight - BUTTON_GRID_STEP * 2, top);
        toggleCaves.setPosition(ctrlRight - BUTTON_GRID_STEP * 3, top);
        toggleShowStructures.setPosition(ctrlRight - BUTTON_GRID_STEP * 4, top);
        settings.setPosition(ctrlRight - BUTTON_GRID_STEP * 5, top);

        // View toggle buttons: positioned to the left of the settings button
        // (visible when toggleExpand is selected).  The noiseCycleButton width is
        // reduced by 2x settings-button width (44px) so the entire toggle group
        // fits between the map left edge and the settings button.
        final int noiseBtnWidth = 200 - BUTTON_GRID_STEP * 2;
        noiseCycleButton.setWidth(noiseBtnWidth);
        final int toggleGroupWidth = BUTTON_GRID_STEP * 4 + noiseBtnWidth;
        int viewBtnX = settings.getX() - toggleGroupWidth - 2;
        if (viewBtnX < mapLeft) {
            viewBtnX = mapLeft;
        }
        if (toggleExpand.selected) {
            int vi = 0;
            toggleBiomes.setPosition(viewBtnX + BUTTON_GRID_STEP * vi++, top);
            toggleIntersections.setPosition(viewBtnX + BUTTON_GRID_STEP * vi++, top);
            toggleHeightmap.setPosition(viewBtnX + BUTTON_GRID_STEP * vi++, top);
            toggleNoise.setPosition(viewBtnX + BUTTON_GRID_STEP * vi++, top);
            noiseCycleButton.setPosition(viewBtnX + BUTTON_GRID_STEP * vi++, top);
            toggleBiomes.visible = true;
            toggleIntersections.visible = true;
            toggleHeightmap.visible = true;
            toggleNoise.visible = true;
            noiseCycleButton.visible = true;
        } else {
            toggleBiomes.visible = false;
            toggleIntersections.visible = false;
            toggleHeightmap.visible = false;
            toggleNoise.visible = false;
            noiseCycleButton.visible = false;
        }

        // --- Rail icons (vertical stack, rendered ON TOP of the map) ---
        int railY = top + 2;
        int switchHeight = LINE_HEIGHT - 2;
        int maxSwitchWidth = RAIL_WIDTH - 2;
        // The seed search and analysis buttons share the rail, so their labels
        // count toward the shared auto width (computed before the setWidth calls).
        seedSearchButton.updateAutoWidth();
        maxSwitchWidth = Math.max(maxSwitchWidth, seedSearchButton.getWidth());
        openAnalysis.updateAutoWidth();
        maxSwitchWidth = Math.max(maxSwitchWidth, openAnalysis.getWidth());
        if (switchBiomes instanceof TranslucentButton tb) { tb.updateAutoWidth(); maxSwitchWidth = Math.max(maxSwitchWidth, tb.getWidth()); }
        if (switchStructures instanceof TranslucentButton ts) { ts.updateAutoWidth(); maxSwitchWidth = Math.max(maxSwitchWidth, ts.getWidth()); }
        switchBiomes.setWidth(maxSwitchWidth);
        switchStructures.setWidth(maxSwitchWidth);
        switchBiomes.setPosition(railLeft, railY);
        switchBiomes.active = true;
        railY += switchHeight + 4;
        switchStructures.setPosition(railLeft, railY);
        switchStructures.active = true;
        railY += switchHeight + 4;
        // Seed search button directly below the two rail buttons.
        seedSearchButton.setPosition(railLeft, railY);
        seedSearchButton.setWidth(maxSwitchWidth);
        seedSearchButton.visible = cfg.showSeedSearchButton;
        railY += switchHeight + 4;
        // Analysis button below the seed search button.
        openAnalysis.setPosition(railLeft, railY);
        openAnalysis.setWidth(maxSwitchWidth);
        openAnalysis.visible = cfg.showAnalysisButton;

        // Reset structures visibility (compact, at bottom of rail)
        resetDefaultStructureVisibility.setPosition(railLeft, bottom - BUTTON_GRID_STEP);
        resetDefaultStructureVisibility.setWidth(RAIL_WIDTH - 2);

        // Bug 5: Seed bar layout restructuring
        // Seed input shortened by 1.5 button widths (~33px), left-aligned
        int seedBarY = bottom + 2;
        int btnW = BUTTON_GRID_STEP;
        int spawnW = (int)(btnW * 2.5);  // 2.5x button width
        int toolsW = btnW * 4;           // waypoint + measure buttons (2 cells each)
        int seedEditWidth = (screenRectangle.right() - left - 4) - btnW * 2 - spawnW - toolsW - 8;
        if (seedEditWidth < 60) seedEditWidth = 60;
        seedEdit.setWidth(seedEditWidth);
        seedEdit.setX(left);
        seedEdit.setY(seedBarY);

        int btnX = left + seedEditWidth + 2;
        randomSeedButton.setX(btnX);
        randomSeedButton.setY(seedBarY);
        btnX += btnW;
        saveSeed.setX(btnX);
        saveSeed.setY(seedBarY);
        btnX += btnW;

        // Waypoint + measure buttons (2 grid cells each)
        toggleWaypoints.setWidth(btnW * 2);
        toggleWaypoints.setX(btnX);
        toggleWaypoints.setY(seedBarY);
        btnX += btnW * 2 + 2;
        toggleMeasure.setWidth(btnW * 2);
        toggleMeasure.setX(btnX);
        toggleMeasure.setY(seedBarY);
        btnX += btnW * 2 + 2;

        // toggleSetSpawn: 2.5x width, right of the tool buttons
        toggleSetSpawn.setWidth(spawnW);
        toggleSetSpawn.setX(btnX);
        toggleSetSpawn.setY(seedBarY);

        // --- Floating panel overlay ---
        boolean showBiomesList = (floatingPanel == 0);
        boolean showStructuresList = (floatingPanel == 1);

        // The analysis button now lives in the rail stack, so the floating
        // panel no longer needs to skip an extra row for it.
        int panelTop = top + LINE_HEIGHT + LINE_VSPACE;
        int panelBottom = bottom - 4;
        int panelHeight = panelBottom - panelTop;
        int panelX = mapLeft + 4;

        if (showBiomesList) {
            biomesList.setPosition(panelX, panelTop);
            biomesList.setSize(FLOATING_PANEL_WIDTH, panelHeight);
            biomesList.visible = true;
            biomesList.active = true;
            structuresList.visible = false;
            structuresList.active = false;
        } else if (showStructuresList) {
            structuresList.setPosition(panelX, panelTop);
            structuresList.setSize(FLOATING_PANEL_WIDTH, panelHeight);
            structuresList.visible = true;
            structuresList.active = true;
            biomesList.visible = false;
            biomesList.active = false;
        } else {
            biomesList.visible = false;
            biomesList.active = false;
            structuresList.visible = false;
            structuresList.active = false;
        }
    }

    /**
     * Expanded layout: original sidebar layout.
     */
    private void doLayoutExpanded(ScreenRectangle screenRectangle) {
        // Restore list visibility managed by tabManager
        if (tabManager != null) {
            tabManager.reapplyCurrentTab();
        }
        int leftWidth = Math.max(130, Math.min(180, screenRectangle.width() / 3));
        int left = screenRectangle.left() + 3;
        int previewLeft = left + leftWidth + 3;
        int top = screenRectangle.top() + 2;
        int bottom = screenRectangle.bottom() - 32;

        // Preview
        final int expand = toggleExpand.selected ? BUTTON_GRID_STEP + 2 : 0;

        previewDisplay.setPosition(previewLeft, top + expand + 1);
        previewDisplay.setSize(screenRectangle.right() - previewDisplay.getX() - 4, screenRectangle.bottom() - previewDisplay.getY() - 14);

        // BOTTOM

        seedEdit.setWidth(leftWidth - 1 - BUTTON_GRID_STEP * 2);
        seedEdit.setX(left);
        seedEdit.setY(bottom + 1);

        randomSeedButton.setX((left + leftWidth) - 20);
        randomSeedButton.setY(bottom);

        saveSeed.setX((left + leftWidth) - BUTTON_GRID_STEP - 20);
        saveSeed.setY(bottom);

        // TOP
        int cycleWith = leftWidth - BUTTON_GRID_STEP * 4;

                int btnStart = left + cycleWith + 2;
        settings.setPosition(left, top);
        int spawnStretch = Math.max(60, btnStart - 2 - (left + BUTTON_GRID_STEP));
        int thirdWidth = Math.max(20, (spawnStretch - 4) / 3);
        toggleWaypoints.setPosition(left + BUTTON_GRID_STEP, top);
        toggleWaypoints.setWidth(thirdWidth);
        toggleMeasure.setPosition(left + BUTTON_GRID_STEP + thirdWidth + 2, top);
        toggleMeasure.setWidth(thirdWidth);
        toggleSetSpawn.setPosition(left + BUTTON_GRID_STEP + thirdWidth * 2 + 4, top);
        toggleSetSpawn.setWidth(Math.max(20, spawnStretch - thirdWidth * 2 - 4));
        toggleSetSpawn.visible = (dataProvider.minecraftServer() == null);

        int i = 0;
        toggleShowStructures.setPosition(btnStart + BUTTON_GRID_STEP * i++, top);
        toggleCaves.setPosition(btnStart + BUTTON_GRID_STEP * i++, top);
        resetToZeroZero.setPosition(btnStart + BUTTON_GRID_STEP * i++, top);
        toggleExpand.setPosition(btnStart + BUTTON_GRID_STEP * i++, top);

        // TOP - hidden buttons
        // Reduce noiseCycleButton width by 2x settings-button width for consistency
        // with the collapsed layout.
        noiseCycleButton.setWidth(200 - BUTTON_GRID_STEP * 2);
        i = 0;
        toggleBiomes.setPosition(previewLeft + BUTTON_GRID_STEP * i++, top);
        toggleIntersections.setPosition(previewLeft + BUTTON_GRID_STEP * i++, top);
        toggleHeightmap.setPosition(previewLeft + BUTTON_GRID_STEP * i++, top);
        toggleNoise.setPosition(previewLeft + BUTTON_GRID_STEP * i++, top);
        noiseCycleButton.setPosition(previewLeft + BUTTON_GRID_STEP * i++, top);

        //  - new row
        // Advance top past the TOP control row so the switch buttons and the
        // list below do not overlap.  (The analysis button now lives below
        // the seed search button, so the TOP section is always one row.)
        top += LINE_HEIGHT + LINE_VSPACE;
        int switchBiomesWidth = 45;
        int switchStructuresWidth = leftWidth - switchBiomesWidth - 4;
        switchBiomes.setPosition(left, top);
        switchStructures.setPosition(left + switchBiomesWidth + 2, top);

        switchBiomes.setWidth(switchBiomesWidth);
        switchStructures.setWidth(switchStructuresWidth);

        // Seed search button directly below the switch row, the analysis
        // button below it; the lists start below whichever rows are shown.
        seedSearchButton.setPosition(left, top + LINE_HEIGHT + LINE_VSPACE);
        seedSearchButton.setWidth(leftWidth);
        seedSearchButton.visible = cfg.showSeedSearchButton;
        openAnalysis.setPosition(left, top + (cfg.showSeedSearchButton ? 2 : 1) * (LINE_HEIGHT + LINE_VSPACE));
        openAnalysis.setWidth(leftWidth);
        openAnalysis.visible = cfg.showAnalysisButton;

        //  - new row
        top += ((cfg.showSeedSearchButton ? 2 : 1) + (cfg.showAnalysisButton ? 1 : 0)) * (LINE_HEIGHT + LINE_VSPACE);

        biomesList.setPosition(left, top);
        biomesList.setSize(leftWidth, bottom - top - LINE_VSPACE);

        // BOTTOM
        //  - new row
        bottom -= LINE_HEIGHT + LINE_VSPACE;

        resetDefaultStructureVisibility.setPosition(left, bottom);
        resetDefaultStructureVisibility.setWidth(leftWidth);

        structuresList.setPosition(left, top);
        structuresList.setSize(leftWidth, bottom - top - LINE_VSPACE);
    }

    @Override
    public void close() {
        closed = true;
        if (pendingSeedEditCommit != null) {
            pendingSeedEditCommit.cancel(false);
        }
        if (pendingBiomesListFlush != null) {
            pendingBiomesListFlush.cancel(false);
        }
        seedSearchService.close();
        terrainExportController.close();
        closeActiveAnalysisSession();
        workManager.cancel();
        previewDisplay.close();
        freeStructureIcons();
        freePlayerAndSpawnIcons();
        if (reloadExecutor != null) {
            reloadExecutor.shutdownNow();
            boolean interrupted = false;
            try {
                if (!reloadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    WorldPreview.LOGGER.warn("reloadExecutor did not terminate within 5s");
                }
            } catch (InterruptedException e) {
                interrupted = true;
                WorldPreview.LOGGER.warn("Interrupted while awaiting reloadExecutor termination");
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void freeStructureIcons() {
        if (allStructureIcons == null) {
            return;
        }
        Arrays.stream(allStructureIcons).filter(Objects::nonNull).forEach(NativeImage::close);
        allStructureIcons = null;
    }

    private void freePlayerAndSpawnIcons() {
        if (playerIcon != null) {
            playerIcon.close();
            playerIcon = null;
        }
        if (spawnIcon != null) {
            spawnIcon.close();
            spawnIcon = null;
        }
    }

    public List<BiomesList.BiomeEntry> allBiomes() {
        return Arrays.stream(allBiomes).sorted(Comparator.comparing(BiomesList.BiomeEntry::name)).toList();
    }

    public List<Identifier> levelStemKeys() {
        return levelStemKeys;
    }

    public Registry<LevelStem> levelStemRegistry() {
        return levelStemRegistry;
    }

    @Override
    public BiomesList.BiomeEntry biome4Id(int id) {
        if (id < 0 || id >= allBiomes.length) return null;
        return allBiomes[id];
    }

    @Override
    public StructuresList.StructureEntry structure4Id(int id) {
        if (id < 0 || id >= allStructures.length) return null;
        return allStructures[id];
    }

    @Override
    public NativeImage[] structureIcons() {
        return allStructureIcons;
    }

    @Override
    public NativeImage playerIcon() {
        return playerIcon;
    }

    @Override
    public NativeImage spawnIcon() {
        return spawnIcon;
    }

    @Override
    public ItemStack[] structureItems() {
        return Arrays.stream(allStructures).map(StructuresList.StructureEntry::itemStack).toArray(ItemStack[]::new);
    }

    @Override
    public void onBiomeVisuallySelected(BiomesList.BiomeEntry entry) {
        biomesList.setSelected(entry, true);
        toggleCaves.selected = false;
        previewDisplay.setHighlightCaves(false);
    }

    @Override
    public PreviewData previewData() {
        return previewData;
    }

    public static int analysisYForDimension(int minY, int height) {
        return minY + height;
    }

    public void openAnalysisScreen() {
        if (!workManager.isSetup()) return;
        try {
            // Re-attach to the running/paused session when it still belongs to
            // this exact worldgen state (same epoch, seed and dimension).
            AnalysisSession existing = activeAnalysisSession;
            if (existing != null) {
                AnalysisRequest existingRequest = existing.request();
                var currentContext = workManager.worldgenContext();
                boolean sameWorld = activeAnalysisSessionEpoch == workManager.epoch()
                        && currentContext != null
                        && existingRequest.seed() == currentContext.seed()
                        && existingRequest.dimension().equals(currentContext.dimension());
                if (sameWorld) {
                    minecraft.gui.setScreen(new WorldAnalysisScreen(parentScreen, existing, this, existingRequest.region()));
                    return;
                }
                closeActiveAnalysisSession();
            }
            BlockPos center = previewDisplay.center();
            int radius = Math.max(1, Math.min(RegionSelector.MAX_DIMENSION / 2, previewDisplay.getWidth() * 4));
            AnalysisRequest request = workManager.analysisRequest(
                    new BlockPos(center.getX() - radius, center.getY(), center.getZ() - radius),
                    new BlockPos(center.getX() + radius, center.getY(), center.getZ() + radius));
            AnalysisSession session = workManager.openAnalysisSession(request);
            activeAnalysisSession = session;
            activeAnalysisSessionEpoch = workManager.epoch();
            minecraft.gui.setScreen(new WorldAnalysisScreen(parentScreen, session, this, request.region()));
        } catch (RuntimeException error) {
            // Never swallow silently: a throw here means the analysis screen
            // (or its session) failed to build while the button already lost
            // its press, which is undebuggable without the stack trace.
            LOGGER.error("Failed to open the analysis screen", error);
            openAnalysis.active = false;
        }
    }

    /**
     * Opens the seed screen, defaulting to the saved-seeds view when no search
     * is running or has results — the sidebar "Seeds" button's old behavior of
     * opening the saved-seed list directly.
     *
     * @param biome biome pre-selected in the search screen's picker (nullable)
     * @param structure structure pre-selected as search criterion (nullable)
     * @param autoStart whether to start searching immediately after opening
     */
    public void openSeedSearchScreen(@Nullable BiomesList.BiomeEntry biome,
                                     @Nullable StructuresList.StructureEntry structure,
                                     boolean autoStart) {
        SeedSearchScreen.View initialView =
                (isSeedSearchRunning() || lastSeedSearchResult() != null)
                        ? SeedSearchScreen.View.RESULTS
                        : SeedSearchScreen.View.SAVED;
        openSeedSearchScreen(biome, structure, autoStart, initialView);
    }

    public void openSeedSearchScreen(@Nullable BiomesList.BiomeEntry biome,
                                     @Nullable StructuresList.StructureEntry structure,
                                     boolean autoStart, SeedSearchScreen.View initialView) {
        if (!workManager.isSetup()) return;
        minecraft.gui.setScreen(new caeruleusTait.world.preview.client.gui.screens.SeedSearchScreen(
                parentScreen, this, biome, structure, autoStart, initialView));
    }

    public WorkManager workManager() {
        return workManager;
    }

    public boolean isCacheLoading() {
        return cacheLoading;
    }

    public void setCacheLoading(boolean v) {
        cacheLoading = v;
    }

    public void openTerrainExportScreen() {
        if (terrainExportSampler == null) return;
        minecraft.gui.setScreen(new TerrainExportScreen(parentScreen, this));
    }

    public void startTerrainExport(TerrainExportSpec spec) {
        if (terrainExportController.isRunning()) return;
        if (terrainExportSampler == null) return;
        // Lineage guard: the sampler was built for a specific worldgen context.
        // After a seed/dimension/generator switch the old sampler must not
        // produce exports labeled with (or sampling) the new world.
        if (samplerContextEpoch != workManager.epoch()) {
            LOGGER.warn("Terrain export rejected: worldgen context changed since the sampler was built");
            return;
        }
        Path outputDir = worldPreview.configDir().resolve("terrain_exports");
        terrainExportController.start(spec, terrainExportSampler, heightProbe(), exportOrigin(),
                buildTerrainExportBiomeFacts(),
                workManager.yMin(), workManager.yMax(), outputDir);
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
    private TerrainMapExporter.BiomeFacts buildTerrainExportBiomeFacts() {
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
        final byte[] estimatedHeightById = new byte[biomeDataById.length];
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

        // The export sampler evaluates biomes at Y=64, so reuse the storage's
        // Y=64 biome layer. Completion bits live on the primary section layer:
        // y=0 with full-vertical chunk sampling, the sampled layer itself
        // otherwise. An unsampled or partially sampled chunk makes the probe
        // answer null (noise-sampling fallback for that pixel) — never a
        // stale or missing value.
        final int completionQuartY = net.minecraft.core.QuartPos.fromBlock(cfg.buildFullVertChunk ? 0 : 64);
        final int dataQuartY = net.minecraft.core.QuartPos.fromBlock(64);
        TerrainMapExporter.BiomeIdProbe probe = (blockX, blockZ) -> {
            int qx = net.minecraft.core.QuartPos.fromBlock(blockX);
            int qz = net.minecraft.core.QuartPos.fromBlock(blockZ);
            if (!storage.isChunkSampled(qx, completionQuartY, qz,
                    caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_BIOME)) {
                return null;
            }
            short v = storage.getRawData4(qx, dataQuartY, qz,
                    caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_BIOME);
            return v == Short.MIN_VALUE ? null : v;
        };

        return new TerrainMapExporter.BiomeFacts(
                probe, new TerrainMapExporter.IdTableResolver(categoryById, estimatedHeightById));
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

    public void cancelTerrainExport() {
        terrainExportController.cancel();
    }

    public TerrainExportController.Status terrainExportStatus() {
        return terrainExportController.status();
    }

    public PreviewDisplay previewDisplay() {
        return previewDisplay;
    }

    @Override
    public PreviewDisplayDataProvider.StructureRenderInfo[] renderStructureMap() {
        return allStructures;
    }

    @Override
    public int[] heightColorMap() {
        ColorMap colorMap = previewData.colorMaps().get(cfg.colorMap);
        if (colorMap == null) {
            int[] black = new int[workManager.yMax() - workManager.yMin()];
            Arrays.fill(black, 0xFF000000);
            return black;
        }
        return colorMap.bake(workManager.yMin(), workManager.yMax(), cfg.heightmapMinY, cfg.heightmapMaxY);
    }

    @Override
    public int[] noiseColorMap() {
        ColorMap colorMap = previewData.colorMaps().get(cfg.colorMap);
        if (colorMap == null) {
            int[] black = new int[256];
            Arrays.fill(black, 0xFF000000);
            return black;
        }
        return colorMap.bake(1024);
    }

    @Override
    public int[] noiseColorMapFor(final RenderSettings.RenderMode mode) {
        return noiseColorProvider.tableFor(mode);
    }

    @Override
    public int yMin() {
        return workManager.yMin();
    }

    @Override
    public int yMax() {
        return workManager.yMax();
    }

    @Override
    public boolean isUpdating() {
        return isUpdating;
    }

    @Override
    public boolean setupFailed() {
        return setupFailed;
    }

    @Override
    public @NotNull PlayerData getPlayerData(UUID playerId) {
        if (workManager == null || workManager.sampleUtils() == null) {
            return new PlayerData(null, null);
        }
        ServerPlayer player = workManager.sampleUtils().getPlayers(playerId);
        if (player == null) {
            return new PlayerData(null, null);
        }
        ResourceKey<Level> playerDimension = player.level().dimension();
        var respawnConfig = player.getRespawnConfig();
        ResourceKey<Level> respawnDimension = respawnConfig != null ? respawnConfig.respawnData().dimension() : null;
        BlockPos respawnPos = respawnConfig != null ? respawnConfig.respawnData().pos() : null;
        ResourceKey<Level> currentDimension = workManager.sampleUtils().dimension();

        return new PlayerData(
                currentDimension.equals(playerDimension) ? player.blockPosition() : null,
                currentDimension.equals(respawnDimension) ? respawnPos : null
        );
    }

    public ToggleButton toggleCaves() {
        return toggleCaves;
    }

    public ToggleButton toggleShowStructures() {
        return toggleShowStructures;
    }

    public ToggleButton toggleHeightmap() {
        return toggleHeightmap;
    }

    public ToggleButton toggleIntersections() {
        return toggleIntersections;
    }

    public PreviewContainerDataProvider dataProvider() {
        return dataProvider;
    }

    /** All known structure entries (for the seed search criteria UI). */
    public StructuresList.StructureEntry[] structureEntries() {
        return allStructures;
    }

    /** Whether the current screen allows changing the seed. */
    public boolean seedIsEditable() {
        return dataProvider.seedIsEditable();
    }

    /**
     * Fingerprint of the live worldgen context, or null before setup. Result
     * lineage checks (search hits, waypoints) compare against this value.
     */
    @Nullable
    public String currentContextFingerprint() {
        var ctx = workManager.worldgenContext();
        return ctx != null ? ctx.fingerprint() : null;
    }

    /**
     * Factory for lightweight per-seed samplers (biome + structure probing)
     * built for the current worldgen context, or {@code null} before the
     * context is set up. Callers must close the created samplers.
     */
    @Nullable
    public SeedSearchService.SeedContextFactory seedSearchFactory() {
        return seedSearchFactory;
    }

    public List<AbstractWidget> widgets() {
        return toRender;
    }
}
