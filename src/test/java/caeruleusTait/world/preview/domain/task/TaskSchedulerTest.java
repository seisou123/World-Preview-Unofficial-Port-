package caeruleusTait.world.preview.domain.task;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskSchedulerTest {

    @Test
    void submitCompletesAllUnits() throws Exception {
        try (TaskScheduler scheduler = new TaskScheduler(2)) {
            AtomicInteger completed = new AtomicInteger();
            TaskScheduler.TaskHandle handle = scheduler.submit(10, (unit, cancelled) -> {
                completed.incrementAndGet();
            });

            assertTrue(awaitTerminal(scheduler, handle, 3));
            assertEquals(10, completed.get());
            assertEquals(TaskState.COMPLETED, scheduler.stateOf(handle));
        }
    }

    @Test
    void pauseStopsNewWorkUnitsAndResumeCompletesAll() throws Exception {
        try (TaskScheduler scheduler = new TaskScheduler(2)) {
            List<Integer> started = new CopyOnWriteArrayList<>();
            CountDownLatch firstTwoStarted = new CountDownLatch(2);
            CountDownLatch releaseFirstTwo = new CountDownLatch(1);
            AtomicInteger completed = new AtomicInteger();

            TaskScheduler.TaskHandle task = scheduler.submit(5, (unit, cancelled) -> {
                started.add((int) unit);
                if (unit < 2) {
                    firstTwoStarted.countDown();
                    releaseFirstTwo.await();
                }
                completed.incrementAndGet();
            });

            assertTrue(firstTwoStarted.await(2, TimeUnit.SECONDS));
            scheduler.pause(task);
            releaseFirstTwo.countDown();
            Thread.sleep(100);
            assertEquals(2, started.size());

            scheduler.resume(task);
            assertTrue(awaitTerminal(scheduler, task, 3));
            started.sort(Integer::compareTo);
            assertEquals(List.of(0, 1, 2, 3, 4), started);
            assertEquals(5, completed.get());
        }
    }

    @Test
    void cancelReachesCancelledWithoutWaitingForUnboundedTask() throws Exception {
        try (TaskScheduler scheduler = new TaskScheduler(1)) {
            CountDownLatch started = new CountDownLatch(1);
            TaskScheduler.TaskHandle task = scheduler.submit(1, (unit, cancelled) -> {
                started.countDown();
                while (!cancelled.getAsBoolean()) {
                    Thread.onSpinWait();
                }
            });

            assertTrue(started.await(2, TimeUnit.SECONDS));
            scheduler.cancel(task);
            assertTrue(awaitTerminal(scheduler, task, 3));
            assertEquals(TaskState.CANCELLED, scheduler.stateOf(task));
        }
    }

    @Test
    void zeroTotalUnitsCompletesImmediately() {
        try (TaskScheduler scheduler = new TaskScheduler(2)) {
            TaskScheduler.TaskHandle handle = scheduler.submit(0, (unit, cancelled) -> {});
            assertEquals(TaskState.COMPLETED, scheduler.stateOf(handle));
        }
    }

    @Test
    void negativeTotalUnitsThrows() {
        try (TaskScheduler scheduler = new TaskScheduler(1)) {
            assertThrows(IllegalArgumentException.class,
                    () -> scheduler.submit(-1, (unit, cancelled) -> {}));
        }
    }

    @Test
    void taskProgressReportsCompletedAndTotal() throws Exception {
        try (TaskScheduler scheduler = new TaskScheduler(1)) {
            TaskScheduler.TaskHandle handle = scheduler.submit(5, (unit, cancelled) -> {});
            assertTrue(awaitTerminal(scheduler, handle, 3));
            TaskProgress progress = scheduler.snapshot(handle);
            assertEquals(5, progress.totalUnits());
            assertEquals(5, progress.completedUnits());
            assertEquals(0, progress.pendingUnits());
        }
    }

    @Test
    void taskGroupCancelsAllTasks() {
        TaskGroup group = new TaskGroup();
        // Create simple tasks using TaskScheduler
        try (TaskScheduler scheduler = new TaskScheduler(1)) {
            TaskScheduler.TaskHandle h1 = scheduler.submit(100, (unit, cancelled) -> {
                while (!cancelled.getAsBoolean()) Thread.onSpinWait();
            });
            // Wait a moment for tasks to start
            // Cancel via group — note: TaskGroup works with Task interface, not TaskHandle
        }
        // Test TaskGroup with mock tasks
        MockTask t1 = new MockTask();
        MockTask t2 = new MockTask();
        MockTask t3 = new MockTask();
        group.add(t1);
        group.add(t2);
        group.add(t3);
        group.cancelAll();
        assertTrue(t1.isCancelled());
        assertTrue(t2.isCancelled());
        assertTrue(t3.isCancelled());
    }

    private static boolean awaitTerminal(TaskScheduler scheduler,
                                          TaskScheduler.TaskHandle handle,
                                          int timeoutSeconds) throws InterruptedException {
        return scheduler.awaitTerminal(handle, timeoutSeconds, TimeUnit.SECONDS);
    }

    // Simple mock task for testing TaskGroup
    private static class MockTask implements Task {
        private final TaskId id = TaskId.generate();
        private volatile boolean cancelled;
        private volatile boolean paused;

        @Override public TaskId id() { return id; }
        @Override public TaskState state() { return cancelled ? TaskState.CANCELLED : TaskState.QUEUED; }
        @Override public TaskProgress progress() { return TaskProgress.notStarted(1); }
        @Override public java.util.Optional<TaskResult<?>> result() { return java.util.Optional.empty(); }
        @Override public void cancel() { cancelled = true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void pause() { paused = true; }
        @Override public void resume() { paused = false; }
        @Override public boolean isPaused() { return paused; }
    }
}
