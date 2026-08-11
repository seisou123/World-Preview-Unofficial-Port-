package caeruleusTait.world.preview.domain.session;

/**
 * Lifecycle hook interface for sessions.
 *
 * <p>External systems (UI refresh, logging, metrics) can register hooks
 * to be notified of session lifecycle events.
 */
public interface SessionHook {

    /** Called when a session transitions to RUNNING. */
    default void onStart(Session session) {}

    /** Called when a session transitions to PAUSED. */
    default void onPause(Session session) {}

    /** Called when a session resumes from PAUSED to RUNNING. */
    default void onResume(Session session) {}

    /** Called when a session completes successfully. */
    default void onComplete(Session session) {}

    /** Called when a session is cancelled. */
    default void onCancel(Session session) {}

    /** Called when a session fails. */
    default void onFailure(Session session, Throwable cause) {}

    /** Called when a session is closed (releases resources). */
    default void onClose(Session session) {}
}
