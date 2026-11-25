package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.domain.ui.ConfigurablePage;
import caeruleusTait.world.preview.domain.ui.PageCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import caeruleusTait.world.preview.client.gui.widgets.WPCheckbox;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Bridge between the domain {@link ConfigurablePage} and the Minecraft-specific
 * {@link SettingsPage} interface.
 *
 * <p>Provides shared widget-creation helpers (checkbox rows, slider rows, labels)
 * that were previously duplicated across all six settings page implementations.
 * Subclasses extend this class and call the helper methods from their
 * {@link #build(ScreenRectangle, PreviewContainer)} implementation.
 *
 * <p>Domain-level reset is bridged automatically:
 * <ul>
 *   <li>{@link #reset()} delegates to {@link ConfigurablePage#resetDefaults()}</li>
 * </ul>
 */
public abstract class AbstractSettingsPage extends ConfigurablePage implements SettingsPage {

    protected final List<AbstractWidget> widgets = new ArrayList<>();

    protected AbstractSettingsPage(String title, PageCategory category) {
        super(title, category);
    }

    // ---- SettingsPage implementation ----

    @Override
    public List<AbstractWidget> widgets() {
        return widgets;
    }

    @Override
    public void save() {
        // Values are already applied via callbacks by default.
        // Subclasses can override for explicit save logic.
    }

    @Override
    public void reset() {
        resetDefaults();
    }

    // ---- Shared widget creation helpers ----

    /**
     * Adds a checkbox row at the given position.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param label the checkbox label
     * @param value the initial checked state
     * @param onSave callback invoked when the checkbox state changes
     * @return the y coordinate (unchanged; caller advances y manually)
     */
    protected int addCheckboxRow(int x, int y, Component label, boolean value,
                                 Consumer<Boolean> onSave) {
        return addCheckboxRow(x, y, label, value, null, onSave);
    }

    /**
     * Adds a checkbox row with an optional tooltip.
     */
    protected int addCheckboxRow(int x, int y, Component label, boolean value,
                                 Component tooltip, Consumer<Boolean> onSave) {
        Checkbox cb = new WPCheckbox(0, 0, label, value, onSave);
        if (tooltip != null) {
            cb.setTooltip(Tooltip.create(tooltip));
        }
        cb.setPosition(x, y);
        widgets.add(cb);
        return y;
    }

    /**
     * Adds a checkbox row and returns the widget, for pages that need to
     * enable/disable it from another control. The plain overload above stays
     * as the returning-y form used by every existing call site.
     */
    protected Checkbox addCheckboxRowWidget(int x, int y, Component label, boolean value,
                                            Component tooltip, Consumer<Boolean> onSave) {
        final Checkbox cb = createCheckbox(x, y, label, value, tooltip, onSave);
        widgets.add(cb);
        return cb;
    }

    /**
     * Adds a slider row and returns the widget; see
     * {@link #addCheckboxRowWidget}. Same layout as {@link #addSliderRow}.
     */
    protected AbstractSliderButton addSliderRowWidget(int x, int y, ScreenRectangle area,
                                                      Component label, int value, int min, int max,
                                                      Component tooltip, IntConsumer onSave) {
        final AbstractSliderButton slider = createSlider(x, y, area, label, value, min, max, tooltip, onSave);
        widgets.add(slider);
        return slider;
    }

    private Checkbox createCheckbox(int x, int y, Component label, boolean value,
                                    Component tooltip, Consumer<Boolean> onSave) {
        // 1.20.1 has no Checkbox.builder(); WPCheckbox bridges the constructor.
        Checkbox cb = new caeruleusTait.world.preview.client.gui.widgets.WPCheckbox(
                x, y, label, value, onSave);
        if (tooltip != null) {
            cb.setTooltip(Tooltip.create(tooltip));
        }
        cb.setPosition(x, y);
        return cb;
    }

    private AbstractSliderButton createSlider(int x, int y, ScreenRectangle area,
                                              Component label, int value, int min, int max,
                                              Component tooltip, IntConsumer onSave) {
        int labelWidth = Math.min(SettingsTheme.LABEL_WIDTH, Math.max(0, area.right() - x - 10));
        StringWidget sw = new StringWidget(x, y, labelWidth, SettingsTheme.ROW_HEIGHT,
                label, Minecraft.getInstance().font);
        widgets.add(sw);

        int sliderX = x + labelWidth + 10;
        int sliderW = Math.max(20, Math.min(SettingsTheme.CONTROL_WIDTH, area.right() - sliderX - SettingsTheme.CONTENT_PADDING));
        int range = Math.max(0, max - min);
        int clampedValue = Math.max(min, Math.min(max, value));
        AbstractSliderButton slider = new AbstractSliderButton(sliderX, y, sliderW, 20,
                Component.literal(String.valueOf(clampedValue)),
                range == 0 ? 0.0 : (double) (clampedValue - min) / range) {
            @Override
            protected void updateMessage() {
                int v = range == 0 ? min : (int) Math.round(value * range + min);
                this.setMessage(Component.literal(String.valueOf(v)));
            }

            @Override
            protected void applyValue() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                onSave.accept(Math.max(min, Math.min(max, v)));
            }
        };
        if (tooltip != null) {
            slider.setTooltip(Tooltip.create(tooltip));
        }
        return slider;
    }

    /**
     * Adds a slider row with a label and tooltip.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param area the content area for width calculation
     * @param label the slider label
     * @param value the initial value
     * @param min the minimum value
     * @param max the maximum value
     * @param tooltip optional tooltip (may be null)
     * @param onSave callback invoked when the slider value changes
     * @return the y coordinate (unchanged; caller advances y manually)
     */
    protected int addSliderRow(int x, int y, ScreenRectangle area,
                               Component label, int value, int min, int max,
                               Component tooltip, IntConsumer onSave) {
        int labelWidth = Math.min(SettingsTheme.LABEL_WIDTH, Math.max(0, area.right() - x - 10));
        StringWidget sw = new StringWidget(x, y, labelWidth, SettingsTheme.ROW_HEIGHT,
                label, Minecraft.getInstance().font).alignLeft();
        widgets.add(sw);

        int sliderX = x + labelWidth + 10;
        int sliderW = Math.max(20, Math.min(SettingsTheme.CONTROL_WIDTH, area.right() - sliderX - SettingsTheme.CONTENT_PADDING));
        int range = Math.max(0, max - min);
        int clampedValue = Math.max(min, Math.min(max, value));
        AbstractSliderButton slider = new AbstractSliderButton(sliderX, y, sliderW, 20,
                Component.literal(String.valueOf(clampedValue)),
                range == 0 ? 0.0 : (double) (clampedValue - min) / range) {
            @Override
            protected void updateMessage() {
                int v = range == 0 ? min : (int) Math.round(value * range + min);
                this.setMessage(Component.literal(String.valueOf(v)));
            }

            @Override
            protected void applyValue() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                onSave.accept(Math.max(min, Math.min(max, v)));
            }
        };
        if (tooltip != null) {
            slider.setTooltip(Tooltip.create(tooltip));
        }
        widgets.add(slider);
        return y;
    }

    /** Calculates the available content width for a full-width widget. */
    protected int contentWidth(ScreenRectangle area, int x) {
        return Math.max(20, area.right() - x - SettingsTheme.CONTENT_PADDING);
    }

    /** Calculates the width for a standard control widget. */
    protected int controlWidth(ScreenRectangle area, int x) {
        return Math.max(20, Math.min(SettingsTheme.CONTROL_WIDTH,
                area.right() - x - SettingsTheme.CONTENT_PADDING));
    }
}
