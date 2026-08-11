package caeruleusTait.world.preview.domain.ui;

/**
 * Factory for creating UI controls based on binding type.
 *
 * <p>The actual control creation is platform-specific (Minecraft GUI widgets);
 * this interface allows the domain layer to describe what controls are needed
 * without depending on Minecraft classes.
 */
public interface ControlFactory {

    /**
     * Creates a control for the given binding.
     *
     * @param binding the config binding
     * @return a platform-specific control object
     */
    Object createControl(ConfigBinding<?> binding);

    /** Creates a button control. */
    Object createButton(String label, Runnable onClick);

    /** Creates a label control. */
    Object createLabel(String text);

    /** Creates a slider control for integer values. */
    Object createSlider(String label, int min, int max, int current, java.util.function.IntConsumer onChange);

    /** Creates a toggle/switch control. */
    Object createToggle(String label, boolean current, java.util.function.Consumer<Boolean> onChange);

    /** Creates a text input control. */
    Object createTextInput(String placeholder, String current, java.util.function.Consumer<String> onChange);

    /** Creates a color picker control. */
    Object createColorPicker(int current, java.util.function.IntConsumer onChange);
}
