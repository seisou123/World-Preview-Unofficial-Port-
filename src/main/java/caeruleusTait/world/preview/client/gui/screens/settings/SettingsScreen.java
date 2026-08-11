package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.color.PreviewMappingData;
import caeruleusTait.world.preview.client.gui.screens.PreviewContainer;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import caeruleusTait.world.preview.domain.ui.PageRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.*;

/**
 * Main settings screen with sidebar navigation and category pages.
 * Uses pending state copies so changes are only applied on Done.
 */
public class SettingsScreen extends Screen {

    private final Screen parent;
    private final PreviewContainer previewContainer;
    private final WorldPreviewConfig pendingConfig;
    private final RenderSettings pendingRender;
    private final Map<Identifier, BiomesList.BiomeEntry.State> initialBiomeStates = new java.util.HashMap<>();

    private SidebarPanel sidebar;
    private AbstractSettingsPage currentPage;
    private final AbstractSettingsPage[] pages = new AbstractSettingsPage[6];
    private final PageRegistry pageRegistry = new PageRegistry();

    private Button doneButton;
    private Button cancelButton;
    private Button resetButton;
    private SettingsContentContainer contentContainer;

    public SettingsScreen(Screen parent, PreviewContainer previewContainer) {
        super(SETTINGS_TITLE);
        this.parent = parent;
        this.previewContainer = previewContainer;

        // Create pending state copies
        WorldPreviewConfig original = WorldPreview.get().cfg();
        this.pendingConfig = copyConfig(original);

        RenderSettings originalRs = WorldPreview.get().renderSettings();
        this.pendingRender = new RenderSettings();
        copyRenderSettings(originalRs, this.pendingRender);
        for (BiomesList.BiomeEntry entry : previewContainer.allBiomes()) {
            initialBiomeStates.put(entry.entry().key().identifier(), entry.state());
        }
    }

    private WorldPreviewConfig copyConfig(WorldPreviewConfig src) {
        return src.copy();
    }

    private void copyRenderSettings(RenderSettings src, RenderSettings dst) {
        dst.apply(src);
    }

    private void applyConfig(WorldPreviewConfig src, WorldPreviewConfig dst) {
        dst.apply(src);
    }

    private void applyRenderSettings(RenderSettings src, RenderSettings dst) {
        dst.apply(src);
    }

    private static final int PANEL_MARGIN = 8;
    private static final int HEADER_HEIGHT = 28;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int FOOTER_ROW_HEIGHT = 28;
    private static final int STACKED_FOOTER_HEIGHT = BUTTON_HEIGHT * 3 + BUTTON_GAP * 2 + 8;
    private static final int CONTENT_GAP = 4;

    private int panelLeft() {
        return Math.max(0, (width - panelWidth()) / 2);
    }

    private int panelTop() {
        return Math.max(0, (height - panelHeight()) / 2);
    }

    private int panelWidth() {
        return Math.max(0, Math.min(800, width - PANEL_MARGIN * 2));
    }

    private int panelHeight() {
        return Math.max(0, Math.min(600, height - PANEL_MARGIN * 2));
    }

    /** The title and all page widgets are laid out relative to this panel header. */
    private int headerHeight() {
        return Math.min(HEADER_HEIGHT, panelHeight());
    }

    private int bodyTop() {
        return Math.min(panelTop() + headerHeight(), panelTop() + panelHeight());
    }

    private Component resetLabel() {
        return Component.translatable("world_preview.settings.reset_defaults");
    }

    private int preferredButtonWidth(Component label) {
        return Math.max(44, font.width(label.getVisualOrderText()) + 16);
    }

    private int footerPadding() {
        return Math.min(8, panelWidth() / 2);
    }

    private int footerAvailableWidth() {
        return Math.max(0, panelWidth() - footerPadding() * 2);
    }

    private boolean stackedFooter() {
        Component reset = resetLabel();
        int preferredWidth = preferredButtonWidth(reset)
                + preferredButtonWidth(net.minecraft.network.chat.CommonComponents.GUI_CANCEL)
                + preferredButtonWidth(net.minecraft.network.chat.CommonComponents.GUI_DONE)
                + BUTTON_GAP * 2;
        return footerAvailableWidth() < preferredWidth
                && panelHeight() >= headerHeight() + STACKED_FOOTER_HEIGHT;
    }

    private int footerHeight() {
        int preferredHeight = stackedFooter() ? STACKED_FOOTER_HEIGHT : FOOTER_ROW_HEIGHT;
        return Math.min(panelHeight(), preferredHeight);
    }

    private int footerTop() {
        return Math.max(bodyTop(), panelTop() + panelHeight() - footerHeight());
    }

    private int bodyHeight() {
        return Math.max(0, footerTop() - bodyTop());
    }

    private boolean stackedSidebar() {
        return panelWidth() < SettingsTheme.SIDEBAR_WIDTH + 120;
    }

    private int sidebarWidth() {
        return Math.min(SettingsTheme.SIDEBAR_WIDTH, panelWidth());
    }

    private int sidebarCategoryHeight() {
        int bodyHeight = bodyHeight();
        if (bodyHeight <= 0) {
            return 0;
        }
        int maxHeight = SettingsTheme.CATEGORY_HEIGHT;
        return Math.max(1, Math.min(maxHeight, bodyHeight / SidebarPanel.CATEGORIES.length));
    }

    private int sidebarUsedHeight() {
        return sidebarCategoryHeight() * SidebarPanel.CATEGORIES.length;
    }

    private int contentLeft() {
        int left = panelLeft();
        if (stackedSidebar()) {
            return left + Math.min(CONTENT_GAP, panelWidth() / 2);
        }
        return left + sidebarWidth() + Math.min(CONTENT_GAP,
                Math.max(0, panelWidth() - sidebarWidth()));
    }

    private int contentTop() {
        if (!stackedSidebar()) {
            return bodyTop();
        }
        int sidebarHeight = Math.min(bodyHeight(), sidebarUsedHeight());
        int remaining = Math.max(0, bodyHeight() - sidebarHeight);
        return bodyTop() + sidebarHeight + Math.min(CONTENT_GAP, remaining);
    }

    private int contentRight() {
        return panelLeft() + panelWidth() - Math.min(CONTENT_GAP, panelWidth() / 2);
    }

    private int contentWidth() {
        return Math.max(0, contentRight() - contentLeft());
    }

    private int contentHeight() {
        return Math.max(0, footerTop() - contentTop());
    }

    private void layoutSidebarButtons() {
        if (sidebar == null) {
            return;
        }
        int categoryHeight = sidebarCategoryHeight();
        int x = panelLeft();
        int y = bodyTop();
        int availableHeight = bodyHeight();
        for (Button button : sidebar.buttons()) {
            int buttonHeight = Math.max(0, Math.min(categoryHeight - 2, availableHeight));
            button.setX(x);
            button.setY(y);
            button.setWidth(sidebarWidth());
            button.setHeight(buttonHeight);
            y += categoryHeight;
            availableHeight = Math.max(0, availableHeight - categoryHeight);
        }
    }

    @Override
    protected void init() {
        int selectedCategory = sidebar == null ? 0 : sidebar.selectedIndex();
        int left = panelLeft();
        int top = panelTop();

        // Sidebar
        sidebar = new SidebarPanel(
                left,
                top,
                SettingsTheme.SIDEBAR_WIDTH,
                SettingsTheme.CATEGORY_HEIGHT * SidebarPanel.CATEGORIES.length,
                this::onCategoryChange
        );

        sidebar.buttons().forEach(this::addRenderableWidget);

        // Initialize pages only once. Screen.init is also called on resize, and recreating
        // pages there would discard pending widget state.
        if (pages[0] == null) {
            initPages();
        }

        int contentLeft = left + SettingsTheme.SIDEBAR_WIDTH + 4;
        int contentTop = top;
        int contentWidth = Math.max(80, panelWidth() - SettingsTheme.SIDEBAR_WIDTH - 8);
        int contentHeight = Math.max(40, panelHeight() - SettingsTheme.BOTTOM_HEIGHT - 4);
        contentContainer = new SettingsContentContainer(contentLeft, contentTop, contentWidth, contentHeight);
        addRenderableWidget(contentContainer);

        // Rebuild the previously selected page after a resize, otherwise select the first
        // category only on the initial screen construction.
        sidebar.select(Math.max(0, Math.min(selectedCategory, SidebarPanel.CATEGORIES.length - 1)));

        // Bottom buttons
        int bottomY = panelTop() + panelHeight() - SettingsTheme.BOTTOM_HEIGHT + 4;
        resetButton = Button.builder(
                Component.translatable("world_preview.settings.reset_defaults"),
                b -> {
                    pageRegistry.resetAll();
                    rebuildCurrentPage();
                }
        ).bounds(panelLeft() + 12, bottomY, 120, 20).build();

        cancelButton = Button.builder(
                net.minecraft.network.chat.CommonComponents.GUI_CANCEL,
                b -> onClose()
        ).bounds(panelLeft() + panelWidth() / 2 - 50, bottomY, 100, 20).build();

        doneButton = Button.builder(
                net.minecraft.network.chat.CommonComponents.GUI_DONE,
                b -> onDone()
        ).bounds(panelLeft() + panelWidth() - 112, bottomY, 100, 20).build();

        addRenderableWidget(resetButton);
        addRenderableWidget(cancelButton);
        addRenderableWidget(doneButton);
    }

    private void initPages() {
        pages[0] = new GeneralSettingsPage(pendingConfig, previewContainer);
        pages[1] = new CacheSettingsPage(pendingConfig, previewContainer);
        pages[2] = new ResolutionSettingsPage(pendingRender);
        pages[3] = new HeightmapSettingsPage(pendingConfig, previewContainer);
        pages[4] = new DimensionSettingsPage(pendingRender, previewContainer);
        pages[5] = new BiomesSettingsPage(previewContainer, this, pendingRender.dimension);

        // Register all pages in the domain-level PageRegistry
        pageRegistry.clear();
        for (AbstractSettingsPage page : pages) {
            pageRegistry.register(page.category(), page);
        }
    }

    private void onCategoryChange(int index) {
        rebuildCurrentPage();
    }

    private void rebuildCurrentPage() {
        currentPage = pages[sidebar.selectedIndex()];

        // Keep the content area inside the panel and adapt to small windows.
        int panelLeft = panelLeft();
        int panelTop = panelTop();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        int contentLeft = panelLeft + SettingsTheme.SIDEBAR_WIDTH + 4;
        int contentTop = panelTop;
        int contentWidth = Math.max(80, panelWidth - SettingsTheme.SIDEBAR_WIDTH - 8);
        int contentHeight = Math.max(40, panelHeight - SettingsTheme.BOTTOM_HEIGHT - 4);

        currentPage.build(new ScreenRectangle(contentLeft, contentTop, contentWidth, contentHeight),
                previewContainer);
        contentContainer.setViewportTop(contentTop);
        contentContainer.setWidgets(currentPage.widgets());
    }

    private void onDone() {
        if (!pageRegistry.validateAll().isEmpty()) {
            return;
        }

        // Materialize widget values into the detached candidates, then normalize and validate
        // before touching live state or any files.
        for (AbstractSettingsPage page : pages) {
            page.save();
        }
        WorldPreviewConfig configCandidate = pendingConfig.normalized();
        RenderSettings renderCandidate = pendingRender.normalized();
        configCandidate.validate();
        renderCandidate.validate();

        WorldPreview worldPreview = WorldPreview.get();
        worldPreview.saveConfig(configCandidate, renderCandidate, collectConfigBiomeColors());
        applyConfig(configCandidate, worldPreview.cfg());
        applyRenderSettings(renderCandidate, worldPreview.renderSettings());
        previewContainer.patchColorData();
        previewContainer.resetTabs();

        minecraft.setScreen(parent);
    }

    private Map<Identifier, PreviewMappingData.ColorEntry> collectConfigBiomeColors() {
        return previewContainer.allBiomes().stream()
                .filter(x -> x.dataSource() == PreviewData.DataSource.CONFIG)
                .collect(Collectors.toMap(
                        x -> x.entry().key().identifier(),
                        x -> new PreviewMappingData.ColorEntry(
                                PreviewData.DataSource.CONFIG,
                                x.color(),
                                x.isCave(),
                                x.name()
                        )
                ));
    }

    @Override
    public void tick() {
        super.tick();
        if (currentPage != null) {
            currentPage.tick();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Draw the background directly to avoid triggering the screen blur twice.
        graphics.fill(0, 0, width, height, SettingsTheme.BACKGROUND);

        // Panel backgrounds
        renderPanelBackgrounds(graphics);

        sidebar.renderBackground(graphics, mouseX, mouseY, delta);

        super.render(graphics, mouseX, mouseY, delta);

        // Title
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 162, SettingsTheme.TEXT);
    }

    private void renderPanelBackgrounds(GuiGraphics graphics) {
        int left = panelLeft();
        int top = panelTop();
        int right = left + panelWidth();
        int bottom = top + panelHeight();

        // Main panel background
        graphics.fill(left, top, right, bottom, SettingsTheme.PANEL_BG);

        // Border
        int borderColor = SettingsTheme.BORDER;
        graphics.fill(left - 1, top - 1, right + 1, top, borderColor);
        graphics.fill(left - 1, bottom, right + 1, bottom + 1, borderColor);
        graphics.fill(left - 1, top, left, bottom, borderColor);
        graphics.fill(right, top, right + 1, bottom, borderColor);

        // Content area background
        int contentLeft = left + SettingsTheme.SIDEBAR_WIDTH + 4;
        graphics.fill(contentLeft, top, right - 1, bottom - SettingsTheme.BOTTOM_HEIGHT - 2,
                SettingsTheme.CONTENT_BG);
    }

    @Override
    public void onClose() {
        // Discard pending configuration and restore any biome edits made in the editor.
        for (BiomesList.BiomeEntry entry : previewContainer.allBiomes()) {
            entry.restore(initialBiomeStates.get(entry.entry().key().identifier()));
        }
        previewContainer.patchColorData();
        minecraft.setScreen(parent);
    }
}
