package caeruleusTait.world.preview.domain.ui;

/**
 * Listener for configuration changes.
 */
@FunctionalInterface
public interface ConfigChangeListener {
    void onConfigChange(ConfigChangeEvent event);
}
