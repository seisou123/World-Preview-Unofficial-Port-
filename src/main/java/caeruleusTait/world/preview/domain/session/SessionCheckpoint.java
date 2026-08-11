package caeruleusTait.world.preview.domain.session;

import java.util.List;
import java.util.Objects;

/**
 * Immutable checkpoint snapshot of a session's state for crash recovery.
 *
 * @param sessionId    the session id
 * @param state        the session state at checkpoint time
 * @param cursor       the current progress cursor (domain-specific)
 * @param totalCount   the total units to process
 * @param stage        human-readable stage name
 * @param errors       accumulated error messages
 * @param timestampMs  wall-clock time of the checkpoint
 */
public record SessionCheckpoint(
        String sessionId,
        SessionState state,
        long cursor,
        long totalCount,
        String stage,
        List<String> errors,
        long timestampMs
) {

    public SessionCheckpoint {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(stage, "stage");
        errors = errors == null ? List.of() : List.copyOf(errors);
        if (cursor < 0) cursor = 0;
        if (totalCount < 0) totalCount = 0;
    }

    /** Creates an initial checkpoint for a newly created session. */
    public static SessionCheckpoint initial(String sessionId, long totalCount) {
        return new SessionCheckpoint(
                sessionId, SessionState.CREATED, 0, totalCount,
                "created", List.of(), System.currentTimeMillis()
        );
    }

    /** Returns a new checkpoint with an updated cursor and state. */
    public SessionCheckpoint withProgress(long newCursor, SessionState newState, String newStage) {
        return new SessionCheckpoint(
                sessionId, newState, newCursor, totalCount,
                newStage, errors, System.currentTimeMillis()
        );
    }

    /** Returns a new checkpoint with an additional error. */
    public SessionCheckpoint withError(String error) {
        if (error == null || error.isBlank()) return this;
        java.util.List<String> newErrors = new java.util.ArrayList<>(errors);
        newErrors.add(error);
        return new SessionCheckpoint(
                sessionId, state, cursor, totalCount,
                stage, List.copyOf(newErrors), System.currentTimeMillis()
        );
    }
}
