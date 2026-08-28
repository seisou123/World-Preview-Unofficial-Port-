// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.analysis.AnalysisSession;
import caeruleusTait.world.preview.backend.analysis.AnalysisRequest;
import caeruleusTait.world.preview.backend.analysis.Region;
import caeruleusTait.world.preview.backend.analysis.SeedSearchRequest;
import caeruleusTait.world.preview.backend.analysis.SeedSearchResult;
import caeruleusTait.world.preview.backend.analysis.SeedSearchService;
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
import caeruleusTait.world.preview.client.gui.widgets.lists.SeedsList;
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
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import caeruleusTait.world.preview.backend.export.TerrainExportController;
import caeruleusTait.world.preview.backend.export.TerrainExportSpec;
import caeruleusTait.world.preview.backend.export.TerrainMapExporter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private volatile SeedSearchService.SeedContextFactory seedSearchFactory;
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
    private Button openAnalysis;
    private Button seedSearchButton;
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
    private Button switchSeeds;
    private Button toggleSetSpawn;
    private boolean spawnPinActive = false;

    // === Slide-out rail system ===
    // When true, the sidebar collapses to a narrow 28px icon rail and the map
    // expands to fill the freed space.  Clicking a rail icon slides out a
    // floating semi-transparent panel that overlays the map.
    private boolean sidebarCollapsed = true;  // collapsed by default for max map area
    // Which floating panel is currently shown over the map:
    // -1 = none, 0 = biomes, 1 = structures, 2 = seeds
    private int floatingPanel = -1;
    // Width of the floating panel when collapsed
    private static final int RAIL_WIDTH = 28;
    private static final int FLOATING_PANEL_WIDTH = 180;

    /** Grid step (px) used to lay out the 20x20 toolbar buttons with 2px gaps. */
    private static final int BUTTON_GRID_STEP = 22;

    /** Shared daemon scheduler for delayed UI cleanups (replaces per-call Timers). */
    private static final ScheduledExecutorService SEARCH_UI_CLEANUP = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "world_preview-search-ui-cleanup");
        t.setDaemon(true);
        return t;
    });
    private final PreviewDisplay previewDisplay;
    private BiomesList biomesList;
    private StructuresList structuresList;
    private SeedsList seedsList;
    private PreviewContainerTabManager tabManager;
    private BiomesList.BiomeEntry[] allBiomes;
    private StructuresList.StructureEntry[] allStructures;
    private NativeImage[] allStructureIcons;
    private NativeImage playerIcon;
    private NativeImage spawnIcon;
    private List<SeedsList.SeedEntry> seedEntries;
    private ScreenRectangle lastScreenRectangle;

    private boolean inhibitUpdates = true;
    private boolean isUpdating = false;
    private boolean setupFailed = false;
    private volatile boolean cacheLoading = false;
    private final ExecutorService reloadExecutor = Executors.newSingleThreadExecutor();
    private final Executor serverThreadPoolExecutor;
    private final AtomicInteger reloadRevision = new AtomicInteger(0);

    private final List<AbstractWidget> toRender = new ArrayList<>();

    public PreviewContainer(Screen screen, PreviewContainerDataProvider previewContainerDataProvider) {
        final Font font = ((ScreenAccessor) screen).getFont();
        dataProvider = previewContainerDataProvider;
        parentScreen = screen;
        minecraft = Minecraft.getInstance();
        allBiomes = new BiomesList.BiomeEntry[0];
        worldPreview = WorldPreview.get();
        cfg = worldPreview.cfg();
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
        // Seed bar widgets join right after the map so they keep event priority.
        toRender.add(seedEdit);
        toRender.add(randomSeedButton);
        toRender.add(saveSeed);

        createTopActionButtons(screen);
        createRailButtons(font);
        createListsAndTabs();
        createViewToggles();
        createSpawnControls();

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
        openAnalysis = Button.builder(WorldPreviewComponents.ANALYSIS_OPEN, ignored -> openAnalysisScreen())
                .size(100, LINE_HEIGHT)
                .build();
        openAnalysis.active = false;
        openAnalysis.visible = cfg.showAnalysisButton;
        toRender.add(openAnalysis);

        seedSearchButton = Button.builder(WorldPreviewComponents.SEARCH_OPEN, ignored -> openSeedSearchScreen(null, false))
                .size(100, LINE_HEIGHT)
                .build();
        seedSearchButton.setTooltip(Tooltip.create(WorldPreviewComponents.SEARCH_OPEN_TOOLTIP));
        toRender.add(seedSearchButton);

        settings = iconButton(60, 20, x -> {
            workManager.cancel();
            minecraft.setScreen(new caeruleusTait.world.preview.client.gui.screens.settings.SettingsScreen(screen, this));
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

    /** Creates the three sidebar rail buttons (biomes / structures / seeds). */
    private void createRailButtons(Font font) {
        switchBiomes = railButton(font, PreviewContainerTabManager.DisplayType.BIOMES, 0,
                x -> tabManager.onTabButtonChange(x, PreviewContainerTabManager.DisplayType.BIOMES));
        switchStructures = railButton(font, PreviewContainerTabManager.DisplayType.STRUCTURES, 1,
                x -> tabManager.onTabButtonChange(x, PreviewContainerTabManager.DisplayType.STRUCTURES));
        switchSeeds = railButton(font, PreviewContainerTabManager.DisplayType.SEEDS, 2,
                x -> tabManager.onTabButtonChange(x, PreviewContainerTabManager.DisplayType.SEEDS));

        toRender.add(switchBiomes);
        toRender.add(switchStructures);
        toRender.add(switchSeeds);
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
        structuresList.setRightClickListener(entry -> openSeedSearchScreen(entry, true));

        seedsList = new SeedsList(minecraft, this);
        updateSeedListWidget();

        tabManager = new PreviewContainerTabManager(
                cfg,
                biomesList,
                structuresList,
                seedsList,
                switchBiomes,
                switchStructures,
                switchSeeds,
                resetDefaultStructureVisibility
        );

        // Lists are added after previewDisplay (which is now at index 0 in
        // toRender).  In forward-order event dispatch, this gives the lists
        // priority over the map.  They also render on top of the map.
        toRender.add(biomesList);
        toRender.add(structuresList);
        toRender.add(seedsList);
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

    /** Wires cross-widget callbacks that depend on several groups being built. */
    private void wireCallbacks() {
        biomesList.setBiomeChangeListener(x -> {
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
        updateSeedListWidget();
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
    }

    public void onBiomeRightClick(BiomesList.BiomeEntry entry) {
        if (seedSearchService.isSearching()) {
            seedSearchService.cancel();
            updateSearchUI(false, null);
            return;
        }
        startBiomeSearch(entry);
    }

    private void startBiomeSearch(BiomesList.BiomeEntry entry) {
        if (seedSearchFactory == null) {
            LOGGER.warn("Search context not available");
            return;
        }

        var viewport = currentSearchViewport();
        var request = new SeedSearchRequest(
            entry.entry().key().identifier(),
            viewport.dimension(),
            viewport.center(),
            viewport.center().getY(),
            viewport.viewMinX(), viewport.viewMaxX(), viewport.viewMinZ(), viewport.viewMaxZ(),
            viewport.sampleStep(),
            viewport.contextFingerprint(),
            SeedSearchRequest.DEFAULT_MAX_ATTEMPTS,
            cfg.searchMinAreaPercent,
            cfg.searchMaxDistance
        );

        updateSearchUI(true, Component.translatable(
            "world_preview.search.progress", 0, request.maxAttempts()));

        seedSearchService.startSearch(request, seedSearchFactory,
            hitSeed -> {
                LOGGER.info("Found seed {} for biome {}", hitSeed, entry.name());
                setSeed(String.valueOf(hitSeed));
            },
            result -> {
                if (result instanceof SeedSearchResult.Hit hit) {
                    updateSearchUI(false, Component.translatable(
                        "world_preview.search.found", String.valueOf(hit.seed())));
                } else if (result instanceof SeedSearchResult.Miss) {
                    updateSearchUI(false, Component.translatable(
                        "world_preview.search.not_found", request.maxAttempts()));
                } else {
                    updateSearchUI(false, Component.translatable(
                        "world_preview.search.stopped"));
                }
                // Clear status text after 3 seconds. A single shared daemon
                // scheduler is reused across searches; creating a fresh
                // java.util.Timer per search leaked one thread each time.
                SEARCH_UI_CLEANUP.schedule(() -> minecraft.execute(() -> {
                    if (!seedSearchService.isSearching()) {
                        updateSearchUI(false, null);
                    }
                }), 3, TimeUnit.SECONDS);
            },
            attempts -> updateSearchUI(true, Component.translatable(
                "world_preview.search.progress", attempts, request.maxAttempts()))
        );
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
     * Starts an advanced (multi-criteria) seed search.
     *
     * @return false when the search context is missing or a search is running
     */
    public boolean startSeedSearch(
            SeedSearchRequest request,
            java.util.function.Consumer<SeedSearchResult> onComplete,
            java.util.function.Consumer<Integer> onProgress
    ) {
        if (seedSearchFactory == null) {
            LOGGER.warn("Search context not available");
            return false;
        }
        return seedSearchService.startSearch(request, seedSearchFactory,
                null, onComplete, onProgress);
    }

    private void updateSearchUI(boolean active, @Nullable Component status) {
        biomesList.setSearchActive(active, status);
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
                        NativeImage img = loadSingleIcon(iconId, builtinResourceManager, sampleResourceManager);
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
     */
    private static NativeImage loadSingleIcon(Identifier iconId,
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

    private void randomizeSeed(Button btn) {
        UUID uuid = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.allocate(Long.BYTES * 2);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());

        String uuidSeed = Base64.getEncoder().encodeToString(bb.array()).substring(0, 16);
        setSeed(uuidSeed);
        // setSeed(String.valueOf(WorldOptions.randomSeed()));
    }

    private void saveCurrentSeed(Button btn) {
        cfg.savedSeeds.add(dataProvider.seed());
        saveSeed.active = false;
        updateSeedListWidget();
    }

    public void deleteSeed(String seed) {
        cfg.savedSeeds.remove(seed);
        updateSeedListWidget();
    }

    public void setSeed(String seed) {
        if (Objects.equals(dataProvider.seed(), seed) || !dataProvider.seedIsEditable()) {
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

    private void updateSeedListWidget() {
        seedEntries = cfg.savedSeeds.stream().map(seedsList::createEntry).toList();
        seedsList.replaceEntries(seedEntries);
        int idx = cfg.savedSeeds.indexOf(dataProvider.seed());
        if (idx >= 0) {
            seedsList.setSelected(seedEntries.get(idx));
        }
    }

    public void resetTabs() {
        tabManager.resetTabs();
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
    LOGGER.info("[WP-Reentry] onScreenReentry called: previewStorage={}, isUpdating={}, setupFailed={}, workManager.isSetup={}",
            workManager.previewStorage() != null,
            isUpdating,
            setupFailed,
            workManager.isSetup());
    previewDisplay.invalidateRenderCache();
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
        updateSettings();
    }

    /**
     * Stop processing
     */
    public synchronized void stop() {
        LOGGER.info("Stop generating biome data...");
        inhibitUpdates = true;
        workManager.cancel();
    }

    public void doLayout(ScreenRectangle screenRectangle) {
        if (screenRectangle == null) {
            screenRectangle = minecraft.screen.getRectangle();
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

        // Analysis button (if enabled); seed search button sits below it.
        if (cfg.showAnalysisButton) {
            openAnalysis.visible = true;
            openAnalysis.setPosition(mapLeft, top);
            openAnalysis.setWidth(Math.min(120, mapWidth / 3));
        } else {
            openAnalysis.visible = false;
        }
        seedSearchButton.visible = true;
        seedSearchButton.setPosition(mapLeft, top + (cfg.showAnalysisButton ? LINE_HEIGHT + LINE_VSPACE : 0));
        seedSearchButton.setWidth(Math.min(120, mapWidth / 3));

        // --- Rail icons (vertical stack, rendered ON TOP of the map) ---
        int railY = top + 2;
        int switchHeight = LINE_HEIGHT - 2;
        int maxSwitchWidth = RAIL_WIDTH - 2;
        if (switchBiomes instanceof TranslucentButton tb) { tb.updateAutoWidth(); maxSwitchWidth = Math.max(maxSwitchWidth, tb.getWidth()); }
        if (switchStructures instanceof TranslucentButton ts) { ts.updateAutoWidth(); maxSwitchWidth = Math.max(maxSwitchWidth, ts.getWidth()); }
        if (switchSeeds instanceof TranslucentButton tsp) { tsp.updateAutoWidth(); maxSwitchWidth = Math.max(maxSwitchWidth, tsp.getWidth()); }
        switchBiomes.setWidth(maxSwitchWidth);
        switchStructures.setWidth(maxSwitchWidth);
        switchSeeds.setWidth(maxSwitchWidth);
        switchBiomes.setPosition(railLeft, railY);
        switchBiomes.active = true;
        railY += switchHeight + 4;
        switchStructures.setPosition(railLeft, railY);
        switchStructures.active = true;
        railY += switchHeight + 4;
        switchSeeds.setPosition(railLeft, railY);
        switchSeeds.active = true;
        railY += switchHeight + 4;

        // Reset structures visibility (compact, at bottom of rail)
        resetDefaultStructureVisibility.setPosition(railLeft, bottom - BUTTON_GRID_STEP);
        resetDefaultStructureVisibility.setWidth(RAIL_WIDTH - 2);

        // Bug 5: Seed bar layout restructuring
        // Seed input shortened by 1.5 button widths (~33px), left-aligned
        int seedBarY = bottom + 2;
        int btnW = BUTTON_GRID_STEP;
        int spawnW = (int)(btnW * 2.5);  // 2.5x button width
        int seedEditWidth = (screenRectangle.right() - left - 4) - btnW * 2 - spawnW - 6;
        if (seedEditWidth < 80) seedEditWidth = 80;
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

        // toggleSetSpawn: 2.5x width, right of saveSeed
        toggleSetSpawn.setWidth(spawnW);
        toggleSetSpawn.setX(btnX);
        toggleSetSpawn.setY(seedBarY);

        // --- Floating panel overlay ---
        boolean showBiomesList = (floatingPanel == 0);
        boolean showStructuresList = (floatingPanel == 1);
        boolean showSeedsList = (floatingPanel == 2);

        int panelTop = top + (cfg.showAnalysisButton ? 2 * (LINE_HEIGHT + LINE_VSPACE) : LINE_HEIGHT + LINE_VSPACE);
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
            seedsList.visible = false;
            seedsList.active = false;
        } else if (showStructuresList) {
            structuresList.setPosition(panelX, panelTop);
            structuresList.setSize(FLOATING_PANEL_WIDTH, panelHeight);
            structuresList.visible = true;
            structuresList.active = true;
            biomesList.visible = false;
            biomesList.active = false;
            seedsList.visible = false;
            seedsList.active = false;
        } else if (showSeedsList) {
            seedsList.setPosition(panelX, panelTop);
            seedsList.setSize(FLOATING_PANEL_WIDTH, panelHeight);
            seedsList.visible = true;
            seedsList.active = true;
            biomesList.visible = false;
            biomesList.active = false;
            structuresList.visible = false;
            structuresList.active = false;
        } else {
            biomesList.visible = false;
            biomesList.active = false;
            structuresList.visible = false;
            structuresList.active = false;
            seedsList.visible = false;
            seedsList.active = false;
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
        toggleSetSpawn.setPosition(left + BUTTON_GRID_STEP, top);
        toggleSetSpawn.setWidth(btnStart - 2 - (left + BUTTON_GRID_STEP));
        toggleSetSpawn.visible = (dataProvider.minecraftServer() == null);
        
        // Toggle analysis button visibility; seed search button sits below it.
        if (cfg.showAnalysisButton) {
            openAnalysis.visible = true;
            openAnalysis.setPosition(left, top + LINE_HEIGHT + LINE_VSPACE);
            openAnalysis.setWidth(leftWidth);
        } else {
            openAnalysis.visible = false;
        }
        seedSearchButton.visible = true;
        seedSearchButton.setPosition(left, top + (cfg.showAnalysisButton ? 2 : 1) * (LINE_HEIGHT + LINE_VSPACE));
        seedSearchButton.setWidth(leftWidth);
        
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
        // The TOP section above occupies 1-3 rows depending on the analysis
        // and seed-search buttons.  Advance top past the buttons so the switch
        // buttons and the list below do not overlap.
        int topRows = cfg.showAnalysisButton ? 3 : 2;
        top += topRows * (LINE_HEIGHT + LINE_VSPACE);
        int switchBiomesWidth = 45;
        int switchSeedsWidth = 45;
        int switchStructuresWidth = leftWidth - switchBiomesWidth - switchSeedsWidth - 4;
        switchBiomes.setPosition(left, top);
        switchStructures.setPosition(left + switchBiomesWidth + 2, top);
        switchSeeds.setPosition(left + switchBiomesWidth + switchStructuresWidth + 4, top);

        switchBiomes.setWidth(switchBiomesWidth);
        switchStructures.setWidth(switchStructuresWidth);
        switchSeeds.setWidth(switchSeedsWidth);

        //  - new row
        top += LINE_HEIGHT + LINE_VSPACE;

        biomesList.setPosition(left, top);
        biomesList.setSize(leftWidth, bottom - top - LINE_VSPACE);

        seedsList.setPosition(left, top);
        seedsList.setSize(leftWidth, bottom - top - LINE_VSPACE);

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
        seedSearchService.close();
        terrainExportController.close();
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
            BlockPos center = previewDisplay.center();
            int radius = Math.max(1, Math.min(RegionSelector.MAX_DIMENSION / 2, previewDisplay.getWidth() * 4));
            AnalysisRequest request = workManager.analysisRequest(
                    new BlockPos(center.getX() - radius, center.getY(), center.getZ() - radius),
                    new BlockPos(center.getX() + radius, center.getY(), center.getZ() + radius));
            AnalysisSession session = workManager.openAnalysisSession(request);
            minecraft.setScreen(new WorldAnalysisScreen(parentScreen, session, this, request.region()));
        } catch (RuntimeException ignored) {
            openAnalysis.active = false;
        }
    }

    /**
     * Opens the advanced seed search screen.
     *
     * @param structure structure pre-selected as search criterion (nullable)
     * @param autoStart whether to start searching immediately after opening
     */
    public void openSeedSearchScreen(@Nullable StructuresList.StructureEntry structure, boolean autoStart) {
        if (!workManager.isSetup()) return;
        var biome = biomesList.getSelected();
        minecraft.setScreen(new caeruleusTait.world.preview.client.gui.screens.SeedSearchScreen(
                parentScreen, this, biome, structure, autoStart));
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
        minecraft.setScreen(new TerrainExportScreen(parentScreen, this));
    }

    public void startTerrainExport(TerrainExportSpec spec) {
        if (terrainExportController.isRunning()) return;
        if (terrainExportSampler == null) return;
        Path outputDir = worldPreview.configDir().resolve("terrain_exports");
        terrainExportController.start(spec, terrainExportSampler, outputDir);
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

    public List<AbstractWidget> widgets() {
        return toRender;
    }
}
