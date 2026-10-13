package caeruleusTait.world.preview.domain.session;

/**
 * Lifecycle states for a session.
 *
 * <p>Allowed transitions:
 * <pre>
 *   CREATED → RUNNING → COMPLETED
 *                   ↕       ↗
 *                 PAUSED
 *                   ↓
 *               CANCELLED / FAILED (from any non-terminal state)
 * </pre>
 */
public enum SessionState {
    CREATED,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED;

    /** Returns {@code true} if this state is terminal. */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }

    /** Returns {@code true} if this state indicates active processing. */
    public boolean isActive() {
        return this == RUNNING;
    }

    /**
     * Validates that transitioning from {@code this} to {@code target} is legal.
     *
     * @throws SessionTransitionException if the transition is not allowed
     */
    public SessionState requireTransitionTo(SessionState target) {
        if (!canTransitionTo(target)) {
            throw new SessionTransitionException(this, target);
        }
        return target;
    }

    /** Checks if transitioning from {@code this} to {@code target} is legal. */
    public boolean canTransitionTo(SessionState target) {
        if (this == target) return true;
        if (isTerminal()) return false;
        return switch (this) {
            case CREATED -> target == RUNNING || target == PAUSED || target == CANCELLED || target == FAILED;
            case RUNNING -> target == PAUSED || target == COMPLETED || target == CANCELLED || target == FAILED;
            case PAUSED -> target == RUNNING || target == CANCELLED || target == FAILED;
            default -> false;
        };
    }
}
