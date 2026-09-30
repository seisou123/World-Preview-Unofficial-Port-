package caeruleusTait.world.preview.client.gui.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * Stand-in for the {@code net.minecraft.client.input.KeyEvent} record that
 * Mojang introduced after 1.21.1. 1.21.1's {@code keyPressed(int key, int
 * scanCode, int modifiers)} callback is converted at the boundary overrides.
 */
public record KeyEvent(int key, int scanCode, int modifiers) {

    public boolean isLeft() {
        return key == GLFW.GLFW_KEY_LEFT;
    }

    public boolean isRight() {
        return key == GLFW.GLFW_KEY_RIGHT;
    }

    public boolean isUp() {
        return key == GLFW.GLFW_KEY_UP;
    }

    public boolean isDown() {
        return key == GLFW.GLFW_KEY_DOWN;
    }

    public boolean isEscape() {
        return key == GLFW.GLFW_KEY_ESCAPE;
    }

    /** KeyMapping.matches(KeyEvent) does not exist in 1.21.1; its (key, scan) form does. */
    public boolean matches(KeyMapping mapping) {
        return mapping.matches(key, scanCode);
    }

    public boolean hasControlDown() {
        return Screen.hasControlDown();
    }

    public boolean hasShiftDown() {
        return Screen.hasShiftDown();
    }
}
