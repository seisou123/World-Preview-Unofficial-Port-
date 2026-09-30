package caeruleusTait.world.preview.client.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/** 1.20.1 has no {@code Checkbox.builder()}; this wraps the plain constructor
 * with the builder's auto-sizing and a value-change callback. */
public class WPCheckbox extends Checkbox {
    @Nullable
    private final Consumer<Boolean> onChange;

    public WPCheckbox(int x, int y, Component label, boolean selected, @Nullable Consumer<Boolean> onChange) {
        super(x, y, Minecraft.getInstance().font.width(label) + 24, 20, label, selected);
        this.onChange = onChange;
    }

    public void setPosition(int x, int y) {
        setX(x);
        setY(y);
    }

    @Override
    public void onPress() {
        super.onPress();
        if (onChange != null) {
            onChange.accept(this.selected());
        }
    }
}
