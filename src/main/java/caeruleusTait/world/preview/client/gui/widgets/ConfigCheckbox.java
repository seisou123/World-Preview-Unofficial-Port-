package caeruleusTait.world.preview.client.gui.widgets;

import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;

/**
 * A checkbox that exposes its state for external binding.
 */
public class ConfigCheckbox extends Checkbox {

    public ConfigCheckbox(int x, int y, int width, int height, Component message,
                          boolean selected) {
        super(x, y, width, message, null, selected, null);
    }

    public void setValue(boolean value) {
        this.selected = value;
    }
}
