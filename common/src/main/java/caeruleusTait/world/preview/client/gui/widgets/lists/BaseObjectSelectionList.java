// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets.lists;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

public abstract class BaseObjectSelectionList<E extends BaseObjectSelectionList.Entry<E>> extends ObjectSelectionList<E> {
    /** Deadzone around the autoscroll anchor, in gui pixels, before scrolling starts. */
    private static final double PAN_DEADZONE_PX = 3.0;
    /** Autoscroll speed in pixels per second per gui pixel of anchor displacement. */
    private static final double PAN_PX_PER_SEC_PER_PX = 12.0;
    /** Upper autoscroll speed bound, in pixels per second. */
    private static final double PAN_MAX_PX_PER_SEC = 480.0;
    /** Time constant of the eased wheel animation, in milliseconds. */
    private static final double SCROLL_EASE_MS = 60.0;

    /** True between a middle-press autoscroll start and its release (screen-routed). */
    private boolean panActive;
    /** Autoscroll anchor: the cursor Y at the middle press. */
    private double panAnchorY;
    /** Wheel easing: the scroll amount the recent wheel steps accumulated toward. */
    private double easedTargetAmount;
    /** True while the applied amount is still gliding toward {@link #easedTargetAmount}. */
    private boolean gliding;
    /** Guards {@link #setScrollAmount} against mistaking our own animated apply for an external request. */
    private boolean applyingScroll;
    /** Previous render timestamp, for frame-rate independent easing and autoscroll. */
    private long lastFrameMs = -1;

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
            tickScroll(mouseY);
            super.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    /**
     * Per-render-frame scroll animation, ticked from the render pass so it
     * keeps running while the mouse is still. Two jobs: ease the applied
     * scroll amount toward the wheel target (wheel steps glide instead of
     * jumping), and while a middle-press autoscroll is active move the
     * amount continuously at a speed proportional to the cursor's distance
     * from the press point — browser autoscroll feel, same direction
     * (cursor below the anchor scrolls down), smooth at any speed.
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
            // Integrate the velocity straight into the applied amount, so
            // slow autoscroll stays as smooth as fast.
            gliding = false;
            applyAnimatedAmount(Mth.clamp(scrollAmount() + speed * dt / 1000.0, 0.0, getMaxScroll()));
        } else if (gliding) {
            double target = Mth.clamp(easedTargetAmount, 0.0, getMaxScroll());
            double eased = scrollAmount() + (target - scrollAmount()) * (1.0 - Math.exp(-dt / SCROLL_EASE_MS));
            if (Math.abs(target - eased) < 0.01) {
                eased = target;
                gliding = false;
            }
            applyAnimatedAmount(eased);
        }
    }

    /** Applies an animated amount without dropping the glide target. */
    private void applyAnimatedAmount(double amount) {
        applyingScroll = true;
        try {
            setScrollAmount(amount);
        } finally {
            applyingScroll = false;
        }
    }

    /**
     * External requests (vanilla thumb drag, keyboard scroll-to-entry, layout
     * resyncs) define the position outright, so any pending glide is dropped.
     * Our own animated applies go through {@link #applyAnimatedAmount}.
     */
    @Override
    public void setScrollAmount(double amount) {
        if (!applyingScroll) {
            gliding = false;
        }
        super.setScrollAmount(amount);
    }

    /**
     * Glide to the accumulated target instead of vanilla's instant jump; the
     * per-notch rate matches vanilla 1.20.1 ({@code itemHeight / 2.0}).
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!visible) {
            return false;
        }
        if (panActive) {
            // The wheel takes over, like a browser: cancel the autoscroll and
            // apply the step to the position it leaves behind.
            panActive = false;
        }
        double base = gliding ? Mth.clamp(easedTargetAmount, 0.0, getMaxScroll()) : scrollAmount();
        easedTargetAmount = Mth.clamp(base - delta * (this.itemHeight / 2.0), 0.0, getMaxScroll());
        gliding = true;
        return true;
    }

    /**
     * Starts a middle-button autoscroll anchored at the given mouse Y.
     * Returns false when the list cannot scroll (hidden or no overflow), so
     * the caller can pass the press through. While the button is held, the
     * render pass keeps scrolling at a speed proportional to the cursor's
     * distance from the anchor — browser autoscroll feel. The host screen
     * routes the release: vanilla's container does not forward non-primary
     * releases to the focused child.
     */
    public boolean beginScrollPan(double mouseY) {
        if (!visible || getMaxScroll() <= 0) {
            return false;
        }
        panActive = true;
        panAnchorY = mouseY;
        gliding = false;
        return true;
    }

    /** Ends a middle-button autoscroll. */
    public void endScrollPan() {
        panActive = false;
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
