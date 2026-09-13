package caeruleusTait.world.preview.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Shared drawing vocabulary for the full-screen tool panels (seed search,
 * world analysis, ...): the dark panel with its 1px outline, the panel header
 * line, the active-tab selection line, empty-state hints, the bottom status
 * strip and the progress bar. The bodies were extracted verbatim from
 * {@code SeedSearchScreen} and {@code TerrainExportScreen} so every tool
 * screen renders in the same visual language; the screens keep all
 * layout/widget decisions (geometry, which tab is active, which hint to show).
 */
public final class PanelRenderer {

    /** Panel background fill (mostly opaque dark blue-gray). */
    public static final int PANEL_BG = 0xF01A1A24;
    /** 1px panel outline color. */
    public static final int PANEL_BORDER = 0xFF3A3A4A;
    /** Inner padding of the panels. */
    public static final int PANEL_PAD = 6;
    /** Accent green of the active tab's selection line. */
    public static final int ACCENT_GREEN = 0xFF55FF55;

    private PanelRenderer() {
    }

    /**
     * One panel: dark fill plus a 1px outline on all four sides; a no-op for
     * non-positive sizes. (Origin: {@code SeedSearchScreen#renderPanelBackground}.)
     */
    public static void panelBackground(GuiGraphics g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.fill(x, y, x + w, y + 1, PANEL_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        g.fill(x, y, x + 1, y + h, PANEL_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    /**
     * Panel header line: the title on the left and the optional gray text
     * (e.g. a count) right-aligned to the panel's inner right edge, both drawn
     * 5px below the given panel-top y. (Origin:
     * {@code SeedSearchScreen#renderPanels}.)
     */
    public static void panelHeader(GuiGraphics g, Font font, int x, int y, int w,
                                   Component title, @Nullable Component rightGray) {
        g.drawString(font, title, x + PANEL_PAD, y + 5, 0xFFFFFFFF);
        if (rightGray != null) {
            g.drawString(font, rightGray,
                    x + w - PANEL_PAD - font.width(rightGray), y + 5, 0xFF999999);
        }
    }

    /**
     * Draws the 2px accent-green selection line across the bottom of the
     * active tab button — vanilla buttons have no selected state, so the
     * caller passes the widget it considers active (or null for none).
     * (Origin: {@code SeedSearchScreen#renderTabSelection}.)
     */
    public static void tabSelectionLine(GuiGraphics g, @Nullable AbstractWidget activeTab) {
        if (activeTab == null) {
            return;
        }
        g.fill(activeTab.getX(), activeTab.getY() + activeTab.getHeight() - 2,
                activeTab.getX() + activeTab.getWidth(), activeTab.getY() + activeTab.getHeight(),
                ACCENT_GREEN);
    }

    /**
     * Centered gray hint for an empty list/view, drawn at the given point
     * (usually the center of the list area). (Origin:
     * {@code SeedSearchScreen#renderEmptyState}.)
     */
    public static void emptyHint(GuiGraphics g, Font font, int centerX, int centerY, Component text) {
        g.drawCenteredString(font, text, centerX, centerY, 0xFF808080);
    }

    /**
     * Bottom status strip: a translucent dark band starting 20px below the
     * footer action row's top (so the status can never cover the action
     * buttons) and reaching down to the bottom of the gui-scaled screen, with
     * the yellow status text 11px above that bottom. Skipped for a null or
     * empty text. (Origin: {@code SeedSearchScreen#renderStatusBar}.)
     */
    public static void statusBar(GuiGraphics g, Font font, int screenWidth, int actionRowY,
                                 @Nullable Component text) {
        if (text == null || text.getString().isEmpty()) {
            return;
        }
        int statusY = g.guiHeight() - 11;
        g.fill(0, actionRowY + 20, screenWidth, g.guiHeight(), 0xAA000000);
        g.drawString(font, text, 8, statusY, 0xFFFFFF55);
    }

    /**
     * Progress bar: a 12px tall dark slot filled from the left according to
     * {@code pct} (0-100) in the given color. (Origin:
     * {@code TerrainExportScreen#renderProgressBar}, whose private copy stays
     * in place for now.)
     */
    public static void progressBar(GuiGraphics g, int x, int y, int w, double pct, int color) {
        g.fill(x, y, x + w, y + 12, 0xFF333344);
        int fillW = (int) (w * pct / 100.0);
        if (fillW > 0) {
            g.fill(x, y, x + fillW, y + 12, color);
        }
    }
}
