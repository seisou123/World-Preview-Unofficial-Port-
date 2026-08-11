package caeruleusTait.world.preview.infra.thread;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A CAS-based cancellation signal that replaces scattered {@code volatile boolean}
 * flags across {@code WorkUnit}, {@code WorkBatch}, and {@code WorkManager}.
 *
 * <p>Thread-safe. Once cancelled, the barrier cannot be un-cancelled.
 * Listeners are notified exactly once when the barrier transitions to cancelled.
 */
public final class CancelBarrier {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Consumer<CancelBarrier>> listener = new AtomicReference<>();

    /**
     * Attempts to cancel this barrier.
     *
     * @return {@code true} if this call actually transitioned the barrier
     *         from non-cancelled to cancelled; {@code false} if it was already cancelled
     */
    public boolean cancel() {
        if (cancelled.compareAndSet(false, true)) {
            Consumer<CancelBarrier> cb = listener.getAndSet(null);
            if (cb != null) {
                cb.accept(this);
            }
            return true;
        }
        return false;
    }

    /** Returns {@code true} if this barrier has been cancelled. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Sets a listener that is invoked exactly once when the barrier is cancelled.
     * If the barrier is already cancelled, the listener is invoked immediately.
     *
     * @param cb the callback to invoke on cancellation
     */
    public void onCancel(Consumer<CancelBarrier> cb) {
        if (cb == null) return;
        if (cancelled.get()) {
            cb.accept(this);
            return;
        }
        if (!listener.compareAndSet(null, cb)) {
            // Another listener was already set; just check if we're already cancelled
            if (cancelled.get()) {
                cb.accept(this);
            }
        } else {
            // Double-check after setting
            if (cancelled.get()) {
                Consumer<CancelBarrier> existing = listener.getAndSet(null);
                if (existing != null) {
                    existing.accept(this);
                }
            }
        }
    }

    /**
     * Throws {@link CancellationException} if this barrier has been cancelled.
     *
     * @throws CancellationException if cancelled
     */
    public void checkCancelled() {
        if (cancelled.get()) {
            throw new CancellationException();
        }
    }

    /**
     * Resets the barrier to non-cancelled state.
     * <p><b>Warning:</b> This is primarily for testing. In production,
     * cancellation should be considered permanent.
     */
    void reset() {
        cancelled.set(false);
        listener.set(null);
    }

    /** Exception thrown when a cancelled barrier is checked. */
    public static class CancellationException extends RuntimeException {
        public CancellationException() {
            super("Operation cancelled via CancelBarrier");
        }
    }
}
