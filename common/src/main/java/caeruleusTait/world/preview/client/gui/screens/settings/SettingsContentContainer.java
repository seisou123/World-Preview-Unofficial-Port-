package caeruleusTait.world.preview.client.gui.screens.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import caeruleusTait.world.preview.client.gui.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Scroll viewport that hosts the existing ordinary settings widgets. */
public final class SettingsContentContainer extends AbstractWidget implements ContainerEventHandler {
    private static final int CONTENT_PADDING = 4;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int MIN_THUMB_HEIGHT = 12;
    /** Deadzone around the autoscroll anchor, in gui pixels, before scrolling starts. */
    private static final double PAN_DEADZONE_PX = 3.0;
    /** Autoscroll speed in pixels per second per gui pixel of anchor displacement. */
    private static final double PAN_PX_PER_SEC_PER_PX = 12.0;
    /** Upper autoscroll speed bound, in pixels per second. */
    private static final double PAN_MAX_PX_PER_SEC = 480.0;
    /** Time constant of the eased scroll animation, in milliseconds. */
    private static final double SCROLL_EASE_MS = 60.0;

    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final Map<AbstractWidget, Integer> baseY = new IdentityHashMap<>();
    private int contentHeight;
    private int viewportTop;
    private double scrollOffset;
    private boolean childCapture;
    private boolean scrollbarDragging;
    private double scrollbarGrabOffset;
    /** True between a middle-press autoscroll start and its release (screen-routed). */
    private boolean panActive;
    /** Autoscroll anchor: the cursor Y at the middle press. */
    private double panAnchorY;
    /** Eased scroll position actually rendered; chases {@link #scrollOffset} for smooth motion. */
    private double renderOffset;
    /** Previous render timestamp, for frame-rate independent easing and autoscroll. */
    private long lastFrameMs = -1;
    @Nullable
    private GuiEventListener focused;
    private boolean dragging;

    public SettingsContentContainer(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
        this.viewportTop = y;
    }

    @Override
    public boolean isDragging() {
        return dragging;
    }

    @Override
    public void setDragging(boolean dragging) {
        this.dragging = dragging;
    }

    @Nullable
    @Override
    public GuiEventListener getFocused() {
        return focused;
    }

    @Override
    public void setFocused(@Nullable GuiEventListener focused) {
        this.focused = focused;
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
        // New page content: no glide from the previous page's position.
        renderOffset = 0.0;
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

    /** 1.20.1 AbstractWidget has no setHeight/getRight/getBottom. */
    public void setHeight(int height) {
        this.height = height;
    }

    private int getRight() {
        return getX() + width;
    }

    private int getBottom() {
        return getY() + height;
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
        int offset = (int) Math.round(renderOffset);
        for (AbstractWidget widget : widgets) {
            Integer y = baseY.get(widget);
            if (y != null) {
                widget.setY(y - offset);
            }
        }
    }

    public void setScrollAmount(double amount) {
        scrollOffset = Math.max(0.0, Math.min(maxScroll(), amount));
        applyScrollPosition();
    }

    protected int contentHeight() {
        return Math.max(height, contentHeight);
    }

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
        return getY() + (int) Math.round(travel * (renderOffset / maxScroll()));
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
        renderOffset = scrollOffset;
    }

    /**
     * Per-render-frame scroll animation, ticked from the render pass so it
     * keeps running while the mouse is still. Two jobs: ease the rendered
     * offset toward the target (wheel steps glide instead of jumping), and
     * while a middle-press autoscroll is active move the rendered position
     * continuously at a speed proportional to the cursor's distance from
     * the press point — browser autoscroll feel, same direction (cursor
     * below the anchor scrolls down), pixel-smooth at any speed.
     */
    private void tickScroll(double mouseY) {
        long now = System.currentTimeMillis();
        long dt = lastFrameMs < 0 ? 16 : Math.min(100, now - lastFrameMs);
        lastFrameMs = now;
        if (panActive) {
            double displacement = mouseY - panAnchorY;
            double speed = 0.0;
            if (Math.abs(displacement) > PAN_DEADZONE_PX) {
                speed = Math.min(
                        PAN_MAX_PX_PER_SEC,
                        (displacement - Math.signum(displacement) * PAN_DEADZONE_PX) * PAN_PX_PER_SEC_PER_PX);
            }
            // Integrate the velocity straight into the rendered position, so
            // slow autoscroll stays as smooth as fast; the integer target
            // follows the rendered position.
            scrollOffset = Math.min(maxScroll(), Math.max(0.0, renderOffset + speed * dt / 1000.0));
            renderOffset = scrollOffset;
        } else if (scrollbarDragging) {
            renderOffset = scrollOffset;
        } else {
            renderOffset += (scrollOffset - renderOffset) * (1.0 - Math.exp(-dt / SCROLL_EASE_MS));
            if (Math.abs(scrollOffset - renderOffset) < 0.01) {
                renderOffset = scrollOffset;
            }
            renderOffset = Math.min(maxScroll(), Math.max(0.0, renderOffset));
        }
        applyScrollPosition();
    }

    /**
     * Starts a middle-button autoscroll anchored at the given mouse Y.
     * Returns false when the page cannot scroll, so the caller can pass the
     * press through. While the button is held, the render pass keeps
     * scrolling at a speed proportional to the cursor's distance from the
     * anchor — browser autoscroll feel. The screen routes the release:
     * vanilla's container does not forward non-primary releases to the
     * focused child.
     */
    public boolean beginScrollPan(double mouseY) {
        if (!hasScrollbar()) {
            return false;
        }
        panActive = true;
        panAnchorY = mouseY;
        return true;
    }

    /** Ends a middle-button autoscroll. */
    public void endScrollPan() {
        panActive = false;
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
        tickScroll(mouseY);
        graphics.enableScissor(getX(), getY(), getRight(), getBottom());
        for (AbstractWidget widget : widgets) {
            widget.render(graphics, mouseX, mouseY, delta);
        }
        graphics.disableScissor();
        renderOwnScrollbar(graphics);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        final MouseButtonEvent event = new MouseButtonEvent(mouseX, mouseY, button);
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
            // Jump the thumb under the cursor (vanilla AbstractScrollArea
            // semantics), then keep dragging it 1:1 — the old page-flip on a
            // track click is gone.
            scrollbarDragging = true;
            scrollbarGrabOffset = scrollbarThumbHeight() / 2.0;
            scrollFromScrollbar(event.y());
            Minecraft.getInstance().screen.setFocused(this);
            return true;
        }
        childCapture = ContainerEventHandler.super.mouseClicked(mouseX, mouseY, button);
        return childCapture;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        final MouseButtonEvent event = new MouseButtonEvent(mouseX, mouseY, button);
        boolean handled = scrollbarDragging;
        scrollbarDragging = false;
        scrollbarGrabOffset = 0;
        if (childCapture) {
            handled = ContainerEventHandler.super.mouseReleased(mouseX, mouseY, button) || handled;
            childCapture = false;
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        final MouseButtonEvent event = new MouseButtonEvent(mouseX, mouseY, button);
        if (scrollbarDragging) {
            scrollFromScrollbar(event.y());
            return true;
        }
        return childCapture && ContainerEventHandler.super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!inViewport(mouseX, mouseY)) {
            return false;
        }
        if (panActive) {
            // The wheel takes over, like a browser: cancel the autoscroll
            // and apply the step to the position it leaves behind.
            panActive = false;
        }
        setScrollAmount(scrollOffset - delta * scrollRate());
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
