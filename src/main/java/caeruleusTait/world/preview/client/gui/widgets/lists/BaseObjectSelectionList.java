// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets.lists;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

    @Override
    protected int scrollBarX() {
        return getRowRight();
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, float partialTick) {
        super.extractWidgetRenderState(guiGraphicsExtractor, mouseX, mouseY, partialTick);
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

    public abstract static class Entry<E extends Entry<E>> extends ObjectSelectionList.Entry<E> {
        public Tooltip tooltip() {
            return null;
        }
    }
}
