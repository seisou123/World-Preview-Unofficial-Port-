package caeruleusTait.world.preview.client.gui.widgets;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * A slider that exposes its value for external binding.
 * Used in settings pages where values are managed as pending state.
 */
public class ConfigSlider extends AbstractSliderButton {

    public ConfigSlider(int x, int y, int width, int height, Component message,
                        double value, Component caption) {
        super(x, y, width, height, message, value);
    }

    public void setValue(double value) {
        this.value = value;
        this.updateMessage();
    }

    @Override
    protected void updateMessage() {
        // Caption is set externally or uses default
    }

    @Override
    protected void applyValue() {
        // No-op: value changes are handled externally via setValue
    }
}
