package caeruleusTait.world.preview.infra.thread;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory for creating pre-configured thread pools for different workload types.
 *
 * <p>Replaces the ad-hoc {@code Executors.newFixedThreadPool} and
 * {@code Executors.newSingleThreadExecutor} calls scattered across
 * {@code WorkManager} and {@code AnalysisScheduler}.
 *
 * <p>Three pool types are supported:
 * <ul>
 *   <li><b>WORK</b> — fixed-size pool for CPU-intensive sampling/rendering tasks</li>
 *   <li><b>IO</b> — single-thread pool for sequential I/O operations (cache loading, queuing)</li>
 *   <li><b>UI</b> — single-thread pool for UI-scheduled tasks that must not block the render thread</li>
 * </ul>
 */
public final class ThreadPools {

    private ThreadPools() {}

    /** Thread name prefix for work pool threads. */
    public static final String WORK_THREAD_PREFIX = "WP-Work-";

    /** Thread name prefix for I/O pool threads. */
    public static final String IO_THREAD_PREFIX = "WP-IO-";

    /** Thread name prefix for UI pool threads. */
    public static final String UI_THREAD_PREFIX = "WP-UI-";

    /**
     * Creates a fixed-size thread pool for CPU-intensive work.
     *
     * @param poolSize the number of threads (typically {@code Runtime.getRuntime().availableProcessors()})
     * @return a new {@link ThreadPoolExecutor} with pre-started core threads
     */
    public static ThreadPoolExecutor createWorkPool(int poolSize) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1, got " + poolSize);
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                createThreadFactory(WORK_THREAD_PREFIX)
        );
        executor.prestartAllCoreThreads();
        return executor;
    }

    /**
     * Creates a single-thread executor for sequential I/O operations.
     *
     * @return a new single-thread {@link ExecutorService}
     */
    public static ExecutorService createIoPool() {
        return Executors.newSingleThreadExecutor(createThreadFactory(IO_THREAD_PREFIX));
    }

    /**
     * Creates a single-thread executor for UI-scheduled tasks.
     *
     * @return a new single-thread {@link ExecutorService}
     */
    public static ExecutorService createUiPool() {
        return Executors.newSingleThreadExecutor(createThreadFactory(UI_THREAD_PREFIX));
    }

    /**
     * Creates a named thread pool with a custom prefix.
     *
     * @param prefix the thread name prefix
     * @param poolSize the number of threads
     * @return a new {@link ThreadPoolExecutor}
     */
    public static ThreadPoolExecutor createNamedPool(String prefix, int poolSize) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1, got " + poolSize);
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                createThreadFactory(prefix)
        );
        executor.prestartAllCoreThreads();
        return executor;
    }

    /**
     * Gracefully shuts down an executor service, waiting up to the given timeout.
     *
     * @param executor the executor to shut down
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return {@code true} if the executor terminated within the timeout
     */
    public static boolean shutdownGracefully(ExecutorService executor, long timeout, TimeUnit unit) {
        if (executor == null) return true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                executor.shutdownNow();
                return executor.awaitTermination(timeout, unit);
            }
            return true;
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Forcefully shuts down an executor service immediately.
     *
     * @param executor the executor to shut down
     */
    public static void shutdownNow(ExecutorService executor) {
        if (executor == null) return;
        executor.shutdownNow();
    }

    private static ThreadFactory createThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
