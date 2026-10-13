package caeruleusTait.world.preview.domain.task;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
     * Task scheduler: submits, cancels, pauses, resumes and waits for tasks.
     *
     * <p>Internally maintains an {@link ExecutorService} and replaces the
     * previous production-only {@code AnalysisScheduler} implementation.
     */
    public final class TaskScheduler implements AutoCloseable {
        private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    /**
     * Functional interface for a unit of work that receives a unit index
     * and a cancellation check.
     */
    @FunctionalInterface
    public interface WorkUnit {
        void run(long unit, BooleanSupplier cancelled) throws Exception;
    }

    /** Handle to a submitted task. */
    public record TaskHandle(TaskId id) {
        public TaskHandle {
            Objects.requireNonNull(id, "id");
        }

        public UUID uuid() {
            return id.uuid();
        }
    }

    // ---- Internal task state ----

    private static final class InternalTask {
        final TaskHandle handle;
        final long totalUnits;
        final WorkUnit workUnit;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean paused = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicLong nextUnit = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong completedUnits = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong sampledPoints = new java.util.concurrent.atomic.AtomicLong();
        final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.QUEUED);
        final java.util.Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
        volatile String stage = "queued";
        volatile String error;

        InternalTask(long totalUnits, WorkUnit workUnit) {
            this.handle = new TaskHandle(TaskId.generate());
            this.totalUnits = totalUnits;
            this.workUnit = workUnit;
        }
    }

    private final ExecutorService executor;
    private final int parallelism;
    private final java.util.List<InternalTask> tasks = new java.util.ArrayList<>();
    private volatile boolean closed;

    public TaskScheduler(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be at least 1");
        }
        this.parallelism = parallelism;
        this.executor = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "task-scheduler-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public TaskHandle submit(long totalUnits, WorkUnit workUnit) {
        if (totalUnits < 0) {
            throw new IllegalArgumentException("totalUnits must not be negative");
        }
        Objects.requireNonNull(workUnit, "workUnit");
        ensureOpen();
        InternalTask task = new InternalTask(totalUnits, workUnit);
        synchronized (tasks) {
            tasks.add(task);
        }
        if (totalUnits == 0) {
            task.state.set(TaskState.COMPLETED);
            task.stage = "complete";
        } else {
            for (int i = 0; i < parallelism; i++) {
                executor.submit(() -> runWorker(task));
            }
        }
        return task.handle;
    }

    public void pause(TaskHandle handle) {
        InternalTask task = task(handle);
        synchronized (task) {
            if (task.state.get().isTerminal()) return;
            task.paused.set(true);
            task.state.set(TaskState.PAUSED);
        }
    }

    public void resume(TaskHandle handle) {
        InternalTask task = task(handle);
        synchronized (task) {
            if (task.state.get().isTerminal()) return;
            task.paused.set(false);
            task.state.set(TaskState.QUEUED);
            task.notifyAll();
        }
    }

    public void cancel(TaskHandle handle) {
        InternalTask task = task(handle);
        synchronized (task) {
            if (task.state.get().isTerminal()) return;
            task.cancelled.set(true);
            task.state.set(TaskState.CANCELLED);
            task.notifyAll();
        }
        task.workerThreads.forEach(Thread::interrupt);
    }

    public TaskProgress snapshot(TaskHandle handle) {
        InternalTask task = task(handle);
        long completed = task.completedUnits.get();
        return new TaskProgress(
                completed,
                task.totalUnits,
                task.sampledPoints.get(),
                task.stage
        );
    }

    /** Returns the current state of a task. */
    public TaskState stateOf(TaskHandle handle) {
        return task(handle).state.get();
    }

    /** Returns the error message if the task failed, or {@code null}. */
    public String errorOf(TaskHandle handle) {
        return task(handle).error;
    }

    /** Waits for a task to reach a terminal state, up to the given timeout. */
    public boolean awaitTerminal(TaskHandle handle, long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadlineNanos) {
            if (task(handle).state.get().isTerminal()) return true;
            Thread.sleep(10);
        }
        return task(handle).state.get().isTerminal();
    }

    private void runWorker(InternalTask task) {
        Thread worker = Thread.currentThread();
        task.workerThreads.add(worker);
        try {
            while (true) {
                long unit;
                synchronized (task) {
                    while (task.paused.get() && !task.cancelled.get()) {
                        task.state.set(TaskState.PAUSED);
                        try {
                            task.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            if (!task.cancelled.get()) {
                                task.cancelled.set(true);
                                task.state.set(TaskState.CANCELLED);
                            }
                            return;
                        }
                    }
                    if (task.cancelled.get() || task.state.get().isTerminal()) return;
                    unit = task.nextUnit.getAndIncrement();
                    if (unit >= task.totalUnits) return;
                    task.state.set(TaskState.RUNNING);
                    task.stage = "unit " + unit;
                }

                try {
                    task.workUnit.run(unit, task.cancelled::get);
                } catch (Throwable throwable) {
                    if (!task.cancelled.get()) {
                        task.error = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
                        task.state.set(TaskState.FAILED);
                        task.cancelled.set(true);
                        synchronized (task) {
                            task.notifyAll();
                        }
                        task.workerThreads.forEach(Thread::interrupt);
                    }
                    return;
                }
                if (task.cancelled.get()) return;
                long completed = task.completedUnits.incrementAndGet();
                if (completed >= task.totalUnits) {
                    task.state.set(TaskState.COMPLETED);
                    task.stage = "complete";
                    synchronized (task) {
                        task.notifyAll();
                    }
                    return;
                }
            }
        } finally {
            task.workerThreads.remove(worker);
        }
    }

    private InternalTask task(TaskHandle handle) {
        Objects.requireNonNull(handle, "handle");
        synchronized (tasks) {
            return tasks.stream()
                    .filter(task -> task.handle.equals(handle))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown task: " + handle.id()));
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("scheduler is closed");
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        java.util.List<InternalTask> currentTasks;
        synchronized (tasks) {
            currentTasks = new java.util.ArrayList<>(tasks);
        }
        currentTasks.forEach(task -> cancel(task.handle));
        executor.shutdownNow();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // No logger dependency in domain layer; silent timeout is acceptable.
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
