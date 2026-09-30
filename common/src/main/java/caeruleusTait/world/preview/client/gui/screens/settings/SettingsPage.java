package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.client.gui.screens.PreviewContainer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;

import java.util.List;

/**
 * A single settings category page within SettingsScreen.
 * Each page builds its own widgets, saves pending changes, and can reset to defaults.
 */
public interface SettingsPage {

    /**
     * Build all widgets for this page into the given content area.
     * Called when the page is first shown and whenever it needs to be rebuilt.
     */
    void build(ScreenRectangle contentArea, PreviewContainer previewContainer);

    /**
     * Return all widgets created by {@link #build} so SettingsScreen can add/remove them.
     */
    List<AbstractWidget> widgets();

    /**
     * Apply this page's pending values to the real config/render settings.
     * Called when the user clicks Done in SettingsScreen.
     */
    void save();

    /**
     * Reset this page's pending values to hardcoded defaults.
     */
    void reset();

    /**
     * Validate pending values before they are committed.
     */
    default boolean validate() {
        return true;
    }

    /**
     * Called every render tick so the page can update widget state
     * (e.g. enabling/disabling based on other settings).
     */
    default void tick() {}
}
