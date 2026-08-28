package caeruleusTait.world.preview.client.gui.widgets;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Semi-transparent button for the switch (Biomes / Structures / Seeds) rail icons.
 *
 * <p>Monochrome gray-to-black ramp on a translucent dark base, so the rail
 * stays readable over any map colors (only the background is translucent —
 * text never drops below 82% opacity).  All three rail buttons share the
 * exact same background/text ramp — the selected tab is marked ONLY by a
 * light gray outline and full-white text, never by a darker fill:
 * <ul>
 *   <li>Idle:     background 57% black, text 85% white (identical for all)</li>
 *   <li>Hover:    background 76% black, text 95% white + white outline</li>
 *   <li>Selected: idle background + light gray 55% outline + 100% white text</li>
 *   <li>Disabled: 40% white text, no outline</li>
 * </ul>
 * Selected state is driven by {@link #setSelected(boolean)} (wired from
 * PreviewContainerTabManager); hover/pressed use the vanilla flags.
 */
public class TranslucentButton extends Button {

    private final Font font;
    private final int minWidth;

    // --- Monochrome ramp (gray -> black) ---
    private static final int BG_IDLE           = 0x92000000;  // 57% black
    private static final int BG_HOVER          = 0xC2000000;  // 76% black
    private static final int BORDER_HOVER      = 0xFFFFFFFF;  // white outline
    private static final int BORDER_SELECTED   = 0x8CCCCCCC;  // 55% light gray outline
    private static final int TEXT_IDLE         = 0xD9FFFFFF;  // 85% white
    private static final int TEXT_HOVER        = 0xF2FFFFFF;  // 95% white
    private static final int TEXT_SELECTED     = 0xFFFFFFFF;  // 100% white
    private static final int TEXT_DISABLED     = 0x66FFFFFF;  // 40% white

    /** True when this button is the current tab (set by PreviewContainerTabManager). */
    private boolean selected;

    public TranslucentButton(Font font, int x, int y, int minWidth, int height,
                              Component text, OnPress onPress) {
        super(x, y, Math.max(minWidth, font.width(text) + 16), height, text, onPress, DEFAULT_NARRATION);
        this.font = font;
        this.minWidth = minWidth;
    }

    public void updateAutoWidth() {
        int textWidth = font.width(getMessage());
        int newWidth = Math.max(minWidth, textWidth + 16);
        setWidth(newWidth);
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    /**
     * Fully self-drawn: never calls renderDefaultSprite().  The vanilla
     * pipeline (Button.Plain etc.) draws the native button sprite here, which
     * uses a different texture for active=false than for active=true — that
     * was why the selected rail button looked different from the other two.
     * Our fill/outline/text ramp is the only thing drawn.
     */
    protected void extractContents(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        // NOTE: the tab manager expresses "selected tab" by setting
        // active=false on that button, so `active` cannot be read as
        // "disabled".  Hover applies to the selected button too; true
        // disabled = active=false AND not selected (structures page off).
        boolean hov = isHoveredOrFocused() && (this.active || this.selected);

        // Background: identical for all three buttons — selection is NOT
        // expressed through darkness (the selected tab used to render darker,
        // which read as inconsistent).  Selected keeps the idle background and
        // differs only by outline + brighter text.
        int bg = hov ? BG_HOVER : BG_IDLE;
        gg.fill(x, y, x + w, y + h, bg);

        if (hov) {
            outline(gg, x, y, w, h, BORDER_HOVER);
        }
        if (selected) {
            outline(gg, x, y, w, h, BORDER_SELECTED);
        }

        // Text: idle 85% white for everyone; the selected tab is pure white
        // (plus its outline) so it reads as "current" without changing opacity.
        int tc = !this.active && !this.selected ? TEXT_DISABLED
                : this.selected ? TEXT_SELECTED
                : hov ? TEXT_HOVER : TEXT_IDLE;
        Component msg = getMessage();
        int textWidth = font.width(msg);
        int textX = x + (w - textWidth) / 2;
        int textY = y + (h - font.lineHeight) / 2 + 1;
        gg.text(font, msg, textX, textY, tc);
    }

    private static void outline(GuiGraphicsExtractor gg, int x, int y, int w, int h, int color) {
        gg.fill(x, y, x + w, y + 1, color);
        gg.fill(x, y + h - 1, x + w, y + h, color);
        gg.fill(x, y, x + 1, y + h, color);
        gg.fill(x + w - 1, y, x + w, y + h, color);
    }
}
