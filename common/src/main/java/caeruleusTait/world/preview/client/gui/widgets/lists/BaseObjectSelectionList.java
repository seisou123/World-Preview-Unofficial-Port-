// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets.lists;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;

import java.util.Collection;

public abstract class BaseObjectSelectionList<E extends BaseObjectSelectionList.Entry<E>> extends ObjectSelectionList<E> {
    protected BaseObjectSelectionList(Minecraft minecraft, int width, int height, int x, int y, int itemHeight) {
        super(minecraft, width, height, y, itemHeight);
    }

    @Override
    public int getRowLeft() {
        return getX();
    }

    @Override
    public int getRowRight() {
        return getX() + width - 6;
    }

    @Override
    public int getRowWidth() {
        return this.width - 6;
    }

    /** 1.21.11's {@code scrollBarX()} hook is {@code getScrollbarPosition()} in 1.21.1. */
    @Override
    protected int getScrollbarPosition() {
        return getRowRight();
    }

    /** 1.21.11's {@code scrollAmount()} getter; 1.21.1 spells it {@code getScrollAmount()}. */
    public double scrollAmount() {
        return getScrollAmount();
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * Make public, then re-apply the current scroll amount: vanilla recomputes
     * the entry coordinates (via its private reposition pass, triggered by
     * {@code setScrollAmount}) only on scrolling and on sort/swap/add/remove/
     * clear, never inside {@code replaceEntries} itself, so freshly swapped-in
     * rows would keep their stale default coordinates and render misplaced or
     * clipped until the first scroll.
     */
    @Override
    public void replaceEntries(Collection<E> entryList) {
        super.replaceEntries(entryList);
        setScrollAmount(scrollAmount());
    }

    /**
     * Vanilla never recomputes the entry coordinates when the list widget
     * itself is moved or resized, so the first frame after a layout change
     * would render row contents at stale (and clipped) positions.  Re-applying
     * the current scroll amount re-runs the internal reposition pass against
     * the new bounds; {@link #setY(int)}, {@link #setWidth(int)} and
     * {@link #setHeight(int)} mirror this.
     */
    @Override
    public void setX(int x) {
        super.setX(x);
        setScrollAmount(scrollAmount());
    }

    /** Re-syncs entry coordinates after the move; see {@link #setX(int)}. */
    @Override
    public void setY(int y) {
        super.setY(y);
        setScrollAmount(scrollAmount());
    }

    /** Re-syncs entry coordinates after the resize; see {@link #setX(int)}. */
    @Override
    public void setWidth(int width) {
        super.setWidth(width);
        setScrollAmount(scrollAmount());
    }

    /** Re-syncs entry coordinates after the resize; see {@link #setX(int)}. */
    @Override
    public void setHeight(int height) {
        super.setHeight(height);
        setScrollAmount(scrollAmount());
    }

    /**
     * 1.21.11 renders entries as positioned widgets and hands subclasses a
     * {@code renderContent(g, mouseX, mouseY, hovered, partialTick)} hook with
     * {@code getContentX()/getContentY()} bounds; 1.21.1 passes the row
     * rectangle as plain {@code render(...)} parameters instead.  This entry
     * base stashes the bounds and re-exposes the 1.21.11 surface so row
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
