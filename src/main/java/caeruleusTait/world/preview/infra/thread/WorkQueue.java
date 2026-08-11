package caeruleusTait.world.preview.infra.thread;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe priority work queue with cancellation support.
 *
 * <p>Replaces the ad-hoc {@code List<WorkBatch>} + {@code List<Future>} management
 * in {@code WorkManager}. Supports three priority levels and batch submission.
 *
 * <p>Items are ordered first by priority (HIGH > NORMAL > LOW), then by
 * insertion order (FIFO within the same priority).
 *
 * @param <T> the type of work items
 */
public class WorkQueue<T> {

    /** Priority levels for work items. */
    public enum Priority {
        HIGH(0),
        NORMAL(1),
        LOW(2);

        final int level;
        Priority(int level) { this.level = level; }
    }

    private final PriorityQueue<Entry<T>> queue;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final AtomicInteger sequence = new AtomicInteger(0);
    private final CancelBarrier cancelBarrier;

    /**
     * Creates a new work queue.
     *
     * @param cancelBarrier the cancellation barrier; when cancelled,
     *                      {@link #take()} throws {@link CancelBarrier.CancellationException}
     */
    public WorkQueue(CancelBarrier cancelBarrier) {
        this.cancelBarrier = Objects.requireNonNull(cancelBarrier, "cancelBarrier");
        this.queue = new PriorityQueue<>();
        // When the barrier is cancelled, wake up any threads blocked in take()
        this.cancelBarrier.onCancel(b -> {
            lock.lock();
            try {
                notEmpty.signalAll();
            } finally {
                lock.unlock();
            }
        });
    }

    /** Creates a new work queue with its own cancellation barrier. */
    public WorkQueue() {
        this(new CancelBarrier());
    }

    /**
     * Submits a work item with normal priority.
     *
     * @param item the work item
     */
    public void submit(T item) {
        submit(item, Priority.NORMAL);
    }

    /**
     * Submits a work item with the given priority.
     *
     * @param item the work item
     * @param priority the priority level
     */
    public void submit(T item, Priority priority) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(priority, "priority");
        Entry<T> entry = new Entry<>(item, priority, sequence.incrementAndGet());
        lock.lock();
        try {
            queue.offer(entry);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Submits multiple work items with normal priority.
     *
     * @param items the work items
     */
    public void submitAll(Collection<? extends T> items) {
        submitAll(items, Priority.NORMAL);
    }

    /**
     * Submits multiple work items with the given priority.
     *
     * @param items the work items
     * @param priority the priority level
     */
    public void submitAll(Collection<? extends T> items, Priority priority) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(priority, "priority");
        lock.lock();
        try {
            for (T item : items) {
                if (item != null) {
                    queue.offer(new Entry<>(item, priority, sequence.incrementAndGet()));
                }
            }
            if (!queue.isEmpty()) {
                notEmpty.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the next work item, blocking if necessary.
     *
     * @return the next work item
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws CancelBarrier.CancellationException if the cancel barrier is triggered
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (cancelBarrier.isCancelled()) {
                    throw new CancelBarrier.CancellationException();
                }
                notEmpty.await();
            }
            if (cancelBarrier.isCancelled()) {
                throw new CancelBarrier.CancellationException();
            }
            return queue.remove().item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the next work item, or returns {@code null} if the queue is empty.
     *
     * @return the next work item, or {@code null} if empty
     */
    public T poll() {
        lock.lock();
        try {
            Entry<T> entry = queue.poll();
            return entry != null ? entry.item : null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes up to {@code maxItems} work items.
     *
     * @param maxItems the maximum number of items to retrieve
     * @return a list of work items (may be smaller than {@code maxItems})
     */
    public List<T> drain(int maxItems) {
        if (maxItems <= 0) return List.of();
        List<T> result = new ArrayList<>(maxItems);
        lock.lock();
        try {
            while (result.size() < maxItems) {
                Entry<T> entry = queue.poll();
                if (entry == null) break;
                result.add(entry.item);
            }
        } finally {
            lock.unlock();
        }
        return result;
    }

    /** Returns the number of items currently in the queue. */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /** Returns {@code true} if the queue contains no items. */
    public boolean isEmpty() {
        return size() == 0;
    }

    /** Removes all items from the queue. */
    public void clear() {
        lock.lock();
        try {
            queue.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a snapshot of all items in the queue (does not remove them).
     *
     * @return an unmodifiable list of items in priority order
     */
    public List<T> snapshot() {
        lock.lock();
        try {
            List<T> result = new ArrayList<>(queue.size());
            for (Entry<T> entry : queue) {
                result.add(entry.item);
            }
            return List.copyOf(result);
        } finally {
            lock.unlock();
        }
    }

    /** Returns the cancellation barrier associated with this queue. */
    public CancelBarrier cancelBarrier() {
        return cancelBarrier;
    }

    // ---- Internal entry class ----

    private static final class Entry<T> implements Comparable<Entry<T>> {
        final T item;
        final Priority priority;
        final int seq;

        Entry(T item, Priority priority, int seq) {
            this.item = item;
            this.priority = priority;
            this.seq = seq;
        }

        @Override
        public int compareTo(Entry<T> o) {
            int cmp = Integer.compare(priority.level, o.priority.level);
            return cmp != 0 ? cmp : Integer.compare(seq, o.seq);
        }
    }
}
