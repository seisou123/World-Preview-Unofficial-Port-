// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.backend.analysis.SearchCriterion;
import caeruleusTait.world.preview.backend.analysis.SeedSearchHistory;
import caeruleusTait.world.preview.backend.analysis.SeedSearchRequest;
import caeruleusTait.world.preview.backend.analysis.SeedSearchResult;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomePickerList;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import caeruleusTait.world.preview.client.gui.widgets.lists.SearchResultsList;
import caeruleusTait.world.preview.client.gui.widgets.lists.StructuresList;
import caeruleusTait.world.preview.domain.waypoint.Waypoint;
import caeruleusTait.world.preview.domain.waypoint.WaypointStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Unified seed screen: enter/randomize/save the world seed, run advanced seed
 * searches combining biome criteria chosen in a filterable multi-select picker
 * (any-of biome group) with a structure criterion, and browse ranked results
 * plus the persistent history, favorites and saved seeds.
 *
 * Layout: the criteria live in a left panel (filter row, biome picker,
 * structure button and a "More Options" button summarizing the advanced
 * values), the results/history/favorites/saved views share a right panel with
 * a tab row; the advanced sliders themselves moved to the
 * {@link SeedSearchOptionsScreen} sub-page.
 */
public final class SeedSearchScreen extends Screen implements SearchResultsList.RowActions {

    /** Maximum number of biomes that may be combined into one group criterion. */
    private static final int MAX_BIOMES_PER_SEARCH = 4;

    /** Y where the two panels start (below the seed row). */
    private static final int PANELS_TOP = 46;
    /** Inner padding of the panels. */
    private static final int PANEL_PAD = 6;
    /** Panel background and 1px outline colors. */
    private static final int PANEL_BG = 0xF01A1A24;
    private static final int PANEL_BORDER = 0xFF3A3A4A;
    /** Accent green of the active tab's selection line. */
    private static final int ACCENT_GREEN = 0xFF55FF55;

    private final Screen parent;
    private final PreviewContainer container;
    /** Only used to prefill the biome picker (right-click entry point); not a criterion itself. */
    @Nullable private final BiomesList.BiomeEntry initialBiome;
    @Nullable private final StructuresList.StructureEntry initialStructure;
    private final boolean autoStart;

    @Nullable private Identifier structureId;
    @Nullable private String structureName;

    private BiomePickerList biomePicker;
    private EditBox filterBox;
    private Button showCavesButton;
    private Button clearBiomesButton;
    private Button structureButton;
    /** Opens the options sub-page; its label summarizes the non-default advanced values. */
    private Button optionsButton;
    private Button startButton;
    private Button stopButton;
    private Button compareButton;
    private Button backButton;
    /** View tabs (results/history/favorites/saved) rendered above the results list. */
    private final Map<View, Button> viewTabs = new EnumMap<>(View.class);
    private SearchResultsList resultsList;
    private EditBox seedEdit;
    private Button randomizeSeedButton;
    private Button saveSeedButton;

    /** Y of the footer action row (Start/Stop/Compare), pinned to the screen bottom. */
    private int actionRowY;
    /** Bottom edge shared by both panels, 8px above the footer action row. */
    private int panelBottom;
    /** Left ("search criteria") and right (results) panel geometry, set by layoutWidgets. */
    private int leftPanelX;
    private int leftPanelW;
    private int rightPanelX;
    private int rightPanelW;

    private boolean showCaves = false;
    private final List<SearchResultsList.Row> hitRows = new ArrayList<>();
    /** The latest finished result with its lineage, kept for chained actions. */
    @Nullable private SeedSearchResult currentResult;
    private String statusText = "";
    private boolean searching = false;
    /**
     * Set when a search is started from {@link #init()} (setScreen is unsafe
     * there); the next tick navigates back to the preview screen.
     */
    private boolean pendingReturn;
    /** When the screen was opened with a quick search (right-click), the best hit is applied automatically. */
    private boolean applyBestOnComplete;
    private View currentView = View.RESULTS;
    /** True when the current view has no rows (drives the centered empty-state hint). */
    private boolean resultsEmpty = true;

    /** Tabs of the results list; SAVED shows the config's persistent savedSeeds. */
    enum View { RESULTS, HISTORY, FAVORITES, SAVED }

    public SeedSearchScreen(Screen parent, PreviewContainer container,
                            @Nullable BiomesList.BiomeEntry biome,
                            @Nullable StructuresList.StructureEntry structure,
                            boolean autoStart, View initialView) {
        super(WorldPreviewComponents.SEARCH_TITLE);
        this.parent = parent;
        this.container = container;
        this.initialBiome = biome;
        this.initialStructure = structure;
        this.autoStart = autoStart;
        this.applyBestOnComplete = autoStart;
        this.currentView = initialView;
        this.structureId = structure != null ? structure.structureId() : null;
        this.structureName = structure != null ? structure.name() : null;
    }

    // ===== Screen lifecycle =====

    @Override
    protected void init() {
        clearWidgets();
        // structureId/structureName are initialized in the constructor and kept
        // across re-inits so a structure picked in StructureSelectScreen
        // survives returning to this screen.
        hitRows.clear();
        statusText = "";

        // Biome criteria picker (multi-select, any-of group)
        biomePicker = new BiomePickerList(minecraft, container.allBiomes());
        biomePicker.setMaxSelections(MAX_BIOMES_PER_SEARCH);
        biomePicker.setOnSelectionRejected(id -> statusText = WorldPreviewComponents.SEARCH_BIOME_MAX.getString());
        biomePicker.setShowCaves(showCaves);
        if (initialBiome != null) {
            biomePicker.selectOnly(initialBiome.entry().key().identifier());
        }

        filterBox = new EditBox(font, 0, 0, 150, 20, WorldPreviewComponents.SEARCH_BIOME_FILTER);
        filterBox.setHint(WorldPreviewComponents.SEARCH_BIOME_FILTER);
        filterBox.setMaxLength(64);
        filterBox.setResponder(biomePicker::setFilter);

        showCavesButton = Button.builder(showCavesLabel(), btn -> {
            showCaves = !showCaves;
            btn.setMessage(showCavesLabel());
            biomePicker.setShowCaves(showCaves);
        }).size(150, 20).build();

        // Narrow clear button; the full "Clear" wording stays available as its
        // tooltip so the ✕ glyph stays unambiguous.
        clearBiomesButton = Button.builder(Component.literal("\u2715"), btn -> biomePicker.clearSelection())
                .size(20, 20).build();
        clearBiomesButton.setTooltip(Tooltip.create(WorldPreviewComponents.SEARCH_CLEAR_BIOME));

        // Structure criterion: opens the filterable structure picker screen
        // (None row + one row per structure with item icons).
        structureButton = Button.builder(structureButtonLabel(), ignored -> openStructureSelect())
                .size(160, 20).build();

        // Advanced options live on a sub-page; the button's label carries a
        // compact summary of the non-default values (vanilla "More World
        // Options…" pattern).
        optionsButton = Button.builder(optionsButtonLabel(), ignored -> openOptions())
                .size(160, 20).build();

        // View tabs for the results list: search hits, history, favorites and
        // the saved seeds.  A visible tab row replaces the former cycle button
        // buried in the criteria column so the saved-seed manager (the old
        // seeds sidebar's job) is discoverable again.
        viewTabs.put(View.RESULTS, Button.builder(WorldPreviewComponents.SEARCH_VIEW_RESULTS,
                ignored -> selectView(View.RESULTS)).size(60, 20).build());
        viewTabs.put(View.HISTORY, Button.builder(WorldPreviewComponents.SEARCH_VIEW_HISTORY,
                ignored -> selectView(View.HISTORY)).size(60, 20).build());
        viewTabs.put(View.FAVORITES, Button.builder(WorldPreviewComponents.SEARCH_VIEW_FAVORITES,
                ignored -> selectView(View.FAVORITES)).size(60, 20).build());
        viewTabs.put(View.SAVED, Button.builder(WorldPreviewComponents.SEARCH_VIEW_SAVED,
                ignored -> selectView(View.SAVED)).size(60, 20).build());

        // Current-seed section: mirrors the preview seed bar so this screen is
        // the single place managing seeds. Read-only hosts (in-game) disable
        // editing; applying/copying still works through the result rows.
        boolean seedEditable = container.seedIsEditable();
        seedEdit = new EditBox(font, 0, 0, 200, 20, WorldPreviewComponents.SEED_FIELD);
        seedEdit.setHint(WorldPreviewComponents.SEED_FIELD);
        seedEdit.setValue(container.dataProvider().seed());
        seedEdit.setResponder(container::setSeed);
        seedEdit.active = seedEditable;

        randomizeSeedButton = Button.builder(WorldPreviewComponents.SEARCH_SEED_RANDOM, ignored -> {
            // Take the generated seed from the return value: the debounced
            // commit has not updated dataProvider.seed() yet, so reading it
            // here would write the stale seed back into the box.
            seedEdit.setValue(container.randomizeSeed(null));
        }).size(70, 20).build();
        randomizeSeedButton.active = seedEditable;

        saveSeedButton = Button.builder(WorldPreviewComponents.SEARCH_SEED_SAVE, ignored -> {
            // Commit a pending debounced edit first so the saved seed is the
            // one currently typed (not the previously applied one), then
            // persist and jump to the saved list to show the new entry.
            container.commitSeedEdit();
            container.saveCurrentSeed(null);
            selectView(View.SAVED);
        }).size(70, 20).build();

        startButton = Button.builder(WorldPreviewComponents.SEARCH_START, ignored -> startSearch())
                .size(70, 20).build();
        stopButton = Button.builder(WorldPreviewComponents.SEARCH_STOP, ignored -> stopSearch())
                .size(70, 20).build();
        stopButton.active = false;
        compareButton = Button.builder(WorldPreviewComponents.COMPARISON_OPEN, ignored -> openComparison())
                .size(70, 20).build();

        resultsList = new SearchResultsList(minecraft, this);

        // Back button (same footer spot as WorldAnalysisScreen); added last so
        // it sits at the end of the click order.
        backButton = Button.builder(CommonComponents.GUI_BACK, ignored -> onClose())
                .size(90, 20)
                .build();

        addRenderableWidget(biomePicker);
        addRenderableWidget(seedEdit);
        addRenderableWidget(randomizeSeedButton);
        addRenderableWidget(saveSeedButton);
        addRenderableWidget(filterBox);
        addRenderableWidget(showCavesButton);
        addRenderableWidget(clearBiomesButton);
        addRenderableWidget(structureButton);
        addRenderableWidget(optionsButton);
        addRenderableWidget(startButton);
        addRenderableWidget(stopButton);
        addRenderableWidget(compareButton);
        for (Button tab : viewTabs.values()) {
            addRenderableWidget(tab);
        }
        addRenderableWidget(resultsList);
        addRenderableWidget(backButton);

        layoutWidgets();
        updateControlState();
        updateTabState();
        refreshList();

        // Take over a search that is still running in the background, or show
        // the most recent completed result.
        if (container.isSeedSearchRunning()) {
            container.reattachSeedSearchListener(this::onComplete, this::onProgress);
            statusText = WorldPreviewComponents.SEARCH_RUNNING.getString();
        } else {
            SeedSearchResult last = container.lastSeedSearchResult();
            if (last != null) {
                applyResult(last, container.lastSeedSearchCriteria(), false);
            }
        }

        if (autoStart) {
            startSearch();
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        layoutWidgets();
    }

    @Override
    public void onClose() {
        // Going back (button/Esc/E) leaves a running search alive in the
        // background; the Stop button is the explicit cancel.
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    // ===== Layout =====

    private void layoutWidgets() {
        int left = 8;

        // Bottom-up layout: the footer action row is pinned to the screen
        // bottom so Start/Stop/Compare stay visible even on small GUI scales;
        // the two panels reach down to just above it (8px breathing space) so
        // the biome picker and the results list absorb the leftover height.
        actionRowY = height - 32;
        panelBottom = actionRowY - 8;

        // Seed row directly below the title: seed box + randomize + save.
        // The box absorbs the spare width so long string seeds fit.
        int seedButtonW = 70;
        int seedEditW = Math.max(100, width - 2 * left - 2 * (seedButtonW + 4));
        seedEdit.setPosition(left, 22);
        seedEdit.setWidth(seedEditW);
        randomizeSeedButton.setPosition(left + seedEditW + 4, 22);
        saveSeedButton.setPosition(left + seedEditW + seedButtonW + 8, 22);

        // Two-panel geometry: the criteria panel on the left (36% of the
        // width, at least 240px) and the results panel taking the rest.
        leftPanelX = left;
        leftPanelW = Math.max(240, (int) (width * 0.36f));
        rightPanelX = leftPanelX + leftPanelW + 8;
        rightPanelW = Math.max(1, (width - left) - (rightPanelX - left) - 8);
        int innerX = leftPanelX + PANEL_PAD;
        int leftInnerW = leftPanelW - 2 * PANEL_PAD;

        // Filter row at the top of the criteria panel: flexible filter box, a
        // narrow clear button and the cave-biomes toggle (widths sized to the
        // localized toggle label so its text never clips).
        int filterY = PANELS_TOP + 16;
        int clearW = clearBiomesButton.getWidth();
        int cavesW = Math.max(60, Math.min(leftInnerW - clearW - 8 - 60,
                font.width(showCavesButton.getMessage()) + 10));
        int filterW = Math.max(40, leftInnerW - clearW - cavesW - 8);
        filterBox.setPosition(innerX, filterY);
        filterBox.setWidth(filterW);
        clearBiomesButton.setPosition(innerX + filterW + 4, filterY);
        showCavesButton.setPosition(innerX + filterW + clearW + 8, filterY);
        showCavesButton.setWidth(cavesW);

        // The fixed criteria rows: the "more options" button hugs the panel
        // bottom (6px inner padding) and the structure button sits 3px above
        // it; the biome picker absorbs everything between itself and the
        // structure row.
        int optionsY = panelBottom - PANEL_PAD - 20;
        int structureY = optionsY - 3 - 20;
        int listTop = filterY + 20 + 4;
        int listBottom = structureY - 4;
        structureButton.setPosition(innerX, structureY);
        structureButton.setWidth(leftInnerW);
        optionsButton.setPosition(innerX, optionsY);
        optionsButton.setWidth(leftInnerW);
        // The options summary is truncated to the button's width so a long
        // list of non-default values never spills over the panel edge.
        optionsButton.setMessage(fitLabelToWidth(optionsButtonLabel(), leftInnerW));

        biomePicker.setX(innerX);
        biomePicker.setY(listTop);
        biomePicker.setWidth(leftInnerW);
        biomePicker.setHeight(Math.max(60, listBottom - listTop));

        // View tab row inside the top of the results panel; the four tabs
        // share the panel's inner width evenly and the results list fills the
        // rest of the panel below the tab row.
        int tabY = PANELS_TOP + 6;
        int tabW = Math.max(1, (rightPanelW - 2 * PANEL_PAD) / 4);
        int tabX = rightPanelX + PANEL_PAD;
        for (View view : List.of(View.RESULTS, View.HISTORY, View.FAVORITES, View.SAVED)) {
            viewTabs.get(view).setPosition(tabX, tabY);
            viewTabs.get(view).setWidth(tabW);
            tabX += tabW;
        }
        resultsList.setX(rightPanelX + PANEL_PAD);
        resultsList.setY(tabY + 24);
        resultsList.setWidth(rightPanelW - 2 * PANEL_PAD);
        resultsList.setHeight(Math.max(40, (panelBottom - PANEL_PAD) - resultsList.getY()));

        // Footer action row; the Start button is widened as the primary action.
        startButton.setPosition(left, actionRowY);
        startButton.setWidth(110);
        stopButton.setPosition(left + 114, actionRowY);
        compareButton.setPosition(left + 188, actionRowY);

        // Back button in the footer, right-aligned (same spot as
        // WorldAnalysisScreen's close button).
        backButton.setX(width - 96);
        backButton.setY(actionRowY);
        backButton.setWidth(90);
        backButton.setHeight(20);
    }

    // ===== Search control =====

    private void startSearch() {
        if (container.isSeedSearchRunning()) {
            statusText = WorldPreviewComponents.SEARCH_RUNNING.getString();
            return;
        }
        var options = container.seedSearchOptions();
        var viewport = container.currentSearchViewport();
        BlockPos anchor = options.anchor == SeedSearchOptions.Anchor.ORIGIN
                ? new BlockPos(0, viewport.center().getY(), 0)
                : viewport.center();

        List<SearchCriterion> criteria = new ArrayList<>();
        List<Identifier> selectedBiomes = biomePicker.getSelectedIds();
        if (!selectedBiomes.isEmpty()) {
            // The picker selection becomes a single ANY-of group criterion; a
            // distance cap of 0 disables the biome proximity requirement.
            criteria.add(new SearchCriterion.BiomeGroup(
                    selectedBiomes, options.minAreaPercent, options.biomeMaxDistance));
        }
        if (structureId != null) {
            criteria.add(new SearchCriterion.Structure(
                    structureId, options.structureDistance));
        }
        if (criteria.isEmpty()) {
            statusText = WorldPreviewComponents.SEARCH_NO_CRITERIA.getString();
            return;
        }

        var request = new SeedSearchRequest(
                viewport.dimension(),
                anchor,
                viewport.center().getY(),
                viewport.viewMinX(), viewport.viewMaxX(), viewport.viewMinZ(), viewport.viewMaxZ(),
                viewport.sampleStep(),
                viewport.contextFingerprint(),
                options.attempts,
                criteria,
                options.hits
        );

        boolean started = container.startSeedSearch(request, criteriaLabel(), this::onComplete, this::onProgress);
        if (started) {
            searching = true;
            statusText = Component.translatable(
                    "world_preview.search.progress", 0, request.maxAttempts()).getString();
            updateControlState();
            // The search keeps running in the background: return to the
            // preview map immediately after a manual start, and on the next
            // tick after an auto-start (setScreen is unsafe inside init()).
            if (autoStart) {
                pendingReturn = true;
            } else {
                returnToPreview();
            }
        } else {
            statusText = WorldPreviewComponents.SEARCH_ERROR.getString();
        }
    }

    /** Returns to the preview screen; deliberately does NOT cancel the search. */
    private void returnToPreview() {
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    private void stopSearch() {
        container.cancelSeedSearch();
    }

    /** Opens the seed comparison screen; back navigation returns to this instance.
     *  Search hits (with their scores and located structures) are carried into
     *  the comparison as the preferred seed list instead of being dropped. */
    private void openComparison() {
        if (minecraft != null) {
            List<String> preferredHits = new ArrayList<>();
            if (currentResult instanceof SeedSearchResult.Hit hit) {
                preferredHits.add(String.valueOf(hit.seed()));
            } else if (currentResult instanceof SeedSearchResult.Multiple multiple) {
                for (SeedSearchResult.Ranked ranked : multiple.hits()) {
                    preferredHits.add(String.valueOf(ranked.seed()));
                }
            }
            minecraft.gui.setScreen(new SeedComparisonScreen(this, container, preferredHits));
        }
    }

    /** Label of the structure criterion button showing the picked structure (or None). */
    private Component structureButtonLabel() {
        return Component.translatable("world_preview.search.structure.value",
                structureName != null ? Component.literal(structureName) : WorldPreviewComponents.SEARCH_STRUCTURE_NONE);
    }

    /** Opens the structure picker screen for the structure criterion. */
    private void openStructureSelect() {
        if (minecraft != null) {
            minecraft.gui.setScreen(new StructureSelectScreen(this, container, structureId));
        }
    }

    /** Opens the advanced options sub-page; the shared options object is edited in place. */
    private void openOptions() {
        if (minecraft != null) {
            minecraft.gui.setScreen(new SeedSearchOptionsScreen(this, container, container.seedSearchOptions()));
        }
    }

    /**
     * Label of the more-options button: plain "More Options…" while every
     * advanced option is at its default, otherwise a compact summary listing
     * exactly the non-default values.
     */
    private Component optionsButtonLabel() {
        SeedSearchOptions options = container.seedSearchOptions();
        List<Component> parts = new ArrayList<>();
        if (options.anchor == SeedSearchOptions.Anchor.ORIGIN) {
            parts.add(Component.translatable("world_preview.search.more_options.anchor",
                    Component.translatable("world_preview.search.anchor.origin.short")));
        }
        if (options.minAreaPercent != 0) {
            // The percent sign rides in the argument: vanilla's translation
            // format would render a literal "%%" in the format string.
            parts.add(Component.translatable("world_preview.search.more_options.min_area", options.minAreaPercent + "%"));
        }
        if (options.biomeMaxDistance != 0) {
            parts.add(Component.translatable("world_preview.search.more_options.biome_distance", options.biomeMaxDistance));
        }
        if (options.structureDistance != 512) {
            parts.add(Component.translatable("world_preview.search.more_options.structure_distance", options.structureDistance));
        }
        if (options.attempts != 100) {
            parts.add(Component.translatable("world_preview.search.more_options.attempts", options.attempts));
        }
        if (options.hits != 1) {
            parts.add(Component.translatable("world_preview.search.more_options.hits", options.hits));
        }
        if (parts.isEmpty()) {
            return WorldPreviewComponents.SEARCH_MORE_OPTIONS;
        }
        StringBuilder joined = new StringBuilder();
        for (Component part : parts) {
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(part.getString());
        }
        return Component.translatable("world_preview.search.more_options.summary", joined.toString());
    }

    /** Fits the label text into the given width, appending an ellipsis when truncated. */
    private Component fitLabelToWidth(Component label, int maxWidth) {
        String text = label.getString();
        if (font.width(text) <= maxWidth) {
            return label;
        }
        while (text.length() > 1 && font.width(text + "…") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return Component.literal(text + "…");
    }

    /** Applies a structure picked in {@link StructureSelectScreen} (null = None). */
    void structurePicked(@Nullable Identifier id, @Nullable String name) {
        structureId = id;
        structureName = name;
        structureButton.setMessage(structureButtonLabel());
    }

    private void onProgress(int attempts) {
        statusText = Component.translatable(
                "world_preview.search.progress", attempts, container.seedSearchOptions().attempts).getString();
    }

    private void onComplete(SeedSearchResult result) {
        applyResult(result, criteriaLabel(), true);
    }

    /**
     * Applies a finished search result to the view. When {@code recordHistory}
     * is false (re-displaying the latest stored result on reopen) the hits are
     * not recorded again.
     */
    private void applyResult(SeedSearchResult result, String criteria, boolean recordHistory) {
        searching = false;
        hitRows.clear();
        currentResult = result;

        switch (result) {
            case SeedSearchResult.Hit hit -> {
                hitRows.add(resultsList.createRow(
                        String.valueOf(hit.seed()), criteria, hit.score(), false, false, hit.structurePos()));
                statusText = WorldPreviewComponents.SEARCH_FOUND.getString() + hit.seed();
            }
            case SeedSearchResult.Multiple multiple -> {
                for (SeedSearchResult.Ranked ranked : multiple.hits()) {
                    hitRows.add(resultsList.createRow(
                            String.valueOf(ranked.seed()), criteria, ranked.score(), false, false, ranked.structurePos()));
                }
                if (multiple.isEmpty()) {
                    statusText = WorldPreviewComponents.SEARCH_NOT_FOUND.getString();
                } else {
                    statusText = WorldPreviewComponents.SEARCH_FOUND.getString() + multiple.hits().get(0).seed();
                }
            }
            case SeedSearchResult.Miss ignored -> {
                statusText = WorldPreviewComponents.SEARCH_NOT_FOUND.getString();
            }
            default -> statusText = WorldPreviewComponents.SEARCH_STOPPED.getString();
        }

        // Apply the best hit automatically when the screen was opened via a
        // quick search (right-click on a structure entry).
        String bestSeed = null;
        if (!hitRows.isEmpty()) {
            bestSeed = hitRows.get(0).seed;
        }
        if (applyBestOnComplete && bestSeed != null) {
            applySeedChecked(bestSeed, requestOf(result));
        }

        // Remember the hits so they can be re-applied later.
        if (recordHistory) {
            var history = container.worldPreview().seedSearchHistory();
            for (SearchResultsList.Row row : hitRows) {
                history.record(row.seed, criteriaLabel());
            }
        }

        updateControlState();
        refreshList();
    }

    private void updateControlState() {
        searching = container.isSeedSearchRunning();
        startButton.active = !searching;
        stopButton.active = searching;
    }

    // ===== Criteria helpers =====

    private String criteriaLabel() {
        List<String> parts = new ArrayList<>();
        for (BiomesList.BiomeEntry entry : biomePicker.getSelectedEntries()) {
            parts.add(entry.name());
        }
        if (structureName != null) {
            parts.add(structureName);
        }
        return String.join(" + ", parts);
    }

    /** Label for the cave-biomes toggle with an [x]/[  ] prefix showing the state. */
    private Component showCavesLabel() {
        return Component.literal((showCaves ? "[x] " : "[  ]")
                + WorldPreviewComponents.SEARCH_BIOME_SHOW_CAVES_SHORT.getString());
    }

    // ===== Result list =====

    /** Switches the results-list view and syncs the tab row selection. */
    private void selectView(View view) {
        currentView = view;
        updateTabState();
        refreshList();
    }

    /** Marks the active tab green — vanilla buttons have no selected state. */
    private void updateTabState() {
        for (Map.Entry<View, Button> tab : viewTabs.entrySet()) {
            Component label = switch (tab.getKey()) {
                case RESULTS -> WorldPreviewComponents.SEARCH_VIEW_RESULTS;
                case HISTORY -> WorldPreviewComponents.SEARCH_VIEW_HISTORY;
                case FAVORITES -> WorldPreviewComponents.SEARCH_VIEW_FAVORITES;
                case SAVED -> WorldPreviewComponents.SEARCH_VIEW_SAVED;
            };
            tab.getValue().setMessage(currentView == tab.getKey()
                    ? label.copy().withStyle(ChatFormatting.GREEN)
                    : label);
        }
    }

    private void refreshList() {
        List<SearchResultsList.Row> rows = switch (currentView) {
            case RESULTS -> hitRows;
            case HISTORY -> historyRows(container.worldPreview().seedSearchHistory().byRecency());
            case FAVORITES -> historyRows(container.worldPreview().seedSearchHistory().favorites());
            case SAVED -> savedRows();
        };
        resultsEmpty = rows.isEmpty();
        resultsList.setRows(rows);
    }

    /** Rows for the saved-seeds view, backed by the config's savedSeeds list. */
    private List<SearchResultsList.Row> savedRows() {
        List<SearchResultsList.Row> rows = new ArrayList<>();
        String currentSeed = container.dataProvider().seed();
        for (String seed : container.worldPreview().cfg().savedSeeds) {
            SearchResultsList.Row row = resultsList.createRow(seed, null, 0, false, true);
            row.current = seed.equals(currentSeed);
            rows.add(row);
        }
        return rows;
    }

    private List<SearchResultsList.Row> historyRows(List<SeedSearchHistory.Entry> entries) {
        List<SearchResultsList.Row> rows = new ArrayList<>();
        for (SeedSearchHistory.Entry entry : entries) {
            // Raw label (may be blank): passing displayLabel() here would feed
            // the seed back as the label and render it twice per row.
            rows.add(resultsList.createRow(
                    entry.seed, entry.label, 0, entry.favorite, true));
        }
        return rows;
    }

    @Override
    public void onApply(String seed) {
        if (currentView == View.SAVED) {
            // Saved seeds are user-entered: no search lineage to verify.
            applySeed(seed);
            return;
        }
        applySeedChecked(seed, requestOf(currentResult));
    }

    /** The originating request of a search result, when it carries lineage. */
    @Nullable
    private static SeedSearchRequest requestOf(@Nullable SeedSearchResult result) {
        if (result instanceof SeedSearchResult.Hit hit) {
            return hit.request();
        }
        if (result instanceof SeedSearchResult.Multiple multiple) {
            return multiple.request();
        }
        return null;
    }

    /**
     * Applies a seed after verifying the result's lineage: when the search was
     * run under a different worldgen context (seed/dimension/generator/compat
     * changed since), the stale action is rejected instead of silently applying
     * a result computed for another world.
     */
    private void applySeedChecked(String seed, @Nullable SeedSearchRequest source) {
        if (source != null) {
            String currentFingerprint = container.currentContextFingerprint();
            if (currentFingerprint == null || !currentFingerprint.equals(source.contextFingerprint())) {
                statusText = WorldPreviewComponents.SEARCH_STALE.getString();
                return;
            }
        }
        applySeed(seed);
    }

    @Override
    public void onCreateWaypoint(String seed) {
        if (currentResult == null) {
            return;
        }
        BlockPos structurePos = null;
        if (currentResult instanceof SeedSearchResult.Hit hit
                && hit.seed() == parseSeed(seed)
                && hit.structurePos() != null) {
            structurePos = hit.structurePos();
        } else if (currentResult instanceof SeedSearchResult.Multiple multiple) {
            for (SeedSearchResult.Ranked ranked : multiple.hits()) {
                if (ranked.seed() == parseSeed(seed) && ranked.structurePos() != null) {
                    structurePos = ranked.structurePos();
                    break;
                }
            }
        }
        if (structurePos == null) {
            return;
        }
        SeedSearchRequest source = requestOf(currentResult);
        String dimension = source != null ? source.dimension()
                : (container.currentContextFingerprint() != null
                    && container.workManager().worldgenContext() != null
                        ? container.workManager().worldgenContext().dimension() : null);
        if (dimension == null) {
            return;
        }
        long seedLong = parseSeed(seed);
        int color = WaypointStore.PALETTE[(int) (seedLong & 0x7FFFFFFL) % WaypointStore.PALETTE.length];
        String name = structureName != null ? structureName : "structure";
        var waypoint = Waypoint.create(name, structurePos.getX(), structurePos.getY(), structurePos.getZ(),
                dimension, color, seedLong);
        container.worldPreview().waypointStore().add(waypoint);
        statusText = Component.translatable("world_preview.search.waypoint_created",
                name, structurePos.getX(), structurePos.getZ()).getString();
    }

    /** Numeric seeds parse directly; text seeds hash like vanilla's own seed parsing. */
    static long parseSeed(String seed) {
        try {
            return Long.parseLong(seed.trim());
        } catch (NumberFormatException e) {
            return seed.hashCode();
        }
    }

    @Override
    public void onToggleFavorite(String seed) {
        if (currentView == View.SAVED) {
            return;
        }
        container.worldPreview().seedSearchHistory().toggleFavorite(seed);
        refreshList();
    }

    @Override
    public void onDelete(String seed) {
        if (currentView == View.SAVED) {
            container.deleteSeed(seed);
        } else {
            container.worldPreview().seedSearchHistory().remove(seed);
        }
        refreshList();
    }

    private void applySeed(String seed) {
        if (container.seedIsEditable()) {
            container.setSeed(seed);
            seedEdit.setValue(seed);
            statusText = WorldPreviewComponents.SEARCH_APPLIED.getString() + seed;
            refreshList();
        } else {
            if (minecraft != null && minecraft.keyboardHandler != null) {
                minecraft.keyboardHandler.setClipboard(seed);
            }
            statusText = WorldPreviewComponents.SEARCH_APPLIED_CLIPBOARD.getString() + seed;
        }
    }

    // ===== Rendering =====

    @Override
    public void tick() {
        super.tick();
        if (pendingReturn) {
            pendingReturn = false;
            returnToPreview();
            return;
        }
        if (searching != container.isSeedSearchRunning()) {
            updateControlState();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);

        renderPanels(graphics);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // Drawn on top of the widgets: the active tab's selection line, the
        // empty-state hint and the status bar.
        renderTabSelection(graphics);
        renderEmptyState(graphics);
        renderStatusBar(graphics);
    }

    /** Draws both panel backgrounds plus the criteria panel's header line. */
    private void renderPanels(GuiGraphicsExtractor graphics) {
        renderPanelBackground(graphics, leftPanelX, PANELS_TOP, leftPanelW, panelBottom - PANELS_TOP);
        renderPanelBackground(graphics, rightPanelX, PANELS_TOP, rightPanelW, panelBottom - PANELS_TOP);

        // Criteria header: panel title on the left, gray biome count on the
        // right (the former standalone count line, folded into the header).
        graphics.text(font, WorldPreviewComponents.SEARCH_CRITERIA_TITLE,
                leftPanelX + PANEL_PAD, PANELS_TOP + 5, 0xFFFFFFFF);
        Component count = Component.translatable("world_preview.search.biome.selected",
                biomePicker.getSelectedCount());
        graphics.text(font, count,
                leftPanelX + leftPanelW - PANEL_PAD - font.width(count), PANELS_TOP + 5, 0xFF999999);
    }

    /** One panel: dark fill plus a 1px outline. */
    private void renderPanelBackground(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        graphics.fill(x, y, x + w, y + h, PANEL_BG);
        graphics.fill(x, y, x + w, y + 1, PANEL_BORDER);
        graphics.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        graphics.fill(x, y, x + 1, y + h, PANEL_BORDER);
        graphics.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    /** Draws the 2px green selection line under the active view tab. */
    private void renderTabSelection(GuiGraphicsExtractor graphics) {
        Button tab = viewTabs.get(currentView);
        if (tab != null) {
            graphics.fill(tab.getX(), tab.getY() + tab.getHeight() - 2,
                    tab.getX() + tab.getWidth(), tab.getY() + tab.getHeight(), ACCENT_GREEN);
        }
    }

    /** Draws a centered gray hint when the current view has no rows. */
    private void renderEmptyState(GuiGraphicsExtractor graphics) {
        if (!resultsEmpty) {
            return;
        }
        Component empty = switch (currentView) {
            case RESULTS -> WorldPreviewComponents.SEARCH_EMPTY_RESULTS;
            case HISTORY -> WorldPreviewComponents.SEARCH_EMPTY_HISTORY;
            case FAVORITES -> WorldPreviewComponents.SEARCH_EMPTY_FAVORITES;
            case SAVED -> WorldPreviewComponents.SEARCH_EMPTY_SAVED;
        };
        graphics.centeredText(font, empty,
                resultsList.getX() + resultsList.getWidth() / 2,
                resultsList.getY() + resultsList.getHeight() / 2 - 4,
                0xFF808080);
    }

    private void renderStatusBar(GuiGraphicsExtractor graphics) {
        if (statusText != null && !statusText.isEmpty()) {
            // Draw in the strip below the footer action row so the status can
            // never cover the Start/Stop/Compare buttons or the back button.
            int statusY = height - 11;
            graphics.fill(0, actionRowY + 20, width, height, 0xAA000000);
            graphics.text(font, statusText, 8, statusY, 0xFFFFFF55);
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (super.keyPressed(event)) {
            return true;
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return false;
    }
}
