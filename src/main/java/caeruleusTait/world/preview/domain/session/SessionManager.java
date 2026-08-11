package caeruleusTait.world.preview.domain.session;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global session manager: creates, recovers, cleans up and validates sessions.
 *
 * <p>This is the domain-level singleton that replaces the per-system
 * session management logic previously scattered across
 * {@code AnalysisSession} and {@code SeedContextCache}.
 */
public final class SessionManager {

    private final SessionRegistry registry = new SessionRegistry();
    private final Map<Long, Object> contextCache = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /** Creates and registers a new session. */
    public <S extends Session> S create(S session) {
        Objects.requireNonNull(session, "session");
        ensureOpen();
        registry.register(session);
        return session;
    }

    /** Looks up a session by id. */
    public Optional<Session> find(String id) {
        return registry.find(id);
    }

    /** Finds all sessions matching the given fingerprint. */
    public List<Session> findByFingerprint(SessionFingerprint fingerprint) {
        return registry.findByFingerprint(fingerprint);
    }

    /** Returns all registered sessions. */
    public Collection<Session> all() {
        return registry.all();
    }

    /** Returns the number of active sessions. */
    public int activeCount() {
        return (int) registry.all().stream().filter(s -> !s.state().isTerminal()).count();
    }

    /** Cancels all active sessions. */
    public void cancelAll() {
        registry.all().stream()
                .filter(s -> !s.state().isTerminal())
                .forEach(Session::cancel);
    }

    /** Closes all sessions and clears the registry. */
    public void closeAll() {
        for (Session session : registry.all()) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        registry.clear();
        contextCache.clear();
    }

    /**
     * Validates that the given fingerprint matches the expected one.
     * Returns {@code true} if they match, {@code false} otherwise.
     */
    public boolean validateFingerprint(SessionFingerprint expected, SessionFingerprint actual) {
        if (expected == null || actual == null) return false;
        return expected.contextMatches(actual);
    }

    // ---- Context cache ----

    /**
     * Caches a context object keyed by seed.
     * This replaces the per-system context caching logic.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrCreateContext(long seed, java.util.function.LongFunction<T> factory) {
        Objects.requireNonNull(factory, "factory");
        ensureOpen();
        return (T) contextCache.computeIfAbsent(seed, k -> factory.apply(k));
    }

    /** Removes and returns a cached context by seed. */
    public Object removeContext(long seed) {
        return contextCache.remove(seed);
    }

    /** Unregisters and closes a session by id. */
    public void close(String id) {
        Session session = registry.unregister(id);
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Returns the underlying registry (for advanced queries). */
    public SessionRegistry registry() {
        return registry;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Shuts down the manager, closing all sessions. */
    public void shutdown() {
        if (closed) return;
        closed = true;
        closeAll();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("session manager is closed");
        }
    }
}
