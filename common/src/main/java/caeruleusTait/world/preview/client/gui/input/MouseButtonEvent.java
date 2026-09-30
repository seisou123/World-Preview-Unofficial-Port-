package caeruleusTait.world.preview.client.gui.input;

import net.minecraft.client.gui.screens.Screen;

/**
 * Stand-in for the {@code net.minecraft.client.input.MouseButtonEvent} record
 * that Mojang introduced after 1.21.1. In 1.21.1 the GUI event callbacks still
 * receive raw {@code (double mouseX, double mouseY, int button)} arguments, so
 * the boundary overrides in this package construct this record from the
 * primitives and keep the existing event-style bodies untouched.
 *
 * <p>Modifier state is read live through {@link Screen}'s static helpers —
 * the same way vanilla code queried modifiers in the 1.21.1 era — instead of
 * being carried inside the event.
 */
public record MouseButtonEvent(double x, double y, int button) {

    public boolean isLeft() {
        return button == 0;
    }

    public boolean isRight() {
        return button == 1;
    }

    public boolean isMiddle() {
        return button == 2;
    }

    public boolean hasControlDown() {
        return Screen.hasControlDown();
    }

    public boolean hasShiftDown() {
        return Screen.hasShiftDown();
    }
}
