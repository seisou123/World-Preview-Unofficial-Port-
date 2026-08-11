package caeruleusTait.world.preview.infra.storage;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks storage engine metrics: total size, entry count, hit/miss rates.
 *
 * <p>Thread-safe. Used to monitor cache performance and trigger cleanup
 * when the cache grows too large.
 */
public final class StorageMetrics {

    private final AtomicLong totalBytes = new AtomicLong(0);
    private final AtomicLong entryCount = new AtomicLong(0);
    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong readHits = new AtomicLong(0);
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong deleteCount = new AtomicLong(0);

    /** Records a successful read of {@code bytes} bytes. */
    public void recordRead(long bytes, boolean hit) {
        readCount.incrementAndGet();
        if (hit) {
            readHits.incrementAndGet();
        }
    }

    /** Records a write of {@code bytes} bytes. */
    public void recordWrite(long bytes) {
        writeCount.incrementAndGet();
        totalBytes.addAndGet(bytes);
        entryCount.incrementAndGet();
    }

    /** Records a deletion of {@code bytes} bytes. */
    public void recordDelete(long bytes) {
        deleteCount.incrementAndGet();
        totalBytes.addAndGet(-bytes);
        entryCount.decrementAndGet();
    }

    /** Returns the total size of all stored entries in bytes. */
    public long totalBytes() {
        return totalBytes.get();
    }

    /** Returns the number of stored entries. */
    public long entryCount() {
        return entryCount.get();
    }

    /** Returns the total number of read operations. */
    public long readCount() {
        return readCount.get();
    }

    /** Returns the number of cache hits (reads that found data). */
    public long readHits() {
        return readHits.get();
    }

    /** Returns the number of cache misses (reads that found no data). */
    public long readMisses() {
        return readCount.get() - readHits.get();
    }

    /** Returns the cache hit rate as a fraction (0..1). */
    public double hitRate() {
        long reads = readCount.get();
        return reads == 0 ? 0.0 : (double) readHits.get() / reads;
    }

    /** Returns the total number of write operations. */
    public long writeCount() {
        return writeCount.get();
    }

    /** Returns the total number of delete operations. */
    public long deleteCount() {
        return deleteCount.get();
    }

    /** Resets all metrics to zero. */
    public void reset() {
        totalBytes.set(0);
        entryCount.set(0);
        readCount.set(0);
        readHits.set(0);
        writeCount.set(0);
        deleteCount.set(0);
    }

    /** Returns a snapshot of all metrics as a string. */
    public String summary() {
        return String.format(
                "StorageMetrics{entries=%d, bytes=%d, reads=%d (hits=%d, misses=%d, hitRate=%.2f%%), writes=%d, deletes=%d}",
                entryCount.get(), totalBytes.get(),
                readCount.get(), readHits.get(), readMisses(), hitRate() * 100,
                writeCount.get(), deleteCount.get()
        );
    }
}
