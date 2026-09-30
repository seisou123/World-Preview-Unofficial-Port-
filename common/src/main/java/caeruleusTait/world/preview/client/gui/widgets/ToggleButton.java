// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public class ToggleButton extends OldStyleImageButton {
    public boolean selected;
    protected final int xDiff;

    public ToggleButton(int x, int y, int width, int height, int xTexStart, int yTexStart, ResourceLocation ResourceLocation, OnPress onPress) {
        this(x, y, width, height, xTexStart, yTexStart, width, height, ResourceLocation, 256, 256, onPress);
    }

    public ToggleButton(int x, int y, int width, int height, int xTexStart, int yTexStart, int xDiff, int yDiff, ResourceLocation ResourceLocation, OnPress onPress) {
        this(x, y, width, height, xTexStart, yTexStart, xDiff, yDiff, ResourceLocation, 256, 256, onPress);
    }

    public ToggleButton(
            int x,
            int y,
            int width,
            int height,
            int xTexStart,
            int yTexStart,
            int xDiff,
            int yDiff,
            ResourceLocation ResourceLocation,
            int texWidth,
            int texHeight,
            OnPress onPress
    ) {
        super(x, y, width, height, xTexStart, yTexStart, yDiff, ResourceLocation, texWidth, texHeight, onPress);
        this.xDiff = xDiff;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int x = this.xTexStart;
        int y = this.yTexStart;
        if (!selected) {
            x += xDiff;
        }
        if (!this.isActive()) {
            y += yDiffTex * 2;
        } else if (this.isHoveredOrFocused()) {
            y += yDiffTex;
        }

        guiGraphics.blit(texture, getX(), getY(), 0, x, y, width, height, texWidth, texHeight);
    }

    @Override
    public void onPress() {
        selected = !selected;
        super.onPress();
    }
}
