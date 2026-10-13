// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.client.WorldPreviewComponents;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * "More search options" sub-page of the seed search screen (vanilla
 * "More World Options" pattern): the six advanced options that used to crowd
 * the main screen, laid out as one compact column where every row pairs a
 * fixed-width gray label on the left with a 20px-high control on the right.
 * Every responder writes straight into the shared {@link SeedSearchOptions}
 * state, so there is no explicit save step; Done/Esc just returns to the
 * parent screen.
 */
public final class SeedSearchOptionsScreen extends Screen {

    /** Y of the centered screen title, matching render(). */
    private static final int TITLE_Y = 8;
    /** Top of the first option row, one title area below the title line. */
    private static final int ROWS_TOP = TITLE_Y + 24;
    /** Row geometry: 20px-high controls with a 4px gap (2px when cramped). */
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int ROW_GAP_CRAMPED = 2;
    /** Width of the gray label column left of every control. */
    private static final int LABEL_WIDTH = 150;

    private final SeedSearchScreen parentScreen;
    private final PreviewContainer container;
    private final SeedSearchOptions options;

    private CycleButton<SeedSearchOptions.Anchor> anchorButton;
    private IntSlider minAreaSlider;
    private IntSlider biomeDistanceSlider;
    private IntSlider structureDistanceSlider;
    private IntSlider attemptsSlider;
    private IntSlider hitsSlider;

    /** The centered option column; row labels and controls align to it. */
    private int columnX;
    private int columnW;

    public SeedSearchOptionsScreen(SeedSearchScreen parentScreen, PreviewContainer container, SeedSearchOptions options) {
        super(WorldPreviewComponents.SEARCH_OPTIONS_TITLE);
        this.parentScreen = parentScreen;
        this.container = container;
        this.options = options;
    }

    @Override
    protected void init() {
        clearWidgets();

        columnW = Math.min(360, width - 40);
        columnX = (width - columnW) / 2;
        // Fixed gray label column on the left of every row; the control
        // takes the flexible remainder of the column.
        int controlX = columnX + LABEL_WIDTH + 4;
        int controlW = Math.max(40, columnW - LABEL_WIDTH - 4);

        // Anchor criterion (map center / world origin); the responder writes
        // straight into the shared session state.
        anchorButton = CycleButton.builder(anchor -> switch (anchor) {
                case CENTER -> WorldPreviewComponents.SEARCH_ANCHOR_CENTER;
                case ORIGIN -> WorldPreviewComponents.SEARCH_ANCHOR_ORIGIN;
            }, options.anchor)
            .withValues(List.of(SeedSearchOptions.Anchor.CENTER, SeedSearchOptions.Anchor.ORIGIN))
            .create(0, 0, controlW, ROW_HEIGHT, WorldPreviewComponents.SEARCH_ANCHOR, (btn, value) -> options.anchor = value);

        minAreaSlider = new IntSlider(0, 0, controlW, ROW_HEIGHT,
                WorldPreviewComponents.SEARCH_MIN_AREA, 0, 100, 1, options.minAreaPercent);
        minAreaSlider.setResponder(value -> options.minAreaPercent = value);

        biomeDistanceSlider = new IntSlider(0, 0, controlW, ROW_HEIGHT,
                WorldPreviewComponents.SEARCH_BIOME_DISTANCE, 0, 4096, 64, options.biomeMaxDistance);
        biomeDistanceSlider.setTooltip(Tooltip.create(WorldPreviewComponents.SEARCH_BIOME_DISTANCE_TOOLTIP));
        biomeDistanceSlider.setResponder(value -> options.biomeMaxDistance = value);

        structureDistanceSlider = new IntSlider(0, 0, controlW, ROW_HEIGHT,
                WorldPreviewComponents.SEARCH_STRUCTURE_DISTANCE, 128, 8192, 128, options.structureDistance);
        structureDistanceSlider.setResponder(value -> options.structureDistance = value);

        attemptsSlider = new IntSlider(0, 0, controlW, ROW_HEIGHT,
                WorldPreviewComponents.SEARCH_ATTEMPTS, 10, 500, 10, options.attempts);
        attemptsSlider.setResponder(value -> options.attempts = value);

        hitsSlider = new IntSlider(0, 0, controlW, ROW_HEIGHT,
                WorldPreviewComponents.SEARCH_HITS, 1, 10, 1, options.hits);
        hitsSlider.setResponder(value -> options.hits = value);

        addRenderableWidget(anchorButton);
        addRenderableWidget(minAreaSlider);
        addRenderableWidget(biomeDistanceSlider);
        addRenderableWidget(structureDistanceSlider);
        addRenderableWidget(attemptsSlider);
        addRenderableWidget(hitsSlider);

        // The Done button is pinned to the bottom center and must never
        // overlap the last row: the inter-row gap shrinks on small windows
        // so all six rows fit between the title area and the button (any
        // GUI height >= 240 fits without shrinking).
        int doneY = height - 26;
        int rowGap = Math.max(ROW_GAP_CRAMPED,
                Math.min(ROW_GAP, (doneY - 4 - ROWS_TOP - 6 * ROW_HEIGHT) / 5));

        int y = ROWS_TOP;
        y = layoutRow(anchorButton, controlX, controlW, y, rowGap);
        y = layoutRow(minAreaSlider, controlX, controlW, y, rowGap);
        y = layoutRow(biomeDistanceSlider, controlX, controlW, y, rowGap);
        y = layoutRow(structureDistanceSlider, controlX, controlW, y, rowGap);
        y = layoutRow(attemptsSlider, controlX, controlW, y, rowGap);
        layoutRow(hitsSlider, controlX, controlW, y, rowGap);

        int doneW = 150;
        Button doneButton = Button.builder(CommonComponents.GUI_DONE, ignored -> onClose())
                .size(doneW, ROW_HEIGHT)
                .build();
        doneButton.setX((width - doneW) / 2);
        doneButton.setY(doneY);
        addRenderableWidget(doneButton);
    }

    /** Positions one row (label left, control on the right column); returns the next row's Y. */
    private int layoutRow(AbstractWidget control, int controlX, int controlW, int y, int rowGap) {
        control.setPosition(controlX, y);
        control.setWidth(controlW);
        return y + ROW_HEIGHT + rowGap;
    }

    @Override
    public void onClose() {
        // Return to the seed search screen; the options are already applied.
        if (minecraft != null) {
            minecraft.setScreen(parentScreen);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.drawCenteredString(font, title, width / 2, TITLE_Y, 0xFFFFFFFF);

        // Small gray labels left of each control, vertically centered in
        // their row.
        drawRowLabel(graphics, WorldPreviewComponents.SEARCH_ANCHOR, anchorButton);
        drawRowLabel(graphics, WorldPreviewComponents.SEARCH_MIN_AREA, minAreaSlider);
        drawRowLabel(graphics, WorldPreviewComponents.SEARCH_BIOME_DISTANCE, biomeDistanceSlider);
        drawRowLabel(graphics, WorldPreviewComponents.SEARCH_STRUCTURE_DISTANCE, structureDistanceSlider);
        drawRowLabel(graphics, WorldPreviewComponents.SEARCH_ATTEMPTS, attemptsSlider);
        drawRowLabel(graphics, WorldPreviewComponents.SEARCH_HITS, hitsSlider);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Draws the gray row label left of the control, vertically centered. */
    private void drawRowLabel(GuiGraphics graphics, Component label, AbstractWidget control) {
        graphics.drawString(font, label, columnX,
                control.getY() + (control.getHeight() - font.lineHeight) / 2, 0xFFAAAAAA);
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
