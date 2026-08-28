// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.backend.analysis.SearchCriterion;
import caeruleusTait.world.preview.backend.analysis.SeedSearchHistory;
import caeruleusTait.world.preview.backend.analysis.SeedSearchRequest;
import caeruleusTait.world.preview.backend.analysis.SeedSearchResult;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import caeruleusTait.world.preview.client.gui.widgets.lists.SearchResultsList;
import caeruleusTait.world.preview.client.gui.widgets.lists.StructuresList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
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
 * Advanced seed search screen: combines a biome criterion (pre-selected in the
 * biome list) with a structure criterion, configures search limits and shows
 * ranked results plus the persistent search history and favorites.
 */
public final class SeedSearchScreen extends Screen implements SearchResultsList.RowActions {

    private final Screen parent;
    private final PreviewContainer container;
    @Nullable private final BiomesList.BiomeEntry initialBiome;
    @Nullable private final StructuresList.StructureEntry initialStructure;
    private final boolean autoStart;

    @Nullable private BiomesList.BiomeEntry biome;
    @Nullable private Identifier structureId;
    @Nullable private String structureName;

    private AbstractWidget clearBiomeButton;
    private CycleButton<StructureOption> structureCycle;
    private CycleButton<Anchor> anchorCycle;
    private IntSlider minAreaSlider;
    private IntSlider structureDistanceSlider;
    private IntSlider attemptsSlider;
    private IntSlider hitsSlider;
    private Button startButton;
    private Button stopButton;
    private CycleButton<View> viewCycle;
    private SearchResultsList resultsList;

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
        this.biome = biome;
        this.structureId = structure != null ? structure.structureId() : null;
        this.structureName = structure != null ? structure.name() : null;
        this.initialBiome = biome;
        this.initialStructure = structure;
        this.autoStart = autoStart;
        this.applyBestOnComplete = autoStart;
    }

    // ===== Screen lifecycle =====

    @Override
    protected void init() {
        clearWidgets();
        biome = initialBiome;
        structureId = initialStructure != null ? initialStructure.structureId() : null;
        structureName = initialStructure != null ? initialStructure.name() : null;
        hitRows.clear();
        statusText = "";
        currentView = View.RESULTS;

        clearBiomeButton = Button.builder(WorldPreviewComponents.SEARCH_CLEAR_BIOME, btn -> {
            biome = null;
            btn.visible = false;
        }).size(70, 20).build();

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

        resultsList = new SearchResultsList(minecraft, this);

        addRenderableWidget(clearBiomeButton);
        addRenderableWidget(structureCycle);
        addRenderableWidget(anchorCycle);
        addRenderableWidget(minAreaSlider);
        addRenderableWidget(structureDistanceSlider);
        addRenderableWidget(attemptsSlider);
        addRenderableWidget(hitsSlider);
        addRenderableWidget(startButton);
        addRenderableWidget(stopButton);
        addRenderableWidget(viewCycle);
        addRenderableWidget(resultsList);

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

        clearBiomeButton.setPosition(left + leftWidth - 74, top);
        clearBiomeButton.setWidth(70);
        structureCycle.setPosition(left, top + 22);
        structureCycle.setWidth(Math.min(160, leftWidth));
        anchorCycle.setPosition(left + Math.min(164, leftWidth + 4), top + 22);
        anchorCycle.setWidth(Math.min(160, Math.max(100, width - (left + Math.min(164, leftWidth + 4)) - 8)));

        int sliderRow = top + 48;
        minAreaSlider.setPosition(left, sliderRow);
        structureDistanceSlider.setPosition(left + 156, sliderRow);
        attemptsSlider.setPosition(left, sliderRow + 24);
        hitsSlider.setPosition(left + 156, sliderRow + 24);

        startButton.setPosition(left, sliderRow + 52);
        stopButton.setPosition(left + 74, sliderRow + 52);

        viewCycle.setPosition(rightX, top);
        resultsList.setX(rightX);
        resultsList.setY(top + 24);
        resultsList.setWidth(rightWidth);
        resultsList.setHeight(Math.max(60, height - (top + 24) - 28));
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
        if (biome != null) {
            criteria.add(new SearchCriterion.Biome(
                    biome.entry().key().identifier(), minAreaSlider.currentValue(), 0));
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
        clearBiomeButton.visible = biome != null;
    }

    // ===== Criteria helpers =====

    private String criteriaLabel() {
        List<String> parts = new ArrayList<>();
        if (biome != null) {
            parts.add(biome.name());
        }
        if (structureName != null) {
            parts.add(structureName);
        }
        return String.join(" + ", parts);
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

        // Criteria summary (left column)
        int left = 8;
        int top = 24;
        String biomeText = biome == null
                ? WorldPreviewComponents.SEARCH_BIOME_NONE.getString()
                : biome.name();
        graphics.drawString(font,
                WorldPreviewComponents.SEARCH_BIOME.copy().append(": ").append(biomeText),
                left, top + 6, 0xFFCCCCCC);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (statusText != null && !statusText.isEmpty()) {
            int statusY = height - 20;
            graphics.fill(0, statusY - 4, width, statusY + font.lineHeight + 4, 0xAA000000);
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
