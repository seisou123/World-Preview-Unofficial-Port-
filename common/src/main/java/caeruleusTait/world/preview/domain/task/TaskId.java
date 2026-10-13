package caeruleusTait.world.preview.domain.task;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable unique identifier for a task, combining a UUID with creation time.
 * Used for cross-system tracing.
 */
public record TaskId(UUID uuid, long createdAtNanos) implements Comparable<TaskId> {

    public TaskId {
        Objects.requireNonNull(uuid, "uuid");
    }

    /** Creates a new unique task id. */
    public static TaskId generate() {
        return new TaskId(UUID.randomUUID(), System.nanoTime());
    }

    /** Creates a task id from an existing UUID (e.g. during recovery). */
    public static TaskId of(UUID uuid) {
        return new TaskId(uuid, System.nanoTime());
    }

    /** Creates a task id from a string representation. */
    public static TaskId parse(String value) {
        Objects.requireNonNull(value, "value");
        return of(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return uuid.toString();
    }

    @Override
    public int compareTo(TaskId o) {
        return uuid.compareTo(o.uuid);
    }
}
