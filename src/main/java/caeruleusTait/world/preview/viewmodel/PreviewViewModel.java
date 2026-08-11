package caeruleusTait.world.preview.viewmodel;

import caeruleusTait.world.preview.domain.preview.Preview;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * View model for the in-game preview screen.
 *
 * <p>Decouples the UI from the facade and backend layers, providing
 * observable state that the preview screen can bind to. The view model
 * holds the current preview state, progress, and error information,
 * and notifies listeners when state changes.
 */
public final class PreviewViewModel {

    /** Callback type for state change notifications. */
    public interface StateListener extends Consumer<PreviewViewModel> {}

    private final AtomicReference<Preview> currentPreview = new AtomicReference<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> errorMessage = new AtomicReference<>();
    private volatile float progress;
    private volatile long sampledChunks;
    private volatile long totalChunks;

    private final java.util.List<StateListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Current preview state.
     */
    public enum State {
        IDLE,
        LOADING,
        GENERATING,
        READY,
        ERROR
    }

    /**
     * Returns the current preview.
     *
     * @return the current preview, or {@code null} if none
     */
    public Preview currentPreview() {
        return currentPreview.get();
    }

    /**
     * Sets the current preview.
     *
     * @param preview the new preview
     */
    public void setCurrentPreview(Preview preview) {
        currentPreview.set(preview);
        notifyListeners();
    }

    /**
     * Returns the current state.
     *
     * @return the current state
     */
    public State state() {
        return state.get();
    }

    /**
     * Sets the current state.
     *
     * @param newState the new state
     */
    public void setState(State newState) {
        Objects.requireNonNull(newState, "newState");
        state.set(newState);
        notifyListeners();
    }

    /**
     * Returns the current error message, if any.
     *
     * @return the error message, or {@code null} if none
     */
    public String errorMessage() {
        return errorMessage.get();
    }

    /**
     * Sets the error message and transitions to ERROR state.
     *
     * @param message the error message
     */
    public void setError(String message) {
        errorMessage.set(message);
        setState(State.ERROR);
    }

    /**
     * Clears the error message and transitions to IDLE state.
     */
    public void clearError() {
        errorMessage.set(null);
        setState(State.IDLE);
    }

    /**
     * Returns the current progress (0.0 to 1.0).
     *
     * @return the progress value
     */
    public float progress() {
        return progress;
    }

    /**
     * Sets the current progress.
     *
     * @param progress the progress value (0.0 to 1.0)
     */
    public void setProgress(float progress) {
        this.progress = Math.max(0.0f, Math.min(1.0f, progress));
        notifyListeners();
    }

    /**
     * Returns the number of sampled chunks.
     *
     * @return the sampled chunk count
     */
    public long sampledChunks() {
        return sampledChunks;
    }

    /**
     * Returns the total number of chunks to sample.
     *
     * @return the total chunk count
     */
    public long totalChunks() {
        return totalChunks;
    }

    /**
     * Sets the chunk progress.
     *
     * @param sampled the number of sampled chunks
     * @param total the total number of chunks
     */
    public void setChunkProgress(long sampled, long total) {
        this.sampledChunks = sampled;
        this.totalChunks = total;
        if (total > 0) {
            setProgress((float) sampled / total);
        }
        notifyListeners();
    }

    /**
     * Registers a listener that will be notified when the view model state changes.
     *
     * @param listener the listener to register
     */
    public void addListener(StateListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
    }

    /**
     * Unregisters a previously registered listener.
     *
     * @param listener the listener to unregister
     */
    public void removeListener(StateListener listener) {
        listeners.remove(listener);
    }

    /**
     * Resets the view model to its initial state.
     */
    public void reset() {
        currentPreview.set(null);
        state.set(State.IDLE);
        errorMessage.set(null);
        progress = 0.0f;
        sampledChunks = 0;
        totalChunks = 0;
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
