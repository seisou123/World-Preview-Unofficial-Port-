package caeruleusTait.world.preview.backend.storage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two invariants that {@link PreviewBlock#get(int, int)} must keep now that it
 * is no longer {@code synchronized} (W4): it does a plain array read and only takes the
 * monitor on a miss, with a double check inside.
 *
 * <ol>
 *   <li><b>Never null.</b> Once a slot is populated every caller must observe a section.</li>
 *   <li><b>Exactly one instance per slot.</b> A racing miss must not publish a second
 *       section — the re-check inside the monitor is what makes the losers reuse the
 *       winner's instance. Removing that re-check is the mutation these tests exist for.</li>
 * </ol>
 *
 * <h2>Why a barrier instead of a long free-running loop</h2>
 * A plain "many threads in a loop for a few hundred milliseconds" test does <em>not</em>
 * detect a missing re-check: the slot is created within the first microseconds, after
 * which every iteration only exercises the (already correct) hit path. The race window
 * is a few tens of nanoseconds wide and opens exactly once per slot.
 *
 * <p>So each round uses a <b>fresh</b> block and a {@link CyclicBarrier} that releases all
 * threads at the same instant onto the same not-yet-created slot. Releasing them together
 * means all eight threads perform their null read inside the same few-nanosecond window,
 * before the winner has stored its section — so if the re-check were missing, the losers
 * would each publish their own instance and the identity assertion would fail. The window
 * is still not something the test can force open, so correctness comes from repetition:
 * 120 independent fresh blocks make a silent pass effectively impossible. (The factory is
 * not artificially slowed down, so this stays a black-box test of the real method.)
 *
 * <p>Runs headless: {@code WorldPreview.get()} is null here, so the section factory falls
 * back to uncompressed full-resolution sections. That is why the second test exercises the
 * compressed section type directly — it is the production default
 * ({@code enableCompression = true}) and would otherwise go unguarded.
 */
class PreviewBlockConcurrencyTest {

    /** Threads racing for the same slot each round. */
    private static final int THREADS = 8;

    /**
     * Fresh blocks raced over. Each round creates a brand-new block so the miss path is
     * exercised again; a single block would be populated after the first round.
     */
    private static final int ROUNDS = 120;

    /** Quart coordinates that land in different slots (slot index shifts by SHIFT then masks). */
    private static final int QUART_A = 0;
    private static final int QUART_B = 64;

    private static final short VALUE_A = 4242;
    private static final short VALUE_B = 777;

    @Test
    void racedMissPublishesExactlyOneSectionPerSlot() throws Exception {
        for (int round = 0; round < ROUNDS; ++round) {
            // Captured by the assertion lambdas below, so it has to be effectively final.
            final int attempt = round;
            final PreviewBlock block = new PreviewBlock(PreviewStorage.FLAG_BIOME);
            final CyclicBarrier barrier = new CyclicBarrier(THREADS);
            final PreviewSection[] observed = new PreviewSection[THREADS];
            final AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread[] workers = new Thread[THREADS];
            for (int t = 0; t < THREADS; ++t) {
                final int slot = t;
                workers[t] = new Thread(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        observed[slot] = block.get(QUART_A, QUART_B);
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
                workers[t].setDaemon(true);
                workers[t].start();
            }
            for (Thread w : workers) {
                w.join(TimeUnit.SECONDS.toMillis(5));
                assertFalse(w.isAlive(), "round " + attempt + ": worker did not terminate");
            }
            assertNull(failure.get(), () -> "round " + attempt + " failed: " + failure.get());

            final PreviewSection expected = observed[0];
            assertNotNull(expected, "round " + attempt + ": get() returned null");
            for (int t = 1; t < THREADS; ++t) {
                assertSame(
                        expected, observed[t],
                        "round " + attempt + ": a racing miss published more than one section"
                );
            }
            // After the race the slot must still resolve to the instance the race settled on.
            assertSame(
                    expected, block.get(QUART_A, QUART_B),
                    "round " + attempt + ": get() returned a different instance after the race"
            );
        }
    }

    /**
     * The default production path writes through {@link PreviewSectionCompressed}, which is
     * only what {@code PreviewBlock.sectionFactory} builds when a mod instance exists — not
     * the case headless. So it is constructed directly and hammered with one writer and
     * several readers: a reader must only ever observe a sentinel or one of the two values
     * the writer alternates between, which is what breaks if the packed-word update is torn.
     */
    @Test
    void compressedSectionToleratesOneWriterAndManyReaders() throws Exception {
        final PreviewSection section = new PreviewSectionCompressed.Full(0, 0);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean running = new AtomicBoolean(true);

        Thread writer = new Thread(() -> {
            try {
                while (running.get()) {
                    section.set(0, 0, VALUE_A);
                    section.set(0, 1, VALUE_A);
                    section.set(0, 0, VALUE_B);
                    section.set(0, 1, VALUE_B);
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
                running.set(false);
            }
        });
        writer.setDaemon(true);
        writer.start();

        final int readers = 3;
        Thread[] readerThreads = new Thread[readers];
        for (int r = 0; r < readers; ++r) {
            readerThreads[r] = new Thread(() -> {
                try {
                    while (running.get()) {
                        for (int z = 0; z < 2; ++z) {
                            final short value = section.get(0, z);
                            assertTrue(
                                    value == Short.MIN_VALUE || value == VALUE_A || value == VALUE_B,
                                    "unexpected value " + value + " read back at z=" + z
                            );
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                    running.set(false);
                }
            });
            readerThreads[r].setDaemon(true);
            readerThreads[r].start();
        }

        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        while (System.nanoTime() < deadline && failure.get() == null) {
            Thread.onSpinWait();
        }
        running.set(false);

        writer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(writer.isAlive(), "writer thread did not terminate");
        for (Thread reader : readerThreads) {
            reader.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(reader.isAlive(), "reader thread did not terminate");
        }
        assertNull(failure.get(), () -> "concurrency failure: " + failure.get());
    }
}
