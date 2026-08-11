package caeruleusTait.world.preview.viewmodel;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * View model for the settings screen.
 *
 * <p>Decouples the settings UI from the configuration classes, providing
 * observable state for all settings pages and validation feedback.
 */
public final class SettingsViewModel {

    /** Callback type for state change notifications. */
    public interface StateListener extends Consumer<SettingsViewModel> {}

    private volatile boolean dirty;
    private volatile boolean valid = true;
    private volatile String validationError;
    private volatile String activePage = "general";

    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();

    // ---- Validation ----

    public boolean dirty() { return dirty; }
    public void setDirty(boolean value) { dirty = value; notifyListeners(); }

    public boolean valid() { return valid; }
    public void setValid(boolean value, String error) {
        valid = value;
        validationError = error;
        notifyListeners();
    }

    public String validationError() { return validationError; }

    // ---- Page navigation ----

    public String activePage() { return activePage; }
    public void setActivePage(String page) {
        activePage = Objects.requireNonNull(page, "page");
        notifyListeners();
    }

    // ---- Listeners ----

    public void addListener(StateListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
    }

    public void removeListener(StateListener listener) {
        listeners.remove(listener);
    }

    // ---- Reset ----

    public void reset() {
        dirty = false;
        valid = true;
        validationError = null;
        activePage = "general";
        notifyListeners();
    }

    private void notifyListeners() {
        for (StateListener listener : listeners) {
            try {
                listener.accept(this);
            } catch (Exception ignored) {
                // Listener failures should not affect the view model
            }
        }
    }
}
