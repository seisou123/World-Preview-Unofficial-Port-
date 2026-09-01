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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Advanced seed search screen: combines biome criteria chosen in a filterable
 * multi-select picker (any-of biome group) with a structure criterion,
 * configures search limits and shows ranked results plus the persistent
 * search history and favorites.
 */
public final class SeedSearchScreen extends Screen implements SearchResultsList.RowActions {

    /** Maximum number of biomes that may be combined into one group criterion. */
    private static final int MAX_BIOMES_PER_SEARCH = 4;

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
    private CycleButton<Anchor> anchorCycle;
    private IntSlider minAreaSlider;
    private IntSlider biomeDistanceSlider;
    private IntSlider structureDistanceSlider;
    private IntSlider attemptsSlider;
    private IntSlider hitsSlider;
    private Button startButton;
    private Button stopButton;
    private Button compareButton;
    private Button backButton;
    private CycleButton<View> viewCycle;
    private SearchResultsList resultsList;

    /** Y of the footer action row (Start/Stop/Compare + hits), pinned to the screen bottom. */
    private int actionRowY;

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

    private enum View { RESULTS, HISTORY, FAVORITES }

    private enum Anchor { CENTER, ORIGIN }

    public SeedSearchScreen(Screen parent, PreviewContainer container,
                            @Nullable BiomesList.BiomeEntry biome,
                            @Nullable StructuresList.StructureEntry structure,
                            boolean autoStart) {
        super(WorldPreviewComponents.SEARCH_TITLE);
        this.parent = parent;
        this.container = container;
        this.initialBiome = biome;
        this.initialStructure = structure;
        this.autoStart = autoStart;
        this.applyBestOnComplete = autoStart;
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
        currentView = View.RESULTS;

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

        clearBiomesButton = Button.builder(WorldPreviewComponents.SEARCH_CLEAR_BIOME, btn -> biomePicker.clearSelection())
                .size(70, 20).build();

        // Structure criterion: opens the filterable structure picker screen
        // (None row + one row per structure with item icons).
        structureButton = Button.builder(structureButtonLabel(), ignored -> openStructureSelect())
                .size(160, 20).build();

        anchorCycle = CycleButton.builder(anchor -> switch (anchor) {
                case CENTER -> WorldPreviewComponents.SEARCH_ANCHOR_CENTER;
                case ORIGIN -> WorldPreviewComponents.SEARCH_ANCHOR_ORIGIN;
            }, Anchor.CENTER)
            .withValues(List.of(Anchor.CENTER, Anchor.ORIGIN))
            .create(0, 0, 160, 20, WorldPreviewComponents.SEARCH_ANCHOR, (btn, value) -> { });

        viewCycle = CycleButton.builder(view -> switch (view) {
                case RESULTS -> WorldPreviewComponents.SEARCH_VIEW_RESULTS;
                case HISTORY -> WorldPreviewComponents.SEARCH_VIEW_HISTORY;
                case FAVORITES -> WorldPreviewComponents.SEARCH_VIEW_FAVORITES;
            }, View.RESULTS)
            .withValues(List.of(View.RESULTS, View.HISTORY, View.FAVORITES))
            .create(0, 0, 110, 20, WorldPreviewComponents.SEARCH_VIEW, (btn, value) -> {
                currentView = value;
                refreshList();
            });

        minAreaSlider = new IntSlider(0, 0, 150, 20,
                WorldPreviewComponents.SEARCH_MIN_AREA, 0, 100, 1,
                container.worldPreview().cfg().searchMinAreaPercent);
        // Config wiring: searchMaxDistance (previously stored but unread)
        // pre-seeds the biome distance slider; 0 keeps the unlimited default.
        biomeDistanceSlider = new IntSlider(0, 0, 150, 20,
                WorldPreviewComponents.SEARCH_BIOME_DISTANCE, 0, 4096, 64,
                Math.max(0, container.worldPreview().cfg().searchMaxDistance));
        biomeDistanceSlider.setTooltip(Tooltip.create(WorldPreviewComponents.SEARCH_BIOME_DISTANCE_TOOLTIP));
        structureDistanceSlider = new IntSlider(0, 0, 150, 20,
                WorldPreviewComponents.SEARCH_STRUCTURE_DISTANCE, 128, 8192, 128, 512);
        attemptsSlider = new IntSlider(0, 0, 150, 20,
                WorldPreviewComponents.SEARCH_ATTEMPTS, 10, 500, 10, 100);
        hitsSlider = new IntSlider(0, 0, 150, 20,
                WorldPreviewComponents.SEARCH_HITS, 1, 10, 1, 1);

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
        addRenderableWidget(filterBox);
        addRenderableWidget(showCavesButton);
        addRenderableWidget(clearBiomesButton);
        addRenderableWidget(structureButton);
        addRenderableWidget(anchorCycle);
        addRenderableWidget(minAreaSlider);
        addRenderableWidget(biomeDistanceSlider);
        addRenderableWidget(structureDistanceSlider);
        addRenderableWidget(attemptsSlider);
        addRenderableWidget(hitsSlider);
        addRenderableWidget(startButton);
        addRenderableWidget(stopButton);
        addRenderableWidget(compareButton);
        addRenderableWidget(viewCycle);
        addRenderableWidget(resultsList);
        addRenderableWidget(backButton);

        layoutWidgets();
        updateControlState();
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
            minecraft.setScreen(parent);
        }
    }

    // ===== Layout =====

    private void layoutWidgets() {
        int left = 8;
        int top = 24;
        int leftWidth = Math.max(230, width / 2 - 16);

        // Bottom-up layout: the footer (action row + back button) is pinned to
        // the screen bottom so Start/Stop/Compare stay visible even on small
        // GUI scales; the biome picker and the results list absorb whatever
        // space remains above.
        actionRowY = height - 32;

        // Three-column grid metrics: three criteria buttons plus 2 gaps fill
        // the full width [left, width - left] exactly; the biome picker is
        // 1.5 button widths wide and the strip right of it holds the results
        // list.
        int gap = 6;
        int buttonW = (width - 2 * left - 2 * gap) / 3;
        int pickerWidth = (3 * buttonW) / 2;

        // Compacted top-left column: the filter box and the show-caves toggle
        // are narrowed to 112px; the clear button keeps its right edge and its
        // left edge moves flush to the filter box's right edge, and the view
        // switch (exactly the clear button's size) sits directly below it, so
        // the two buttons share the right half of the top rows.
        int topColW = 112;                            // filter + show-caves width (was 150)
        int clearRight = left + 224;                  // clear button's right edge is unchanged
        int clearW = clearRight - (left + topColW);   // == 112
        int rightX = Math.max(left + pickerWidth + gap, clearRight + gap);
        int rightWidth = (width - left) - rightX;

        // Filter row + cave toggle at the top of the left column; the
        // "Biomes: n" summary line is drawn by render() right above the
        // filter row.
        filterBox.setPosition(left, top + 12);
        filterBox.setWidth(topColW);
        clearBiomesButton.setPosition(left + topColW, top + 12);
        clearBiomesButton.setWidth(clearW);
        showCavesButton.setPosition(left, top + 34);
        showCavesButton.setWidth(topColW);
        viewCycle.setPosition(left + topColW, top + 34);
        viewCycle.setWidth(clearW);

        // Picker (1.5 button widths) fills the strip between the compacted
        // left column and the criteria grid; the results list sits beside it,
        // its top edge level with the clear button's top edge. Both lists end
        // 4px above the upper criteria row so they never touch the grid.
        int listTop = top + 56;
        int listBottom = actionRowY - 52;
        biomePicker.setX(left);
        biomePicker.setY(listTop);
        biomePicker.setWidth(pickerWidth);
        biomePicker.setHeight(Math.max(40, listBottom - listTop));
        resultsList.setX(rightX);
        resultsList.setY(top + 12);
        resultsList.setWidth(rightWidth);
        resultsList.setHeight(Math.max(40, listBottom - (top + 12)));

        // Criteria grid, three widgets per row across the full width: the
        // structure button, the anchor cycle and structure distance on the
        // upper row, min area + biome distance and attempts on the lower row.
        int upperRowY = actionRowY - 48;
        int lowerRowY = actionRowY - 24;
        structureButton.setPosition(left, upperRowY);
        structureButton.setWidth(buttonW);
        anchorCycle.setPosition(left + buttonW + gap, upperRowY);
        anchorCycle.setWidth(buttonW);
        structureDistanceSlider.setPosition(left + 2 * (buttonW + gap), upperRowY);
        structureDistanceSlider.setWidth(buttonW);
        minAreaSlider.setPosition(left, lowerRowY);
        minAreaSlider.setWidth(buttonW);
        biomeDistanceSlider.setPosition(left + buttonW + gap, lowerRowY);
        biomeDistanceSlider.setWidth(buttonW);
        attemptsSlider.setPosition(left + 2 * (buttonW + gap), lowerRowY);
        attemptsSlider.setWidth(buttonW);

        // Footer action row; the hits slider shares the row with the buttons.
        startButton.setPosition(left, actionRowY);
        stopButton.setPosition(left + 74, actionRowY);
        compareButton.setPosition(left + 150, actionRowY);
        hitsSlider.setPosition(left + 224, actionRowY);
        hitsSlider.setWidth(Math.max(90, leftWidth - 224));

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
        var viewport = container.currentSearchViewport();
        BlockPos anchor = anchorCycle.getValue() == Anchor.ORIGIN
                ? new BlockPos(0, viewport.center().getY(), 0)
                : viewport.center();

        List<SearchCriterion> criteria = new ArrayList<>();
        List<Identifier> selectedBiomes = biomePicker.getSelectedIds();
        if (!selectedBiomes.isEmpty()) {
            // The picker selection becomes a single ANY-of group criterion; a
            // distance cap of 0 disables the biome proximity requirement.
            criteria.add(new SearchCriterion.BiomeGroup(
                    selectedBiomes, minAreaSlider.currentValue(), biomeDistanceSlider.currentValue()));
        }
        if (structureId != null) {
            criteria.add(new SearchCriterion.Structure(
                    structureId, structureDistanceSlider.currentValue()));
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
                attemptsSlider.currentValue(),
                criteria,
                hitsSlider.currentValue()
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
            minecraft.setScreen(parent);
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
            minecraft.setScreen(new SeedComparisonScreen(this, container, preferredHits));
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
            minecraft.setScreen(new StructureSelectScreen(this, container, structureId));
        }
    }

    /** Applies a structure picked in {@link StructureSelectScreen} (null = None). */
    void structurePicked(@Nullable Identifier id, @Nullable String name) {
        structureId = id;
        structureName = name;
        structureButton.setMessage(structureButtonLabel());
    }

    private void onProgress(int attempts) {
        statusText = Component.translatable(
                "world_preview.search.progress", attempts, attemptsSlider.currentValue()).getString();
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
                + WorldPreviewComponents.SEARCH_BIOME_SHOW_CAVES.getString());
    }

    // ===== Result list =====

    private void refreshList() {
        List<SearchResultsList.Row> rows = switch (currentView) {
            case RESULTS -> hitRows;
            case HISTORY -> historyRows(container.worldPreview().seedSearchHistory().byRecency());
            case FAVORITES -> historyRows(container.worldPreview().seedSearchHistory().favorites());
        };
        resultsList.setRows(rows);
    }

    private List<SearchResultsList.Row> historyRows(List<SeedSearchHistory.Entry> entries) {
        List<SearchResultsList.Row> rows = new ArrayList<>();
        for (SeedSearchHistory.Entry entry : entries) {
            rows.add(resultsList.createRow(
                    entry.seed, entry.displayLabel(), 0, entry.favorite, true));
        }
        return rows;
    }

    @Override
    public void onApply(String seed) {
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

    private static long parseSeed(String seed) {
        try {
            return Long.parseLong(seed.trim());
        } catch (NumberFormatException e) {
            return seed.hashCode();
        }
    }

    @Override
    public void onToggleFavorite(String seed) {
        container.worldPreview().seedSearchHistory().toggleFavorite(seed);
        refreshList();
    }

    @Override
    public void onDelete(String seed) {
        container.worldPreview().seedSearchHistory().remove(seed);
        refreshList();
    }

    private void applySeed(String seed) {
        if (container.seedIsEditable()) {
            container.setSeed(seed);
            statusText = WorldPreviewComponents.SEARCH_APPLIED.getString() + seed;
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);

        // Criteria summary (left column): number of selected biomes
        int left = 8;
        int top = 24;
        graphics.drawString(font,
                Component.translatable("world_preview.search.biome.selected",
                        biomePicker.getSelectedCount()),
                left, top + 3, 0xFFCCCCCC);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (statusText != null && !statusText.isEmpty()) {
            // Draw in the strip below the footer action row so the status can
            // never cover the Start/Stop/Compare buttons or the back button.
            int statusY = height - 11;
            graphics.fill(0, actionRowY + 20, width, height, 0xAA000000);
            graphics.drawString(font, statusText, 8, statusY, 0xFFFFFF55);
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

    // ===== Widgets =====

    /** Continuous slider snapping to whole values within [min, max]. */
    private static final class IntSlider extends AbstractSliderButton {
        private final Component caption;
        private final int min;
        private final int max;
        private final int step;

        private IntSlider(int x, int y, int w, int h, Component caption, int min, int max, int step, int initialValue) {
            super(x, y, w, h, Component.empty(), toSlider(initialValue, min, max));
            this.caption = caption;
            this.min = min;
            this.max = max;
            this.step = Math.max(1, step);
            updateMessage();
        }

        private static double toSlider(int value, int min, int max) {
            return max <= min ? 0.0 : Math.min(1.0, Math.max(0.0, (value - min) / (double) (max - min)));
        }

        public int currentValue() {
            int span = max - min;
            int snapped = Math.round((float) value * span / step) * step;
            return Math.max(min, Math.min(max, min + snapped));
        }

        @Override
        protected void updateMessage() {
            setMessage(caption.copy().append(": " + String.format(Locale.ROOT, "%d", currentValue())));
        }

        @Override
        protected void applyValue() {
            updateMessage();
        }
    }
}
