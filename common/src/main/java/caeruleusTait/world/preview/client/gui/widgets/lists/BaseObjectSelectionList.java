// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets.lists;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.util.Mth;

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
        tickScroll(mouseY);
        super.extractWidgetRenderState(guiGraphicsExtractor, mouseX, mouseY, partialTick);
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
            applyAnimatedAmount(Mth.clamp(scrollAmount() + speed * dt / 1000.0, 0.0, maxScrollAmount()));
        } else if (gliding) {
            double target = Mth.clamp(easedTargetAmount, 0.0, maxScrollAmount());
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (!visible) {
            return false;
        }
        // Glide to the accumulated target instead of vanilla's instant jump.
        if (panActive) {
            // The wheel takes over, like a browser: cancel the autoscroll and
            // apply the step to the position it leaves behind.
            panActive = false;
        }
        double base = gliding ? Mth.clamp(easedTargetAmount, 0.0, maxScrollAmount()) : scrollAmount();
        easedTargetAmount = Mth.clamp(base - deltaY * scrollRate(), 0.0, maxScrollAmount());
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
        if (!visible || maxScrollAmount() <= 0) {
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
