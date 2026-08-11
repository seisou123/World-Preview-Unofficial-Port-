package caeruleusTait.world.preview.backend.analysis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongFunction;

/** Caches one independently closeable world-generation context per seed. */
public final class SeedContextCache<T extends AutoCloseable> implements AutoCloseable {
    private final LongFunction<T> factory;
    private final Map<Long, T> contexts = new LinkedHashMap<>();
    private boolean closed;

    public SeedContextCache(LongFunction<T> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public synchronized T get(long seed) {
        if (closed) throw new IllegalStateException("seed context cache is closed");
        return contexts.computeIfAbsent(seed, factory::apply);
    }

    public synchronized void close(long seed) throws Exception {
        T context = contexts.remove(seed);
        if (context != null) context.close();
    }

    @Override
    public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        Exception failure = null;
        for (T context : contexts.values()) {
            try {
                context.close();
            } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        contexts.clear();
        if (failure != null) throw failure;
    }
}
