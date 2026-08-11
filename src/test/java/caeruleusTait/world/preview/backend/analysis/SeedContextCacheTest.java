package caeruleusTait.world.preview.backend.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class SeedContextCacheTest {
    @Test
    void keepsOneIndependentContextPerSeedAndClosesAllContexts() throws Exception {
        List<FakeContext> created = new ArrayList<>();
        SeedContextCache<FakeContext> cache = new SeedContextCache<>(seed -> {
            FakeContext context = new FakeContext(seed);
            created.add(context);
            return context;
        });

        FakeContext first = cache.get(11L);
        FakeContext same = cache.get(11L);
        FakeContext second = cache.get(12L);

        assertSame(first, same);
        assertNotSame(first, second);
        assertEquals(List.of(11L, 12L), created.stream().map(FakeContext::seed).toList());

        cache.close();

        assertEquals(List.of(11L, 12L), created.stream().filter(FakeContext::closed).map(FakeContext::seed).toList());
    }

    private static final class FakeContext implements AutoCloseable {
        private final long seed;
        private boolean closed;

        private FakeContext(long seed) {
            this.seed = seed;
        }

        private long seed() {
            return seed;
        }

        private boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
