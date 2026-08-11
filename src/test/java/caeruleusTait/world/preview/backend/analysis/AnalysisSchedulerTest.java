package caeruleusTait.world.preview.backend.analysis;

import caeruleusTait.world.preview.domain.task.TaskScheduler;
import caeruleusTait.world.preview.domain.task.TaskState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production analysis scheduling now uses {@link TaskScheduler}.
 * These regression tests cover the previous AnalysisScheduler behavior on the domain scheduler.
 */
class AnalysisSchedulerTest {
    @Test
    void pauseStopsNewWorkUnitsAndResumeCompletesAllFive() throws Exception {
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
            assertTrue(awaitStatus(scheduler, task, TaskState.COMPLETED));
            started.sort(Integer::compareTo);
            assertEquals(List.of(0, 1, 2, 3, 4), started);
            assertEquals(5, completed.get());
        }
    }

    @Test
    void cancelReachesCancelledWithoutWaitingForAnUnboundedTask() throws Exception {
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
            assertTrue(awaitStatus(scheduler, task, TaskState.CANCELLED));
        }
    }

    private static boolean awaitStatus(TaskScheduler scheduler,
                                        TaskScheduler.TaskHandle task,
                                        TaskState expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (scheduler.stateOf(task) == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return scheduler.stateOf(task) == expected;
    }
}
