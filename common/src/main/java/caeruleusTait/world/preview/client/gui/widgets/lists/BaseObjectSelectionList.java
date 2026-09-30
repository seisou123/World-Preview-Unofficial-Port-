// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets.lists;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

public abstract class BaseObjectSelectionList<E extends BaseObjectSelectionList.Entry<E>> extends ObjectSelectionList<E> {
    // 1.20.1 selection lists are not AbstractWidgets: they expose no position,
    // size or enabled flags.  Re-expose the surface the rest of the mod uses.
    public boolean visible = true;
    public boolean active = true;

    @Nullable
    private AbstractWidget widgetAdapter;

    protected BaseObjectSelectionList(Minecraft minecraft, int width, int height, int x, int y, int itemHeight) {
        super(minecraft, width, height, y, y + height, itemHeight);
        this.x0 = x;
        this.x1 = x + width;
        // Vanilla 1.20.1 list decorations assume a full-screen list: the
        // top/bottom bands blit dirt from y=0 down to the list top (and from
        // y1 onward), which in the floating panel paints over the tab bar and
        // rail buttons.  Upstream 1.20.1 disables them for exactly this
        // reason.  The opaque in-bounds dirt background is disabled too:
        // 1.21.x fills lists with a translucent tile instead (see
        // renderBackground below).
        setRenderTopAndBottom(false);
        setRenderBackground(false);
    }

    /**
     * 1.21.x fills lists with a translucent black tile
     * ({@code menu_list_background}, uniform 44% alpha), so the map shows
     * through the floating panel; 1.20.1's {@code options_background} dirt
     * tile is fully opaque.  Replicate the 1.21.x look with a plain
     * translucent fill (alpha 112/255 sampled from the 1.21.1 texture).
     */
    @Override
    protected void renderBackground(GuiGraphics guiGraphics) {
        guiGraphics.fill(this.x0, this.y0, this.x1, this.y1, 0x70000000);
    }

    public int getX() {
        return this.x0;
    }

    public int getY() {
        return this.y0;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public void setX(int x) {
        this.x0 = x;
        this.x1 = x + this.width;
        setScrollAmount(scrollAmount());
    }

    public void setY(int y) {
        this.y0 = y;
        this.y1 = y + this.height;
        setScrollAmount(scrollAmount());
    }

    public void setWidth(int width) {
        this.width = width;
        this.x1 = this.x0 + width;
        setScrollAmount(scrollAmount());
    }

    public void setHeight(int height) {
        this.height = height;
        // Keep y1 in sync: vanilla scissoring, scroll math and hit tests all
        // read y0/y1, not height.
        this.y1 = this.y0 + height;
        setScrollAmount(scrollAmount());
    }

    @Override
    public int getRowLeft() {
        return this.x0;
    }

    @Override
    public int getRowRight() {
        return this.x0 + this.width - 6;
    }

    @Override
    public int getRowWidth() {
        return this.width - 6;
    }

    @Override
    protected int getScrollbarPosition() {
        return getRowRight();
    }

    public double scrollAmount() {
        return getScrollAmount();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (visible) {
            super.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible && super.isMouseOver(mouseX, mouseY);
    }

    /**
     * Vanilla never recomputes the entry coordinates when the list widget
     * itself is moved or resized, so the first frame after a layout change
     * would render row contents at stale (and clipped) positions.  Re-applying
     * the current scroll amount re-runs the internal reposition pass against
     * the new bounds; {@link #setX(int)}, {@link #setY(int)},
     * {@link #setWidth(int)} and {@link #setHeight(int)} mirror this.
     */
    @Override
    public void replaceEntries(Collection<E> entryList) {
        super.replaceEntries(entryList);
        setScrollAmount(scrollAmount());
    }

    /**
     * 1.20.1 tabs can only register {@link AbstractWidget} children, so the
     * screen attaches this adapter instead of the raw list.  Rendering and
     * mouse handling delegate to the list; bounds and the visible flag are
     * mirrored back so occlusion checks and event traversal keep working.
     */
    public AbstractWidget asWidget() {
        if (widgetAdapter == null) {
            widgetAdapter = new AbstractWidget(0, 0, 0, 0, Component.empty()) {
                @Override
                protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
                    setX(BaseObjectSelectionList.this.getX());
                    setY(BaseObjectSelectionList.this.getY());
                    setWidth(BaseObjectSelectionList.this.getWidth());
                    // 1.20.1 AbstractWidget has no setHeight setter, so the
                    // plain call would resolve to the enclosing list's own
                    // setHeight and leave this widget's height at 0; assign
                    // the field directly (focus-navigation rectangles read it).
                    height = BaseObjectSelectionList.this.getHeight();
                    if (BaseObjectSelectionList.this.visible) {
                        BaseObjectSelectionList.this.render(guiGraphics, mouseX, mouseY, partialTick);
                    }
                }

                @Override
                public boolean isMouseOver(double mouseX, double mouseY) {
                    return BaseObjectSelectionList.this.visible
                            && BaseObjectSelectionList.this.isMouseOver(mouseX, mouseY);
                }

                @Override
                public boolean mouseClicked(double mouseX, double mouseY, int button) {
                    return BaseObjectSelectionList.this.visible
                            && BaseObjectSelectionList.this.active
                            && BaseObjectSelectionList.this.mouseClicked(mouseX, mouseY, button);
                }

                @Override
                public boolean mouseReleased(double mouseX, double mouseY, int button) {
                    return BaseObjectSelectionList.this.mouseReleased(mouseX, mouseY, button);
                }

                @Override
                public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
                    return BaseObjectSelectionList.this.mouseDragged(mouseX, mouseY, button, dragX, dragY);
                }

                @Override
                public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
                    return BaseObjectSelectionList.this.visible
                            && BaseObjectSelectionList.this.mouseScrolled(mouseX, mouseY, delta);
                }

                @Override
                protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
                    // Row widgets narrate themselves.
                }
            };
        }
        return widgetAdapter;
    }

    /**
     * 1.21.x renders entries as positioned widgets and hands subclasses a
     * {@code renderContent(g, mouseX, mouseY, hovered, partialTick)} hook with
     * {@code getContentX()/getContentY()} bounds; 1.20.1 passes the row
     * rectangle as plain {@code render(...)} parameters instead.  This entry
     * base stashes the bounds and re-exposes the same surface so row
     * implementations stay unchanged.
     */
    public abstract static class Entry<E extends Entry<E>> extends ObjectSelectionList.Entry<E> {
        private int contentX;
        private int contentY;
        private int contentWidth;
        private int contentHeight;

        @Override
        public void render(
                GuiGraphics guiGraphics,
                int index,
                int top,
                int left,
                int entryWidth,
                int entryHeight,
                int mouseX,
                int mouseY,
                boolean hovered,
                float partialTick
        ) {
            this.contentX = left;
            this.contentY = top;
            this.contentWidth = entryWidth;
            this.contentHeight = entryHeight;
            renderContent(guiGraphics, mouseX, mouseY, hovered, partialTick);
        }

        public abstract void renderContent(
                GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick
        );

        public int getContentX() {
            return contentX;
        }

        public int getContentY() {
            return contentY;
        }

        public int getContentWidth() {
            return contentWidth;
        }

        public int getContentHeight() {
            return contentHeight;
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return mouseX >= contentX && mouseX < contentX + contentWidth
                    && mouseY >= contentY && mouseY < contentY + contentHeight;
        }

        public Tooltip tooltip() {
            return null;
        }
    }
}
