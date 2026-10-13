package caeruleusTait.world.preview.domain.task;

/**
 * Lifecycle states for a task.
 *
 * <p>Allowed transitions (no backward transitions except explicit reset):
 * <pre>
 *   QUEUED → PENDING_START → RUNNING → COMPLETED
 *                          ↘          ↗ PAUSED → RUNNING
 *                            ↘        ↘ CANCELLED
 *                              FAILED
 * </pre>
 * Any state can transition to CANCELLED or FAILED.
 */
public enum TaskState {
    QUEUED,
    PENDING_START,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED;

    /** Returns {@code true} if this state is terminal (no further transitions). */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }

    /** Returns {@code true} if this state allows the task to make progress. */
    public boolean isActive() {
        return this == RUNNING || this == PENDING_START || this == QUEUED;
    }

    /**
     * Validates that transitioning from {@code this} to {@code target} is legal.
     *
     * @throws TaskTransitionException if the transition is not allowed
     */
    public TaskState requireTransitionTo(TaskState target) {
        if (!canTransitionTo(target)) {
            throw new TaskTransitionException(this, target);
        }
        return target;
    }

    /**
     * Checks if transitioning from {@code this} to {@code target} is legal.
     */
    public boolean canTransitionTo(TaskState target) {
        if (this == target) return true;
        if (isTerminal()) return false;
        return switch (this) {
            case QUEUED -> target == PENDING_START || target == RUNNING || target == CANCELLED || target == FAILED;
            case PENDING_START -> target == RUNNING || target == PAUSED || target == CANCELLED || target == FAILED;
            case RUNNING -> target == PAUSED || target == COMPLETED || target == CANCELLED || target == FAILED;
            case PAUSED -> target == RUNNING || target == CANCELLED || target == FAILED;
            default -> false;
        };
    }
}
