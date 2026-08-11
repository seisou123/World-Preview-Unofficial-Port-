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
     * Make public
     */
    @Override
    public void replaceEntries(Collection<E> entryList) {
        super.replaceEntries(entryList);
    }

    public abstract static class Entry<E extends Entry<E>> extends ObjectSelectionList.Entry<E> {
        public Tooltip tooltip() {
            return null;
        }
    }
}
