package caeruleusTait.world.preview.backend.analysis;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.domain.task.TaskScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisSessionTest {
    @BeforeEach
    void initializeWorldPreview() throws Exception {
        WorldPreview preview = new WorldPreview();
        Field instance = WorldPreview.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, preview);
        Field cfg = WorldPreview.class.getDeclaredField("cfg");
        cfg.setAccessible(true);
        WorldPreviewConfig config = WorldPreviewConfig.defaults();
        config.enableCompression = false;
        cfg.set(preview, config);
        Field settings = WorldPreview.class.getDeclaredField("renderSettings");
        settings.setAccessible(true);
        settings.set(preview, RenderSettings.defaults());
    }

    @Test
    void partialSamplingProducesPendingSnapshot() throws Exception {
        try (TaskScheduler scheduler = new TaskScheduler(1)) {
            CountDownLatch firstRow = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AnalysisSession session = newSession(scheduler, (x, z, y) -> {
                if (z == 0) firstRow.countDown();
                if (z == 1) release.await();
                return new AnalysisSession.Sample((short) 7, (short) 64);
            });

            session.start();
            assertTrue(firstRow.await(2, TimeUnit.SECONDS));
            assertTrue(session.result().coverage() < 1.0);
            assertEquals(AnalysisDataState.PENDING, session.result().state());
            // Spin-wait briefly: firstRow.countDown() fires inside the sampler callback,
            // but sampledPoints is incremented after the callback returns.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (session.progress().sampledPoints() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(session.progress().sampledPoints() > 0);
            release.countDown();
            awaitStatus(session, AnalysisStatus.COMPLETED);
            assertEquals(1.0, session.result().coverage());
            session.close();
        }
    }

    @Test
    void cancelPreservesCompletedSnapshot() throws Exception {
        try (TaskScheduler scheduler = new TaskScheduler(1)) {
            CountDownLatch firstRow = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AnalysisSession session = newSession(scheduler, (x, z, y) -> {
                if (z == 0) firstRow.countDown();
                if (z == 1) release.await();
                return new AnalysisSession.Sample((short) 3, (short) 70);
            });

            session.start();
            assertTrue(firstRow.await(2, TimeUnit.SECONDS));
            session.cancel();
            release.countDown();
            awaitStatus(session, AnalysisStatus.CANCELLED);
            assertTrue(session.result().presentSamples() > 0);
            assertTrue(session.result().coverage() < 1.0);
            session.close();
        }
    }

    @Test
    void nonDivisibleRegionIncludesMaximumCoordinates() throws Exception {
        try (TaskScheduler scheduler = new TaskScheduler(1)) {
            java.util.Set<String> sampled = java.util.concurrent.ConcurrentHashMap.newKeySet();
            AnalysisRequest request = new AnalysisRequest(
                    1L, "minecraft:overworld", Region.of(0, 0, 5, 5), 64, 3,
                    true, false, false);
            AnalysisSession session = new AnalysisSession(request, scheduler,
                    new PreviewStorage(0, 128), (x, z, y) -> {
                        sampled.add(x + "," + z);
                        return new AnalysisSession.Sample((short) 7, (short) 64);
                    });

            session.start();
            awaitStatus(session, AnalysisStatus.COMPLETED);

            assertEquals(9, sampled.size());
            assertEquals(1.0, session.result().coverage());
            assertTrue(sampled.contains("5,5"));
            assertTrue(sampled.contains("0,5"));
            assertTrue(sampled.contains("5,0"));
            session.close();
        }
    }

    @Test
    void profileReportsPendingWhenStorageHasNoSample() {
        try (TaskScheduler scheduler = new TaskScheduler(1)) {
            AnalysisSession session = newSession(scheduler, (x, z, y) ->
                    new AnalysisSession.Sample(Short.MIN_VALUE, Short.MIN_VALUE));
            ProfileResult result = session.profile(new ProfileRequest(0, 0, 2, 0, 64, 64, 1, false));
            assertEquals(AnalysisDataState.PENDING, result.state());
            assertTrue(result.points().stream().allMatch(point -> point.state() == AnalysisDataState.PENDING));
            session.close();
        }
    }

    @Test
    void pauseAndResumeCompletesRemainingRows() throws Exception {
        try (TaskScheduler scheduler = new TaskScheduler(1)) {
            CountDownLatch firstRow = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean paused = new AtomicBoolean();
            AnalysisSession session = newSession(scheduler, (x, z, y) -> {
                if (z == 0) firstRow.countDown();
                if (z == 1) release.await();
                return new AnalysisSession.Sample((short) 1, (short) 62);
            });

            session.start();
            assertTrue(firstRow.await(2, TimeUnit.SECONDS));
            session.pause();
            // Unblock the worker so it can finish z=1 and reach the pause check.
            release.countDown();
            paused.set(awaitStatus(session, AnalysisStatus.PAUSED));
            assertTrue(paused.get());
            assertTrue(session.result().coverage() < 1.0);
            session.resume();
            awaitStatus(session, AnalysisStatus.COMPLETED);
            assertEquals(1.0, session.result().coverage());
            session.close();
        }
    }

    private static AnalysisSession newSession(TaskScheduler scheduler,
                                               AnalysisSession.Sampler sampler) {
        AnalysisRequest request = new AnalysisRequest(
                1L, "minecraft:overworld", Region.of(0, 0, 3, 3), 64, 1,
                true, false, false);
        return new AnalysisSession(request, scheduler, new PreviewStorage(0, 128), sampler);
    }

    private static boolean awaitStatus(AnalysisSession session, AnalysisStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline && session.progress().status() != expected) {
            Thread.sleep(5);
        }
        assertEquals(expected, session.progress().status());
        return true;
    }
}
