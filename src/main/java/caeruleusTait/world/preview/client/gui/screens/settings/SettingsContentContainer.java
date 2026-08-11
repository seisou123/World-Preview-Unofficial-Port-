package caeruleusTait.world.preview.client.gui.screens.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractContainerWidget;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Scroll viewport that hosts the existing ordinary settings widgets. */
public final class SettingsContentContainer extends AbstractContainerWidget {
    private static final int CONTENT_PADDING = 4;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int MIN_THUMB_HEIGHT = 12;

    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final Map<AbstractWidget, Integer> baseY = new IdentityHashMap<>();
    private int contentHeight;
    private int viewportTop;
    private double scrollOffset;
    private boolean childCapture;
    private boolean scrollbarDragging;
    private double scrollbarGrabOffset;

    public SettingsContentContainer(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
        this.viewportTop = y;
    }

    public void setWidgets(List<AbstractWidget> source) {
        widgets.clear();
        baseY.clear();
        widgets.addAll(source);
        for (AbstractWidget widget : widgets) {
            baseY.put(widget, widget.getY());
        }
        recalculateContentHeight();
        setScrollAmount(0);
    }

    public void setViewportTop(int viewportTop) {
        this.viewportTop = viewportTop;
        recalculateContentHeight();
        setScrollAmount(scrollOffset);
    }

    /** Repositions the viewport without changing the content's logical coordinates. */
    public void setViewportBounds(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        setWidth(width);
        setHeight(height);
        setViewportTop(y);
    }

    private void recalculateContentHeight() {
        contentHeight = 0;
        for (AbstractWidget widget : widgets) {
            Integer y = baseY.get(widget);
            if (y != null) {
                contentHeight = Math.max(contentHeight, y + widget.getHeight() - viewportTop + CONTENT_PADDING);
            }
        }
    }

    private double maxScroll() {
        return Math.max(0.0, contentHeight - height);
    }

    private void applyScrollPosition() {
        int offset = (int) Math.round(scrollOffset);
        for (AbstractWidget widget : widgets) {
            Integer y = baseY.get(widget);
            if (y != null) {
                widget.setY(y - offset);
            }
        }
    }

    @Override
    public void setScrollAmount(double amount) {
        scrollOffset = Math.max(0.0, Math.min(maxScroll(), amount));
        applyScrollPosition();
    }

    @Override
    protected int contentHeight() {
        return Math.max(height, contentHeight);
    }

    @Override
    protected double scrollRate() {
        return 18.0;
    }

    private boolean inViewport(double mouseX, double mouseY) {
        return mouseX >= getX() && mouseX < getRight() && mouseY >= getY() && mouseY < getBottom();
    }

    private boolean hasScrollbar() {
        return maxScroll() > 0.0;
    }

    private boolean inScrollbar(double mouseX, double mouseY) {
        return hasScrollbar()
                && mouseX >= getRight() - SCROLLBAR_WIDTH
                && mouseX < getRight()
                && mouseY >= getY()
                && mouseY < getBottom();
    }

    private int scrollbarThumbHeight() {
        return Math.max(MIN_THUMB_HEIGHT, (int) Math.round(height * (height / (double) contentHeight)));
    }

    private int scrollbarThumbTop() {
        int trackHeight = height;
        int thumbHeight = scrollbarThumbHeight();
        int travel = Math.max(0, trackHeight - thumbHeight);
        if (travel == 0 || maxScroll() == 0.0) {
            return getY();
        }
        return getY() + (int) Math.round(travel * (scrollOffset / maxScroll()));
    }

    private boolean inScrollbarThumb(double mouseX, double mouseY) {
        int thumbTop = scrollbarThumbTop();
        return inScrollbar(mouseX, mouseY)
                && mouseY >= thumbTop
                && mouseY < thumbTop + scrollbarThumbHeight();
    }

    private void scrollFromScrollbar(double mouseY) {
        int thumbHeight = scrollbarThumbHeight();
        int travel = Math.max(1, height - thumbHeight);
        double thumbTop = mouseY - scrollbarGrabOffset;
        double fraction = (thumbTop - getY()) / travel;
        setScrollAmount(fraction * maxScroll());
    }

    private void renderOwnScrollbar(GuiGraphics graphics) {
        if (!hasScrollbar()) {
            return;
        }
        int left = getRight() - SCROLLBAR_WIDTH;
        int top = getY();
        int bottom = getBottom();
        int thumbTop = scrollbarThumbTop();
        int thumbBottom = thumbTop + scrollbarThumbHeight();
        graphics.fill(left, top, getRight(), bottom, 0x66000000);
        graphics.fill(left, thumbTop, getRight(), thumbBottom, 0xFF888888);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return inViewport(mouseX, mouseY);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.enableScissor(getX(), getY(), getRight(), getBottom());
        for (AbstractWidget widget : widgets) {
            widget.render(graphics, mouseX, mouseY, delta);
        }
        graphics.disableScissor();
        renderOwnScrollbar(graphics);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!inViewport(event.x(), event.y())) {
            return false;
        }
        if (inScrollbarThumb(event.x(), event.y())) {
            scrollbarDragging = true;
            scrollbarGrabOffset = event.y() - scrollbarThumbTop();
            Minecraft.getInstance().screen.setFocused(this);
            return true;
        }
        if (inScrollbar(event.x(), event.y())) {
            int thumbTop = scrollbarThumbTop();
            setScrollAmount(event.y() < thumbTop ? scrollOffset - height : scrollOffset + height);
            return true;
        }
        childCapture = super.mouseClicked(event, doubleClick);
        return childCapture;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = scrollbarDragging;
        scrollbarDragging = false;
        scrollbarGrabOffset = 0;
        if (childCapture) {
            handled = super.mouseReleased(event) || handled;
            childCapture = false;
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (scrollbarDragging) {
            scrollFromScrollbar(event.y());
            return true;
        }
        return childCapture && super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (!inViewport(mouseX, mouseY)) {
            return false;
        }
        setScrollAmount(scrollOffset - deltaY * scrollRate());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // Child widgets provide their own narration when focused.
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return widgets;
    }
}
