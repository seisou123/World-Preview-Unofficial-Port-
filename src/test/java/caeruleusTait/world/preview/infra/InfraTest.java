package caeruleusTait.world.preview.infra;

import caeruleusTait.world.preview.infra.storage.*;
import caeruleusTait.world.preview.infra.thread.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the infra layer: thread/ and storage/ modules.
 */
class InfraTest {

    // ---- CancelBarrier tests ----

    @Test
    void cancelBarrierStartsNotCancelled() {
        CancelBarrier barrier = new CancelBarrier();
        assertFalse(barrier.isCancelled());
    }

    @Test
    void cancelBarrierCancelIsIdempotent() {
        CancelBarrier barrier = new CancelBarrier();
        assertTrue(barrier.cancel());
        assertTrue(barrier.isCancelled());
        assertFalse(barrier.cancel()); // already cancelled
        assertTrue(barrier.isCancelled());
    }

    @Test
    void cancelBarrierCheckCancelledThrows() {
        CancelBarrier barrier = new CancelBarrier();
        assertDoesNotThrow(barrier::checkCancelled);
        barrier.cancel();
        assertThrows(CancelBarrier.CancellationException.class, barrier::checkCancelled);
    }

    @Test
    void cancelBarrierListenerFiresOnCancel() {
        CancelBarrier barrier = new CancelBarrier();
        AtomicReference<CancelBarrier> fired = new AtomicReference<>();
        barrier.onCancel(b -> fired.set(b));

        assertNull(fired.get());
        barrier.cancel();
        assertSame(barrier, fired.get());
    }

    @Test
    void cancelBarrierListenerFiresImmediatelyIfAlreadyCancelled() {
        CancelBarrier barrier = new CancelBarrier();
        barrier.cancel();

        AtomicReference<CancelBarrier> fired = new AtomicReference<>();
        barrier.onCancel(b -> fired.set(b));
        assertSame(barrier, fired.get());
    }

    // ---- ThreadPools tests ----

    @Test
    void threadPoolsCreateWorkPoolWithCorrectSize() {
        ThreadPoolExecutor pool = ThreadPools.createWorkPool(4);
        assertEquals(4, pool.getCorePoolSize());
        assertEquals(4, pool.getMaximumPoolSize());
        assertTrue(pool.getActiveCount() >= 0);
        ThreadPools.shutdownNow(pool);
    }

    @Test
    void threadPoolsCreateWorkPoolRejectsZero() {
        assertThrows(IllegalArgumentException.class, () -> ThreadPools.createWorkPool(0));
    }

    @Test
    void threadPoolsCreateNamedPoolWithCorrectPrefix() throws Exception {
        ThreadPoolExecutor pool = ThreadPools.createNamedPool("Test-", 2);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        pool.submit(() -> {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertNotNull(threadName.get());
        assertTrue(threadName.get().startsWith("Test-"));
        ThreadPools.shutdownNow(pool);
    }

    @Test
    void threadPoolsShutdownGracefully() throws Exception {
        ExecutorService pool = ThreadPools.createIoPool();
        AtomicInteger counter = new AtomicInteger(0);
        pool.submit(() -> counter.incrementAndGet());
        assertTrue(ThreadPools.shutdownGracefully(pool, 5, TimeUnit.SECONDS));
        assertEquals(1, counter.get());
    }

    @Test
    void threadPoolsCreateDaemonThreads() throws Exception {
        ThreadPoolExecutor pool = ThreadPools.createWorkPool(1);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> isDaemon = new AtomicReference<>();
        pool.submit(() -> {
            isDaemon.set(Thread.currentThread().isDaemon());
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        assertTrue(isDaemon.get());
        ThreadPools.shutdownNow(pool);
    }

    // ---- WorkQueue tests ----

    @Test
    void workQueueSubmitAndPoll() {
        WorkQueue<String> queue = new WorkQueue<>();
        queue.submit("a");
        queue.submit("b");

        assertEquals(2, queue.size());
        assertEquals("a", queue.poll());
        assertEquals("b", queue.poll());
        assertNull(queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test
    void workQueuePriorityOrdering() {
        WorkQueue<String> queue = new WorkQueue<>();
        queue.submit("low1", WorkQueue.Priority.LOW);
        queue.submit("high1", WorkQueue.Priority.HIGH);
        queue.submit("normal1", WorkQueue.Priority.NORMAL);
        queue.submit("high2", WorkQueue.Priority.HIGH);
        queue.submit("low2", WorkQueue.Priority.LOW);

        assertEquals("high1", queue.poll());
        assertEquals("high2", queue.poll());
        assertEquals("normal1", queue.poll());
        assertEquals("low1", queue.poll());
        assertEquals("low2", queue.poll());
    }

    @Test
    void workQueueFifoWithinSamePriority() {
        WorkQueue<Integer> queue = new WorkQueue<>();
        for (int i = 0; i < 10; i++) {
            queue.submit(i, WorkQueue.Priority.NORMAL);
        }

        for (int i = 0; i < 10; i++) {
            assertEquals(i, queue.poll());
        }
    }

    @Test
    void workQueueSubmitAll() {
        WorkQueue<Integer> queue = new WorkQueue<>();
        queue.submitAll(List.of(1, 2, 3, 4, 5));

        assertEquals(5, queue.size());
        for (int i = 1; i <= 5; i++) {
            assertEquals(i, queue.poll());
        }
    }

    @Test
    void workQueueDrain() {
        WorkQueue<Integer> queue = new WorkQueue<>();
        queue.submitAll(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));

        List<Integer> batch = queue.drain(3);
        assertEquals(3, batch.size());
        assertEquals(7, queue.size());

        List<Integer> rest = queue.drain(100);
        assertEquals(7, rest.size());
        assertEquals(0, queue.size());
    }

    @Test
    void workQueueClear() {
        WorkQueue<String> queue = new WorkQueue<>();
        queue.submitAll(List.of("a", "b", "c"));
        assertEquals(3, queue.size());
        queue.clear();
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
    }

    @Test
    void workQueueSnapshotDoesNotRemove() {
        WorkQueue<Integer> queue = new WorkQueue<>();
        queue.submitAll(List.of(1, 2, 3));
        List<Integer> snapshot = queue.snapshot();
        assertEquals(3, snapshot.size());
        assertEquals(3, queue.size()); // still has items
    }

    @Test
    void workQueueTakeBlocksUntilItemAvailable() throws Exception {
        WorkQueue<String> queue = new WorkQueue<>();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = exec.submit(() -> queue.take());
            Thread.sleep(100); // let the thread start waiting
            assertFalse(future.isDone());
            queue.submit("hello");
            assertEquals("hello", future.get(5, TimeUnit.SECONDS));
        } finally {
            ThreadPools.shutdownNow(exec);
        }
    }

    @Test
    void workQueueTakeThrowsOnCancel() throws Exception {
        CancelBarrier barrier = new CancelBarrier();
        WorkQueue<String> queue = new WorkQueue<>(barrier);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = exec.submit(() -> queue.take());
            Thread.sleep(100);
            barrier.cancel();
            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        } finally {
            ThreadPools.shutdownNow(exec);
        }
    }

    // ---- FileStorageEngine tests ----

    @Test
    void fileStorageEngineWriteAndRead(@TempDir Path tempDir) throws IOException {
        FileStorageEngine engine = new FileStorageEngine(tempDir);
        byte[] data = "Hello, World!".getBytes();

        try (OutputStream out = engine.write("test.txt")) {
            out.write(data);
        }

        assertTrue(engine.exists("test.txt"));
        assertEquals(data.length, engine.size("test.txt"));

        Optional<InputStream> optIn = engine.read("test.txt");
        assertTrue(optIn.isPresent());
        byte[] read = optIn.get().readAllBytes();
        assertArrayEquals(data, read);
    }

    @Test
    void fileStorageEngineReadNonExistentReturnsEmpty(@TempDir Path tempDir) throws IOException {
        FileStorageEngine engine = new FileStorageEngine(tempDir);
        Optional<InputStream> result = engine.read("nonexistent.txt");
        assertTrue(result.isEmpty());
        assertFalse(engine.exists("nonexistent.txt"));
        assertEquals(-1, engine.size("nonexistent.txt"));
    }

    @Test
    void fileStorageEngineDelete(@TempDir Path tempDir) throws IOException {
        FileStorageEngine engine = new FileStorageEngine(tempDir);
        try (OutputStream out = engine.write("delete-me.txt")) {
            out.write("data".getBytes());
        }
        assertTrue(engine.exists("delete-me.txt"));
        assertTrue(engine.delete("delete-me.txt"));
        assertFalse(engine.exists("delete-me.txt"));
        assertFalse(engine.delete("delete-me.txt")); // already deleted
    }

    @Test
    void fileStorageEngineList(@TempDir Path tempDir) throws IOException {
        FileStorageEngine engine = new FileStorageEngine(tempDir);
        try (OutputStream out = engine.write("dir/a.txt")) { out.write("a".getBytes()); }
        try (OutputStream out = engine.write("dir/b.txt")) { out.write("b".getBytes()); }
        try (OutputStream out = engine.write("dir/sub/c.txt")) { out.write("c".getBytes()); }

        List<String> files = engine.list("dir");
        assertEquals(3, files.size());
        assertTrue(files.contains("dir/a.txt"));
        assertTrue(files.contains("dir/b.txt"));
        assertTrue(files.contains("dir/sub/c.txt"));
    }

    @Test
    void fileStorageEngineCreatesParentDirectories(@TempDir Path tempDir) throws IOException {
        FileStorageEngine engine = new FileStorageEngine(tempDir);
        try (OutputStream out = engine.write("deeply/nested/path/file.txt")) {
            out.write("data".getBytes());
        }
        assertTrue(engine.exists("deeply/nested/path/file.txt"));
    }

    @Test
    void fileStorageEngineRejectsPathTraversal(@TempDir Path tempDir) {
        FileStorageEngine engine = new FileStorageEngine(tempDir);
        assertThrows(SecurityException.class, () -> engine.read("../../etc/passwd"));
        assertThrows(SecurityException.class, () -> engine.write("../../evil.txt"));
    }

    @Test
    void fileStorageEngineTransactionCommit(@TempDir Path tempDir) throws IOException {
        FileStorageEngine engine = new FileStorageEngine(tempDir);
        try (StorageTransaction txn = engine.beginTransaction()) {
            txn.write("file1.txt", "data1".getBytes());
            txn.write("file2.txt", "data2".getBytes());
            txn.commit();
        }
        assertTrue(engine.exists("file1.txt"));
        assertTrue(engine.exists("file2.txt"));
    }

    @Test
    void fileStorageEngineTransactionRollback(@TempDir Path tempDir) throws IOException {
        FileStorageEngine engine = new FileStorageEngine(tempDir);
        try (StorageTransaction txn = engine.beginTransaction()) {
            txn.write("file1.txt", "data1".getBytes());
            txn.write("file2.txt", "data2".getBytes());
            txn.rollback();
        }
        assertFalse(engine.exists("file1.txt"));
        assertFalse(engine.exists("file2.txt"));
    }

    @Test
    void fileStorageEngineTransactionAutoRollbackOnClose(@TempDir Path tempDir) throws IOException {
        FileStorageEngine engine = new FileStorageEngine(tempDir);
        try (StorageTransaction txn = engine.beginTransaction()) {
            txn.write("file1.txt", "data1".getBytes());
            // No commit, no explicit rollback — auto-rollback on close
        }
        assertFalse(engine.exists("file1.txt"));
    }

    // ---- CacheSchema tests ----

    @Test
    void cacheSchemaMatchesSameVersion() {
        CacheSchema s1 = new CacheSchema(2, "sig123");
        CacheSchema s2 = new CacheSchema(2, "sig123");
        assertTrue(s1.matches(s2));
    }

    @Test
    void cacheSchemaDoesNotMatchDifferentVersion() {
        CacheSchema s1 = new CacheSchema(2, "sig123");
        CacheSchema s2 = new CacheSchema(3, "sig123");
        assertFalse(s1.matches(s2));
    }

    @Test
    void cacheSchemaDoesNotMatchDifferentSignature() {
        CacheSchema s1 = new CacheSchema(2, "sig123");
        CacheSchema s2 = new CacheSchema(2, "sig456");
        assertFalse(s1.matches(s2));
    }

    @Test
    void cacheSchemaDoesNotMatchNull() {
        CacheSchema s = new CacheSchema(2, "sig123");
        assertFalse(s.matches(null));
    }

    @Test
    void cacheSchemaCanMigrateFromOlderVersion() {
        CacheSchema target = new CacheSchema(3, "sig123");
        CacheSchema older = new CacheSchema(1, "sig123");
        assertTrue(target.canMigrateFrom(older));
    }

    @Test
    void cacheSchemaCannotMigrateFromSameVersion() {
        CacheSchema target = new CacheSchema(3, "sig123");
        CacheSchema same = new CacheSchema(3, "sig123");
        assertFalse(target.canMigrateFrom(same));
    }

    @Test
    void cacheSchemaCannotMigrateFromNewerVersion() {
        CacheSchema target = new CacheSchema(2, "sig123");
        CacheSchema newer = new CacheSchema(3, "sig123");
        assertFalse(target.canMigrateFrom(newer));
    }

    @Test
    void cacheSchemaCannotMigrateFromDifferentSignature() {
        CacheSchema target = new CacheSchema(3, "sig123");
        CacheSchema other = new CacheSchema(1, "sig456");
        assertFalse(target.canMigrateFrom(other));
    }

    @Test
    void cacheSchemaRejectsNegativeVersion() {
        assertThrows(IllegalArgumentException.class, () -> new CacheSchema(-1, "sig"));
    }

    @Test
    void cacheSchemaRejectsBlankSignature() {
        assertThrows(IllegalArgumentException.class, () -> new CacheSchema(1, ""));
        assertThrows(IllegalArgumentException.class, () -> new CacheSchema(1, "  "));
    }

    // ---- CacheMigration tests ----

    @Test
    void cacheMigrationNoOpWhenSameSchema() throws IOException {
        CacheSchema schema = new CacheSchema(2, "sig");
        CacheMigration migration = new CacheMigration(schema);
        byte[] data = "original".getBytes();
        Optional<byte[]> result = migration.migrate(schema, data);
        assertTrue(result.isPresent());
        assertArrayEquals(data, result.get());
    }

    @Test
    void cacheMigrationReturnsEmptyForIncompatibleSchema() throws IOException {
        CacheSchema target = new CacheSchema(2, "sigA");
        CacheMigration migration = new CacheMigration(target);
        CacheSchema source = new CacheSchema(1, "sigB");
        Optional<byte[]> result = migration.migrate(source, "data".getBytes());
        assertTrue(result.isEmpty());
    }

    @Test
    void cacheMigrationReturnsEmptyWhenNoStepsRegistered() throws IOException {
        CacheSchema target = new CacheSchema(3, "sig");
        CacheMigration migration = new CacheMigration(target);
        CacheSchema source = new CacheSchema(1, "sig");
        Optional<byte[]> result = migration.migrate(source, "data".getBytes());
        assertTrue(result.isEmpty());
    }

    @Test
    void cacheMigrationAppliesSingleStep() throws IOException {
        CacheSchema target = new CacheSchema(2, "sig");
        CacheMigration migration = new CacheMigration(target);
        migration.registerMigration("sig", 1, data -> new String(data).toUpperCase().getBytes());

        byte[] result = migration.migrate(new CacheSchema(1, "sig"), "hello".getBytes()).orElseThrow();
        assertArrayEquals("HELLO".getBytes(), result);
    }

    @Test
    void cacheMigrationAppliesMultipleSteps() throws IOException {
        CacheSchema target = new CacheSchema(3, "sig");
        CacheMigration migration = new CacheMigration(target);
        migration.registerMigration("sig", 1, data -> new String(data).toUpperCase().getBytes());
        migration.registerMigration("sig", 2, data -> new String(data).replace("O", "0").getBytes());

        byte[] result = migration.migrate(new CacheSchema(1, "sig"), "hello".getBytes()).orElseThrow();
        assertArrayEquals("HELL0".getBytes(), result);
    }

    @Test
    void cacheMigrationReturnsEmptyForNewerSource() throws IOException {
        CacheSchema target = new CacheSchema(2, "sig");
        CacheMigration migration = new CacheMigration(target);
        migration.registerMigration("sig", 1, data -> data);

        Optional<byte[]> result = migration.migrate(new CacheSchema(3, "sig"), "data".getBytes());
        assertTrue(result.isEmpty());
    }

    // ---- StorageMetrics tests ----

    @Test
    void storageMetricsStartAtZero() {
        StorageMetrics metrics = new StorageMetrics();
        assertEquals(0, metrics.totalBytes());
        assertEquals(0, metrics.entryCount());
        assertEquals(0, metrics.readCount());
        assertEquals(0, metrics.readHits());
        assertEquals(0, metrics.readMisses());
        assertEquals(0.0, metrics.hitRate());
        assertEquals(0, metrics.writeCount());
        assertEquals(0, metrics.deleteCount());
    }

    @Test
    void storageMetricsRecordWrite() {
        StorageMetrics metrics = new StorageMetrics();
        metrics.recordWrite(100);
        assertEquals(100, metrics.totalBytes());
        assertEquals(1, metrics.entryCount());
        assertEquals(1, metrics.writeCount());
    }

    @Test
    void storageMetricsRecordDelete() {
        StorageMetrics metrics = new StorageMetrics();
        metrics.recordWrite(100);
        metrics.recordDelete(100);
        assertEquals(0, metrics.totalBytes());
        assertEquals(0, metrics.entryCount());
        assertEquals(1, metrics.deleteCount());
    }

    @Test
    void storageMetricsRecordRead() {
        StorageMetrics metrics = new StorageMetrics();
        metrics.recordRead(100, true);
        metrics.recordRead(200, true);
        metrics.recordRead(0, false);

        assertEquals(3, metrics.readCount());
        assertEquals(2, metrics.readHits());
        assertEquals(1, metrics.readMisses());
        assertEquals(2.0 / 3.0, metrics.hitRate(), 0.001);
    }

    @Test
    void storageMetricsHitRateZeroWhenNoReads() {
        StorageMetrics metrics = new StorageMetrics();
        assertEquals(0.0, metrics.hitRate());
    }

    @Test
    void storageMetricsReset() {
        StorageMetrics metrics = new StorageMetrics();
        metrics.recordWrite(100);
        metrics.recordRead(50, true);
        metrics.recordDelete(100);

        metrics.reset();
        assertEquals(0, metrics.totalBytes());
        assertEquals(0, metrics.entryCount());
        assertEquals(0, metrics.readCount());
        assertEquals(0, metrics.readHits());
        assertEquals(0, metrics.writeCount());
        assertEquals(0, metrics.deleteCount());
    }

    @Test
    void storageMetricsSummary() {
        StorageMetrics metrics = new StorageMetrics();
        metrics.recordWrite(100);
        metrics.recordRead(50, true);
        String summary = metrics.summary();
        assertNotNull(summary);
        assertTrue(summary.contains("entries=1"));
        assertTrue(summary.contains("bytes=100"));
        assertTrue(summary.contains("reads=1"));
    }

    // ---- FileStorageEngine transaction with mixed write+delete ----

    @Test
    void fileStorageEngineTransactionMixedWriteAndDelete(@TempDir Path tempDir) throws IOException {
        FileStorageEngine engine = new FileStorageEngine(tempDir);
        // Pre-create a file to delete
        try (OutputStream out = engine.write("toDelete.txt")) { out.write("old".getBytes()); }
        assertTrue(engine.exists("toDelete.txt"));

        try (StorageTransaction txn = engine.beginTransaction()) {
            txn.write("file1.txt", "data1".getBytes());
            txn.write("file2.txt", "data2".getBytes());
            txn.delete("toDelete.txt");
            txn.commit();
        }
        assertTrue(engine.exists("file1.txt"));
        assertTrue(engine.exists("file2.txt"));
        assertFalse(engine.exists("toDelete.txt"));
    }

    @Test
    void fileStorageEngineTransactionMixedWriteAndDeleteRollback(@TempDir Path tempDir) throws IOException {
        FileStorageEngine engine = new FileStorageEngine(tempDir);
        try (OutputStream out = engine.write("toDelete.txt")) { out.write("old".getBytes()); }

        try (StorageTransaction txn = engine.beginTransaction()) {
            txn.write("file1.txt", "data1".getBytes());
            txn.delete("toDelete.txt");
            txn.rollback();
        }
        assertFalse(engine.exists("file1.txt"));
        assertTrue(engine.exists("toDelete.txt"));  // should still exist
    }
}
