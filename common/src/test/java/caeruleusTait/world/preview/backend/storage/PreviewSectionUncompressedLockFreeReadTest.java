package caeruleusTait.world.preview.backend.storage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The uncompressed section {@code get()} is lock-free (C1): short array writes are atomic
 * per JLS 17.7 and readers tolerate eventual consistency. A writer continuously sets
 * different cells while the main thread reads, asserting every observed value belongs to
 * the set of values that may ever be written to that cell.
 */
class PreviewSectionUncompressedLockFreeReadTest {

    /** Long enough to collide writer/reader many times, short enough for a fast suite. */
    private static final long TEST_DURATION_MS = 300;

    private static final short VALUE_A = 1000;
    private static final short VALUE_B = 2000;

    private static final int[][] CELLS = {{0, 0}, {1, 1}, {2, 2}, {3, 3}};

    @Test
    void lockFreeReadObservesOnlyWrittenValues_full() throws Exception {
        run(new PreviewSectionFull(0, 0));
    }

    @Test
    void lockFreeReadObservesOnlyWrittenValues_half() throws Exception {
        run(new PreviewSectionHalf(0, 0));
    }

    @Test
    void lockFreeReadObservesOnlyWrittenValues_quarter() throws Exception {
        run(new PreviewSectionQuarter(0, 0));
    }

    private static void run(PreviewSection section) throws Exception {
        final AtomicReference<Throwable> writerError = new AtomicReference<>();
        final AtomicBoolean running = new AtomicBoolean(true);

        Thread writer = new Thread(() -> {
            try {
                while (running.get()) {
                    for (int[] cell : CELLS) {
                        section.set(cell[0], cell[1], VALUE_A);
                        section.set(cell[0], cell[1], VALUE_B);
                    }
                }
            } catch (Throwable t) {
                writerError.compareAndSet(null, t);
            } finally {
                running.set(false);
            }
        });
        writer.start();

        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TEST_DURATION_MS);
        try {
            while (System.nanoTime() < deadline && writerError.get() == null) {
                for (int[] cell : CELLS) {
                    final short value = section.get(cell[0], cell[1]);
                    // Only the untouched sentinel or one of the two written values may be observed.
                    assertTrue(
                            value == Short.MIN_VALUE || value == VALUE_A || value == VALUE_B,
                            "Unexpected value " + value + " at cell [" + cell[0] + "," + cell[1] + "]"
                    );
                }
            }
        } finally {
            running.set(false);
            writer.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertNull(writerError.get());
        assertFalse(writer.isAlive(), "writer thread did not terminate");
    }
}
