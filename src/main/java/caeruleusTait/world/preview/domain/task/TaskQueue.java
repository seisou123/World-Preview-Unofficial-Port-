package caeruleusTait.world.preview.domain.task;

import java.util.Objects;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * A priority queue for tasks with three priority levels: HIGH, MEDIUM, LOW.
 *
 * <p>Supports batch submission and delayed submission.
 * Thread-safe.
 */
public final class TaskQueue {

    /** Priority levels for tasks. */
    public enum Priority {
        HIGH(0),
        MEDIUM(1),
        LOW(2);

        private final int ordinal;

        Priority(int ordinal) {
            this.ordinal = ordinal;
        }

        public int priorityOrdinal() {
            return ordinal;
        }
    }

    private record QueuedTask(Task task, Priority priority, long sequence) implements Comparable<QueuedTask> {
        @Override
        public int compareTo(QueuedTask o) {
            int cmp = Integer.compare(priority.priorityOrdinal(), o.priority.priorityOrdinal());
            if (cmp != 0) return cmp;
            return Long.compare(sequence, o.sequence);
        }
    }

    private final PriorityBlockingQueue<QueuedTask> queue = new PriorityBlockingQueue<>();
    private final AtomicLong sequencer = new AtomicLong(0);

    /** Enqueues a task with default MEDIUM priority. */
    public void submit(Task task) {
        submit(task, Priority.MEDIUM);
    }

    /** Enqueues a task with the given priority. */
    public void submit(Task task, Priority priority) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(priority, "priority");
        queue.put(new QueuedTask(task, priority, sequencer.getAndIncrement()));
    }

    /** Enqueues multiple tasks with the same priority. */
    public void submitAll(java.util.Collection<? extends Task> tasks, Priority priority) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(priority, "priority");
        long seq = sequencer.getAndAdd(tasks.size());
        for (Task task : tasks) {
            queue.put(new QueuedTask(task, priority, seq++));
        }
    }

    /**
     * Retrieves and removes the next task, waiting up to the specified timeout.
     * Returns {@code null} if no task is available within the timeout.
     */
    public Task poll(long timeout, TimeUnit unit) throws InterruptedException {
        QueuedTask qt = queue.poll(timeout, unit);
        return qt == null ? null : qt.task();
    }

    /** Retrieves and removes the next task immediately, or returns {@code null} if empty. */
    public Task pollImmediate() {
        QueuedTask qt = queue.poll();
        return qt == null ? null : qt.task();
    }

    /** Returns the number of tasks in the queue. */
    public int size() {
        return queue.size();
    }

    /** Returns {@code true} if the queue is empty. */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Removes all tasks from the queue. */
    public void clear() {
        queue.clear();
    }
}
