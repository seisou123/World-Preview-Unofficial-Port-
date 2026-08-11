package caeruleusTait.world.preview.viewmodel;

import caeruleusTait.world.preview.domain.task.TaskId;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * View model for the world analysis screen.
 *
 * <p>Decouples the UI from the facade and backend analysis engine, providing
 * observable state for the analysis session, region selection, and metrics.
 */
public final class AnalysisViewModel {

    /** Callback type for state change notifications. */
    public interface StateListener extends Consumer<AnalysisViewModel> {}

    private final AtomicReference<AnalysisState> state = new AtomicReference<>(AnalysisState.IDLE);
    private final AtomicReference<String> errorMessage = new AtomicReference<>();
    private final AtomicReference<TaskId> taskId = new AtomicReference<>();

    private volatile RegionSelection region = new RegionSelection(0, 0, 16, 16);
    private volatile long analyzedChunks;
    private volatile long totalChunks;
    private volatile float progress;
    private volatile MetricsSnapshot lastMetrics;

    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();

    /** Analysis state machine. */
    public enum AnalysisState {
        IDLE,
        ANALYZING,
        PAUSED,
        COMPLETED,
        ERROR
    }

    /** Immutable region selection. */
    public record RegionSelection(int minX, int minZ, int maxX, int maxZ) {
        public RegionSelection {
            if (minX > maxX) throw new IllegalArgumentException("minX > maxX");
            if (minZ > maxZ) throw new IllegalArgumentException("minZ > maxZ");
        }

        public int width() { return maxX - minX + 1; }
        public int height() { return maxZ - minZ + 1; }
        public int chunkCount() {
            int cw = (width() + 15) / 16;
            int ch = (height() + 15) / 16;
            return cw * ch;
        }
    }

    /** Immutable metrics snapshot. */
    public record MetricsSnapshot(
            double meanHeight,
            double medianHeight,
            double minHeight,
            double maxHeight,
            int biomeCount,
            int structureCount
    ) {}

    // ---- State ----

    public AnalysisState state() { return state.get(); }
    public void setState(AnalysisState newState) {
        Objects.requireNonNull(newState, "newState");
        state.set(newState);
        notifyListeners();
    }

    public String errorMessage() { return errorMessage.get(); }
    public void setError(String message) {
        errorMessage.set(message);
        setState(AnalysisState.ERROR);
    }

    public void clearError() {
        errorMessage.set(null);
        setState(AnalysisState.IDLE);
    }

    // ---- Region ----

    public RegionSelection region() { return region; }
    public void setRegion(RegionSelection newRegion) {
        region = Objects.requireNonNull(newRegion, "newRegion");
        notifyListeners();
    }

    // ---- Progress ----

    public TaskId taskId() { return taskId.get(); }
    public void setTaskId(TaskId id) { taskId.set(id); notifyListeners(); }

    public long analyzedChunks() { return analyzedChunks; }
    public long totalChunks() { return totalChunks; }
    public float progress() { return progress; }

    public void setProgress(long analyzed, long total, float progress) {
        this.analyzedChunks = analyzed;
        this.totalChunks = total;
        this.progress = Math.max(0.0f, Math.min(1.0f, progress));
        notifyListeners();
    }

    // ---- Metrics ----

    public MetricsSnapshot lastMetrics() { return lastMetrics; }
    public void setLastMetrics(MetricsSnapshot metrics) {
        lastMetrics = metrics;
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
        state.set(AnalysisState.IDLE);
        errorMessage.set(null);
        taskId.set(null);
        analyzedChunks = 0;
        totalChunks = 0;
        progress = 0.0f;
        lastMetrics = null;
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
