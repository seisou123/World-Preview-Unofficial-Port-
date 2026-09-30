package caeruleusTait.world.preview.backend.storage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PreviewSectionConcurrencyTest {

    @Test
    void concurrentWritersOnDifferentCellsAreVisibleToReaders() throws Exception {
        PreviewSectionFull section = new PreviewSectionFull(0, 0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 1000; i++) {
                    section.set(0, 0, (short) 11);
                    section.set(1, 0, (short) 12);
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 1000; i++) {
                    section.set(0, 1, (short) 21);
                    section.set(1, 1, (short) 22);
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });

        t1.start();
        t2.start();
        start.countDown();
        done.await();

        assertNull(error.get());
        assertEquals(11, section.get(0, 0));
        assertEquals(12, section.get(1, 0));
        assertEquals(21, section.get(0, 1));
        assertEquals(22, section.get(1, 1));
    }
}
