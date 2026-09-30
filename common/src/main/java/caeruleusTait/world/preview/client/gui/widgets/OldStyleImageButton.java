// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class OldStyleImageButton extends Button {

    protected final int xTexStart;
    protected final int yTexStart;
    protected final int yDiffTex;
    protected final ResourceLocation texture;
    protected final int texWidth;
    protected final int texHeight;

    public OldStyleImageButton(
            int x, int y, int width, int height,
            int xTexStart, int yTexStart, int yDiffTex,
            ResourceLocation texture, int texWidth, int texHeight,
            OnPress onPress
    ) {
        super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
        this.xTexStart = xTexStart;
        this.yTexStart = yTexStart;
        this.yDiffTex = yDiffTex;
        this.texture = texture;
        this.texWidth = texWidth;
        this.texHeight = texHeight;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int x = this.xTexStart;
        int y = this.yTexStart;
        if (!this.isActive()) {
            y += yDiffTex * 2;
        } else if (this.isHoveredOrFocused()) {
            y += yDiffTex;
        }

        guiGraphics.blit(texture, getX(), getY(), 0, x, y, width, height, texWidth, texHeight);
    }
}
