// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Continuous slider snapping to whole values within [min, max]. Shared by the
 * seed search screens; caption and value are shown as "caption: value".
 */
final class IntSlider extends AbstractSliderButton {
    private final Component caption;
    private final int min;
    private final int max;
    private final int step;
    /** Optional change listener receiving the snapped value (options screen writes its state through it). */
    private IntConsumer responder;

    IntSlider(int x, int y, int w, int h, Component caption, int min, int max, int step, int initialValue) {
        super(x, y, w, h, Component.empty(), toSlider(initialValue, min, max));
        this.caption = caption;
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        updateMessage();
    }

    /** Sets the change listener invoked with the snapped value after each drag. */
    void setResponder(IntConsumer responder) {
        this.responder = responder;
    }

    private static double toSlider(int value, int min, int max) {
        return max <= min ? 0.0 : Math.min(1.0, Math.max(0.0, (value - min) / (double) (max - min)));
    }

    public int currentValue() {
        int span = max - min;
        int snapped = Math.round((float) value * span / step) * step;
        return Math.max(min, Math.min(max, min + snapped));
    }

    @Override
    protected void updateMessage() {
        setMessage(caption.copy().append(": " + String.format(Locale.ROOT, "%d", currentValue())));
    }

    @Override
    protected void applyValue() {
        updateMessage();
        if (responder != null) {
            responder.accept(currentValue());
        }
    }
}
