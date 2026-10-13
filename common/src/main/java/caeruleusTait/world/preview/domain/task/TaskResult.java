package caeruleusTait.world.preview.domain.task;

import java.util.Objects;
import java.util.Optional;

/**
 * Generic result of a task execution.
 *
 * <p>Supports four variants:
 * <ul>
 *   <li>{@link #success(Object)} — task completed successfully with a result value</li>
 *   <li>{@link #error(Throwable)} — task failed with an error</li>
 *   <li>{@link #partial(Object)} — task was cancelled/paused but produced partial results</li>
 *   <li>{@link #skipped()} — task was skipped (e.g. already completed by another)</li>
 * </ul>
 */
public sealed interface TaskResult<T> permits TaskResult.Success, TaskResult.Error, TaskResult.Partial, TaskResult.Skipped {

    /** Returns {@code true} if this result represents a successful outcome. */
    boolean isSuccess();

    /** Returns {@code true} if this result represents a failure. */
    boolean isError();

    /** Returns {@code true} if this result represents a partial outcome. */
    boolean isPartial();

    /** Returns {@code true} if this result was skipped. */
    boolean isSkipped();

    /** Returns the value if successful or partial, otherwise empty. */
    Optional<T> value();

    /** Returns the error if failed, otherwise empty. */
    Optional<Throwable> error();

    record Success<T>(T data) implements TaskResult<T> {
        public Success {
            data = Objects.requireNonNull(data, "data");
        }

        @Override public boolean isSuccess() { return true; }
        @Override public boolean isError() { return false; }
        @Override public boolean isPartial() { return false; }
        @Override public boolean isSkipped() { return false; }
        @Override public Optional<T> value() { return Optional.of(data); }
        @Override public Optional<Throwable> error() { return Optional.empty(); }
    }

    record Error<T>(Throwable cause) implements TaskResult<T> {
        public Error {
            cause = Objects.requireNonNull(cause, "cause");
        }

        @Override public boolean isSuccess() { return false; }
        @Override public boolean isError() { return true; }
        @Override public boolean isPartial() { return false; }
        @Override public boolean isSkipped() { return false; }
        @Override public Optional<T> value() { return Optional.empty(); }
        @Override public Optional<Throwable> error() { return Optional.of(cause); }
    }

    record Partial<T>(T data) implements TaskResult<T> {
        public Partial {
            data = Objects.requireNonNull(data, "data");
        }

        @Override public boolean isSuccess() { return false; }
        @Override public boolean isError() { return false; }
        @Override public boolean isPartial() { return true; }
        @Override public boolean isSkipped() { return false; }
        @Override public Optional<T> value() { return Optional.of(data); }
        @Override public Optional<Throwable> error() { return Optional.empty(); }
    }

    record Skipped<T>() implements TaskResult<T> {
        @Override public boolean isSuccess() { return false; }
        @Override public boolean isError() { return false; }
        @Override public boolean isPartial() { return false; }
        @Override public boolean isSkipped() { return true; }
        @Override public Optional<T> value() { return Optional.empty(); }
        @Override public Optional<Throwable> error() { return Optional.empty(); }
    }

    // ---- Factory methods ----

    static <T> TaskResult<T> success(T value) {
        return new Success<>(value);
    }

    static <T> TaskResult<T> error(Throwable cause) {
        return new Error<>(cause);
    }

    static <T> TaskResult<T> partial(T value) {
        return new Partial<>(value);
    }

    static <T> TaskResult<T> skipped() {
        return new Skipped<>();
    }
}
