package caeruleusTait.world.preview.domain.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Base class for all "long-running" objects: analysis sessions, etc.
 *
 * <p>Provides:
 * <ul>
 *   <li>A unique string id</li>
 *   <li>A session type (for classification)</li>
 *   <li>A context {@link SessionFingerprint}</li>
 *   <li>A thread-safe {@link SessionState state machine}</li>
 *   <li>Lifecycle {@link SessionHook hooks}</li>
 * </ul>
 */
public abstract class Session implements AutoCloseable {

    private final String id;
    private final String type;
    private final SessionFingerprint fingerprint;
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CREATED);
    private final List<SessionHook> hooks = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    protected Session(String id, String type, SessionFingerprint fingerprint) {
        this.id = requireText(id, "id");
        this.type = requireText(type, "type");
        this.fingerprint = fingerprint; // may be null
    }

    // ---- Identity ----

    public String id() {
        return id;
    }

    public String type() {
        return type;
    }

    public SessionFingerprint fingerprint() {
        return fingerprint;
    }

    // ---- State management ----

    public SessionState state() {
        return state.get();
    }

    /**
     * Transitions the session to the target state.
     *
     * @throws SessionTransitionException if the transition is illegal
     */
    protected SessionState transitionTo(SessionState target) {
        ensureOpen();
        SessionState current;
        do {
            current = state.get();
            current.requireTransitionTo(target);
        } while (!state.compareAndSet(current, target));
        notifyHooks(target, current);
        return target;
    }

    /** Attempts to transition to the target state, returns {@code false} if not allowed. */
    protected boolean tryTransitionTo(SessionState target) {
        ensureOpen();
        SessionState current;
        do {
            current = state.get();
            if (!current.canTransitionTo(target)) return false;
        } while (!state.compareAndSet(current, target));
        notifyHooks(target, current);
        return true;
    }

    /**
     * Directly sets the session state, bypassing transition validation.
     * Intended for subclasses that manage their own state machine and need
     * to synchronize the Session state with an external status enum.
     */
    protected void setStateDirect(SessionState target) {
        SessionState current = state.getAndSet(target);
        if (current != target) {
            notifyHooks(target, current);
        }
    }

    // ---- Lifecycle ----

    /** Starts the session (CREATED → RUNNING). */
    public void start() {
        transitionTo(SessionState.RUNNING);
    }

    /** Pauses the session (RUNNING → PAUSED). */
    public void pause() {
        tryTransitionTo(SessionState.PAUSED);
    }

    /** Resumes the session (PAUSED → RUNNING). */
    public void resume() {
        tryTransitionTo(SessionState.RUNNING);
    }

    /** Cancels the session (any non-terminal → CANCELLED). */
    public void cancel() {
        tryTransitionTo(SessionState.CANCELLED);
    }

    /** Marks the session as completed (RUNNING → COMPLETED). */
    protected void complete() {
        transitionTo(SessionState.COMPLETED);
    }

    /** Marks the session as failed (any non-terminal → FAILED). */
    protected void fail(Throwable cause) {
        if (cause != null) {
            addError(cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
        tryTransitionTo(SessionState.FAILED);
    }

    // ---- Errors ----

    public List<String> errors() {
        return List.copyOf(errors);
    }

    public void addError(String error) {
        if (error != null && !error.isBlank()) {
            errors.add(error);
        }
    }

    // ---- Hooks ----

    public void addHook(SessionHook hook) {
        Objects.requireNonNull(hook, "hook");
        hooks.add(hook);
    }

    public void removeHook(SessionHook hook) {
        hooks.remove(hook);
    }

    private void notifyHooks(SessionState newState, SessionState oldState) {
        for (SessionHook hook : hooks) {
            try {
                switch (newState) {
                    case RUNNING -> {
                        if (oldState == SessionState.PAUSED) hook.onResume(this);
                        else hook.onStart(this);
                    }
                    case PAUSED -> hook.onPause(this);
                    case COMPLETED -> hook.onComplete(this);
                    case CANCELLED -> hook.onCancel(this);
                    case FAILED -> hook.onFailure(this, null);
                    default -> {}
                }
            } catch (Exception e) {
                // Hook failures should never block session transitions
            }
        }
    }

    // ---- Checkpoint ----

    /**
     * Creates a checkpoint snapshot of the current session state.
     * Subclasses should override to include domain-specific cursor data.
     */
    public abstract SessionCheckpoint checkpoint();

    // ---- Close ----

    public boolean isClosed() {
        return closed;
    }

    protected void ensureOpen() {
        if (closed) throw new IllegalStateException("session is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (!state.get().isTerminal()) {
            // BUG FIX: Use setStateDirect instead of tryTransitionTo to avoid
            // ensureOpen() throwing IllegalStateException — closed is already true.
            setStateDirect(SessionState.CANCELLED);
        }
        for (SessionHook hook : hooks) {
            try {
                hook.onClose(this);
            } catch (Exception ignored) {
            }
        }
    }

    // ---- Helpers ----

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
