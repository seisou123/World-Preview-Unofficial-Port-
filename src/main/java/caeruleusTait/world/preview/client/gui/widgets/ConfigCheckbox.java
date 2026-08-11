package caeruleusTait.world.preview.client.gui.widgets;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A checkbox-like widget that exposes its state for external binding.
 */
public class ConfigCheckbox extends AbstractWidget {

    private boolean selected;

    public ConfigCheckbox(int x, int y, int width, int height, Component message,
                          boolean selected) {
        super(x, y, width, height, message);
        this.selected = selected;
    }

    public void setValue(boolean value) {
        this.selected = value;
    }

    public boolean isSelected() {
        return selected;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        guiGraphics.fill(x, y, x + w, y + h, 0xFF666666);
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
        if (selected) {
            guiGraphics.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFFFFFFF);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.isMouseOver(mouseX, mouseY)) {
            this.selected = !this.selected;
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}