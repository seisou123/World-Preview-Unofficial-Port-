package caeruleusTait.world.preview.domain.ui;

import java.util.*;

/**
 * Base class for configurable settings pages.
 *
 * <p>Implements a template method pattern: subclasses declare their
 * {@link ConfigBinding}s and {@link ControlLayout}, and the base class
 * handles the control creation, validation, and persistence flow.
 *
 * <p>Replaces the ~40% duplicated code across the 6 SettingsPage classes.
 */
public abstract class ConfigurablePage {

    private final String title;
    private final PageCategory category;
    private final List<ConfigBinding<?>> bindings = new ArrayList<>();
    private final List<ConfigChangeListener> listeners = new ArrayList<>();

    protected ConfigurablePage(String title, PageCategory category) {
        this.title = Objects.requireNonNull(title, "title");
        this.category = Objects.requireNonNull(category, "category");
        if (title.isBlank()) throw new IllegalArgumentException("title must not be blank");
    }

    /** Returns the page title. */
    public String title() { return title; }

    /** Returns the page category. */
    public PageCategory category() { return category; }

    /** Registers a config binding. */
    protected <T> ConfigBinding<T> bind(ConfigBinding<T> binding) {
        Objects.requireNonNull(binding, "binding");
        bindings.add(binding);
        return binding;
    }

    /** Returns all config bindings on this page. */
    public List<ConfigBinding<?>> bindings() {
        return List.copyOf(bindings);
    }

    /** Adds a change listener. */
    public void addChangeListener(ConfigChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
    }

    /** Removes a change listener. */
    public void removeChangeListener(ConfigChangeListener listener) {
        listeners.remove(listener);
    }

    /** Notifies all listeners of a config change. */
    protected void fireConfigChange(ConfigChangeEvent event) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigChange(event);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Template method: creates the layout for this page.
     * Subclasses can override to describe how controls are arranged.
     * The default returns a single-column layout.
     */
    public ControlLayout layout() {
        return ControlLayout.column(1, 4);
    }

    /**
     * Template method: builds the controls for this page using the given factory.
     * Subclasses can override to create their specific controls via the factory.
     * The default is a no-op; platform-specific pages may build controls directly.
     */
    public void buildControls(ControlFactory factory) {
        // no-op by default
    }

    /** Validates all bindings on this page. */
    public List<String> validateBindings() {
        List<String> errors = new ArrayList<>();
        for (ConfigBinding<?> binding : bindings) {
            try {
                binding.validate();
            } catch (Exception e) {
                errors.add(binding.key() + ": " + e.getMessage());
            }
        }
        return errors;
    }

    /** Resets all bindings to their default values. */
    public void resetDefaults() {
        for (ConfigBinding<?> binding : bindings) {
            binding.reset();
        }
    }

    // ---- Objects helper (to avoid import in abstract class) ----

    private static class Objects {
        static <T> T requireNonNull(T obj, String name) {
            if (obj == null) throw new NullPointerException(name + " must not be null");
            return obj;
        }
    }
}
