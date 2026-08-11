package caeruleusTait.world.preview.client.gui.widgets;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Semi-transparent button for the switch (Biomes / Structures / Seeds) rail icons.
 *
 * - Background: 2/3 transparent light gray (1/3 opaque)
 * - Text: 1/2 transparent
 * - Hover/click border: 33% transparent gray
 * - Auto-adaptive width (min = current rail width)
 * - Slightly reduced height
 */
public class TranslucentButton extends Button {

    private static final int BG_COLOR_ACTIVE   = 0x55CCCCCC;
    private static final int BG_COLOR_INACTIVE = 0x22666666;
    private static final int TEXT_COLOR_ACTIVE   = 0x80FFFFFF;
    private static final int TEXT_COLOR_INACTIVE = 0x66999999;
    private static final int HOVER_BORDER_COLOR = 0xAA888888;

    private final Font font;
    private final int minWidth;

    public TranslucentButton(Font font, int x, int y, int minWidth, int height,
                              Component text, OnPress onPress) {
        super(x, y, Math.max(minWidth, font.width(text) + 16), height, text, onPress, DEFAULT_NARRATION);
        this.font = font;
        this.minWidth = minWidth;
        updateAutoWidth();
    }

    public void updateAutoWidth() {
        int textWidth = font.width(getMessage());
        int newWidth = Math.max(minWidth, textWidth + 16);
        setWidth(newWidth);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean act = this.active;
        boolean hov = this.isHoveredOrFocused();

        int bg = act ? BG_COLOR_ACTIVE : BG_COLOR_INACTIVE;
        gg.fill(x, y, x + w, y + h, bg);

        if (act && hov) {
            int bc = HOVER_BORDER_COLOR;
            gg.fill(x, y, x + w, y + 1, bc);
            gg.fill(x, y + h - 1, x + w, y + h, bc);
            gg.fill(x, y, x + 1, y + h, bc);
            gg.fill(x + w - 1, y, x + w, y + h, bc);
        }

        int tc = act ? TEXT_COLOR_ACTIVE : TEXT_COLOR_INACTIVE;
        Component msg = getMessage();
        int textWidth = font.width(msg);
        int textX = x + (w - textWidth) / 2;
        int textY = y + (h - font.lineHeight) / 2 + 1;
        gg.text(font, msg, textX, textY, tc);
    }
}
