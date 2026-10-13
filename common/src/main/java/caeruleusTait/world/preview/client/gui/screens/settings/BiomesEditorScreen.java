package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.client.gui.screens.PreviewContainer;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.client.gui.screens.settings.BiomesTab;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.SETTINGS_BIOMES_TITLE;

/**
 * Full-screen biomes color editor, reusing BiomesTab logic.
 * Changes stay in memory until the parent SettingsScreen saves.
 */
public class BiomesEditorScreen extends Screen {

    private static final ResourceLocation FOOTER_SEPARATOR = ResourceLocation.parse("textures/gui/footer_separator.png");

    private final Screen parent;
    private final PreviewContainer previewContainer;
    private final BiomesTab biomesTab;
    private final java.util.Map<ResourceLocation, BiomesList.BiomeEntry.State> initialStates = new java.util.HashMap<>();
    private GridLayout bottomButtons;
    /** True between a middle-press autoscroll start on the biome list and its release. */
    private boolean listPanActive;

    public BiomesEditorScreen(Screen parent, PreviewContainer previewContainer) {
        this(parent, previewContainer, null);
    }

    public BiomesEditorScreen(Screen parent, PreviewContainer previewContainer, net.minecraft.resources.ResourceLocation pendingDimension) {
        super(SETTINGS_BIOMES_TITLE);
        this.parent = parent;
        this.previewContainer = previewContainer;
        this.biomesTab = new BiomesTab(Minecraft.getInstance(), previewContainer, pendingDimension);
        for (BiomesList.BiomeEntry entry : previewContainer.allBiomes()) {
            initialStates.put(entry.entry().key().location(), entry.state());
        }
    }

    @Override
    protected void init() {
        biomesTab.visitChildren(this::addRenderableWidget);

        bottomButtons = new GridLayout().columnSpacing(10);
        GridLayout.RowHelper rowHelper = bottomButtons.createRowHelper(1);
        rowHelper.addChild(Button.builder(CommonComponents.GUI_BACK, button -> onClose()).build());
        bottomButtons.visitWidgets(widget -> {
            widget.setTabOrderGroup(1);
            addRenderableWidget(widget);
        });

        repositionElements();
    }

    @Override
    public void repositionElements() {
        if (bottomButtons == null) {
            return;
        }

        bottomButtons.arrangeElements();
        FrameLayout.centerInRectangle(bottomButtons, 0, this.height - 36, this.width, 36);

        int top = 24;
        int bottom = this.bottomButtons.getY();
        biomesTab.doLayout(new ScreenRectangle(0, top, this.width, Math.max(0, bottom - top)));
    }

    /**
     * 1.21.1's {@code Screen.render} runs the vanilla menu backdrop pass
     * (panorama + processBlurEffect + menu background) before the widgets.
     * This screen paints its own opaque backdrop and draws its title and
     * panel headers before calling {@code super.render}, which that pass
     * would smear; skip it entirely (the fork's backdrop fill already covers
     * the screen, matching the 1.20.1 output).
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Paint the settings screen's own opaque background: this screen
        // draws none by itself, so on versions without the 26.x screen stack
        // (which re-renders the screen below blurred) the previous screen's
        // last frame stays visible in the framebuffer behind the widgets.
        guiGraphics.fill(0, 0, this.width, this.height, SettingsTheme.BACKGROUND);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.blit(
                FOOTER_SEPARATOR,
                0,
                (int) Mth.roundToward(this.height - 36 - 2, 2),
                0.0F,
                0.0F,
                this.width,
                2,
                32,
                2
        );
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        // Keep edits in the parent settings session; SettingsScreen restores them on Cancel.
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // A middle press on the biome list starts a browser-style autoscroll
        // (the list ticks it from its render pass, so it keeps scrolling while
        // the mouse holds still). The screen only routes the release: vanilla's
        // container does not forward non-primary releases to the focused child.
        BiomesList list = biomesTab.biomesList();
        if (button == InputConstants.MOUSE_BUTTON_MIDDLE
                && list.visible
                && list.isMouseOver(mouseX, mouseY)
                && list.beginScrollPan(mouseY)) {
            listPanActive = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (listPanActive) {
            listPanActive = false;
            biomesTab.biomesList().endScrollPan();
            if (button == InputConstants.MOUSE_BUTTON_MIDDLE) {
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
}
