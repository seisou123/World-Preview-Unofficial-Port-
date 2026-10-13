package caeruleusTait.world.preview.domain.task;

/**
 * Core task interface — the Liskov substitution principle (LSP) implementation
 * for all task types (WorkUnit, analysis tasks).
 *
 * <p>Every task has:
 * <ul>
 *   <li>A unique {@link TaskId}</li>
 *   <li>A {@link TaskState lifecycle state}</li>
 *   <li>A {@link TaskProgress progress} snapshot</li>
 *   <li>A {@link TaskResult result} when finished</li>
 * </ul>
 */
public interface Task {

    /** Returns the unique identifier of this task. */
    TaskId id();

    /** Returns the current lifecycle state. */
    TaskState state();

    /** Returns an immutable progress snapshot. */
    TaskProgress progress();

    /** Returns the result if the task is in a terminal state, or empty if not yet available. */
    java.util.Optional<TaskResult<?>> result();

    /** Requests cancellation of this task. Idempotent and safe to call from any thread. */
    void cancel();

    /** Returns {@code true} if cancellation has been requested. */
    boolean isCancelled();

    /** Requests the task to pause. No-op if the task is not running. */
    void pause();

    /** Requests the task to resume from a paused state. */
    void resume();

    /** Returns {@code true} if the task is paused. */
    boolean isPaused();
}
