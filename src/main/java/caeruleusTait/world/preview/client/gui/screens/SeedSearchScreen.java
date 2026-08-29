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
    private CycleButton<StructureOption> structureCycle;
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
    private String statusText = "";
    private boolean searching = false;
    /** When the screen was opened with a quick search (right-click), the best hit is applied automatically. */
    private boolean applyBestOnComplete;
    private View currentView = View.RESULTS;

    private enum View { RESULTS, HISTORY, FAVORITES }

    private enum Anchor { CENTER, ORIGIN }

    /** One selectable structure criterion: "no structure" or a concrete structure. */
    private record StructureOption(@Nullable Identifier id, String label) {
    }

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
        structureId = initialStructure != null ? initialStructure.structureId() : null;
        structureName = initialStructure != null ? initialStructure.name() : null;
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

        List<StructureOption> structureOptions = new ArrayList<>();
        StructureOption noneOption = new StructureOption(
                null, WorldPreviewComponents.SEARCH_STRUCTURE_NONE.getString());
        structureOptions.add(noneOption);
        var entries = container.structureEntries();
        if (entries != null) {
            for (StructuresList.StructureEntry entry : entries) {
                structureOptions.add(new StructureOption(entry.structureId(), entry.name()));
            }
        }
        StructureOption initialOption = structureId == null
                ? noneOption
                : structureOptions.stream()
                        .filter(option -> structureId.equals(option.id()))
                        .findFirst()
                        .orElse(noneOption);
        structureCycle = CycleButton.builder(option -> Component.literal(option.label()), initialOption)
                .withValues(structureOptions)
                .create(0, 0, 160, 20, WorldPreviewComponents.SEARCH_STRUCTURE, (btn, value) -> {
                    structureId = value.id();
                    structureName = value.id() == null ? null : value.label();
                });

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
        biomeDistanceSlider = new IntSlider(0, 0, 150, 20,
                WorldPreviewComponents.SEARCH_BIOME_DISTANCE, 0, 4096, 64, 0);
        biomeDistanceSlider.setTooltip(Tooltip.create(WorldPreviewComponents.SEARCH_BIOME_DISTANCE_TOOLTIP));
        structureDistanceSlider = new IntSlider(0, 0, 150, 20,
                WorldPreviewComponents.SEARCH_STRUCTURE_DISTANCE, 128, 8192, 128, 512);
        attemptsSlider = new IntSlider(0, 0, 150, 20,
                WorldPreviewComponents.SEARCH_ATTEMPTS, 10, 500, 10, 100);
        hitsSlider = new IntSlider(0, 0, 150, 20,
                WorldPreviewComponents.SEARCH_HITS, 1, 10, 1, 5);

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
        addRenderableWidget(structureCycle);
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
        container.cancelSeedSearch();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    // ===== Layout =====

    private void layoutWidgets() {
        int left = 8;
        int top = 24;
        int leftWidth = Math.max(230, width / 2 - 16);
        int rightX = width / 2 + 4;
        int rightWidth = Math.max(200, width / 2 - 12);

        // Bottom-up layout: the footer (action row + back button) is pinned to
        // the screen bottom so Start/Stop/Compare stay visible even on small
        // GUI scales; the biome picker absorbs whatever space remains above.
        actionRowY = height - 32;

        // Filter row + cave toggle at the top of the left column; the
        // "Biomes: n" summary line is drawn by render() right above the
        // filter row.
        filterBox.setPosition(left, top + 12);
        filterBox.setWidth(150);
        clearBiomesButton.setPosition(left + 154, top + 12);
        showCavesButton.setPosition(left, top + 34);

        // Picker fills the gap between the cave toggle and the cycles row.
        int pickerTop = top + 56;
        int pickerBottom = actionRowY - 76;
        biomePicker.setX(left);
        biomePicker.setY(pickerTop);
        biomePicker.setWidth(leftWidth);
        biomePicker.setHeight(Math.max(40, pickerBottom - pickerTop));

        // Cycles row directly above the two slider rows.
        int cyclesRowY = actionRowY - 72;
        structureCycle.setPosition(left, cyclesRowY);
        structureCycle.setWidth(Math.min(160, leftWidth));
        anchorCycle.setPosition(left + Math.min(164, leftWidth + 4), cyclesRowY);
        anchorCycle.setWidth(Math.min(160, Math.max(100, width - (left + Math.min(164, leftWidth + 4)) - 8)));

        // Two slider columns above the action row: min area + biome distance,
        // then structure distance + attempts.
        minAreaSlider.setPosition(left, actionRowY - 24);
        biomeDistanceSlider.setPosition(left + 156, actionRowY - 24);
        structureDistanceSlider.setPosition(left, actionRowY - 48);
        attemptsSlider.setPosition(left + 156, actionRowY - 48);

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

        viewCycle.setPosition(rightX, top);
        resultsList.setX(rightX);
        resultsList.setY(top + 24);
        resultsList.setWidth(rightWidth);
        // End the results above the footer so they never cover the back button.
        resultsList.setHeight(Math.max(60, (height - 32) - (top + 24) - 4));
    }

    // ===== Search control =====

    private void startSearch() {
        if (container.isSeedSearchRunning()) {
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

        boolean started = container.startSeedSearch(request, this::onComplete, this::onProgress);
        if (started) {
            searching = true;
            statusText = Component.translatable(
                    "world_preview.search.progress", 0, request.maxAttempts()).getString();
            updateControlState();
        } else {
            statusText = WorldPreviewComponents.SEARCH_ERROR.getString();
        }
    }

    private void stopSearch() {
        container.cancelSeedSearch();
    }

    /** Opens the seed comparison screen; back navigation returns to this instance. */
    private void openComparison() {
        if (minecraft != null) {
            minecraft.setScreen(new SeedComparisonScreen(this, container));
        }
    }

    private void onProgress(int attempts) {
        statusText = Component.translatable(
                "world_preview.search.progress", attempts, attemptsSlider.currentValue()).getString();
    }

    private void onComplete(SeedSearchResult result) {
        searching = false;
        hitRows.clear();

        switch (result) {
            case SeedSearchResult.Hit hit -> {
                hitRows.add(resultsList.createRow(
                        String.valueOf(hit.seed()), criteriaLabel(), hit.score(), false, false));
                statusText = WorldPreviewComponents.SEARCH_FOUND.getString() + hit.seed();
            }
            case SeedSearchResult.Multiple multiple -> {
                for (SeedSearchResult.Ranked ranked : multiple.hits()) {
                    hitRows.add(resultsList.createRow(
                            String.valueOf(ranked.seed()), criteriaLabel(), ranked.score(), false, false));
                }
                if (multiple.isEmpty()) {
                    statusText = WorldPreviewComponents.SEARCH_NOT_FOUND.getString();
                } else {
                    statusText = WorldPreviewComponents.SEARCH_FOUND.getString() + multiple.hits().get(0).seed();
                }
            }
            case SeedSearchResult.Miss ignored ->
                    statusText = WorldPreviewComponents.SEARCH_NOT_FOUND.getString();
            default -> statusText = WorldPreviewComponents.SEARCH_STOPPED.getString();
        }

        // Apply the best hit automatically when the screen was opened via a
        // quick search (right-click on a structure entry).
        String bestSeed = null;
        if (!hitRows.isEmpty()) {
            bestSeed = hitRows.get(0).seed;
        }
        if (applyBestOnComplete && bestSeed != null) {
            applySeed(bestSeed);
        }

        // Remember the hits so they can be re-applied later.
        var history = container.worldPreview().seedSearchHistory();
        for (SearchResultsList.Row row : hitRows) {
            history.record(row.seed, criteriaLabel());
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
        applySeed(seed);
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
