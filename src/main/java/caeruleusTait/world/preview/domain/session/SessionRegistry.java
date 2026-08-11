package caeruleusTait.world.preview.domain.session;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry mapping session ids to {@link Session} instances.
 * Supports lookup by id and by context fingerprint.
 */
public final class SessionRegistry {

    private final Map<String, Session> byId = new ConcurrentHashMap<>();
    private final Map<String, List<Session>> byFingerprint = new ConcurrentHashMap<>();

    /** Registers a session. */
    public void register(Session session) {
        Objects.requireNonNull(session, "session");
        byId.put(session.id(), session);
        if (session.fingerprint() != null) {
            byFingerprint.computeIfAbsent(session.fingerprint().contextStableKey(), k -> new CopyOnWriteArrayList<>())
                    .add(session);
        }
    }

    /** Unregisters a session by id. */
    public Session unregister(String id) {
        Objects.requireNonNull(id, "id");
        Session removed = byId.remove(id);
        if (removed != null && removed.fingerprint() != null) {
            List<Session> list = byFingerprint.get(removed.fingerprint().contextStableKey());
            if (list != null) list.remove(removed);
        }
        return removed;
    }

    /** Looks up a session by id. */
    public Optional<Session> find(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(byId.get(id));
    }

    /** Finds all sessions matching the given fingerprint. */
    public List<Session> findByFingerprint(SessionFingerprint fingerprint) {
        if (fingerprint == null) return List.of();
        List<Session> list = byFingerprint.get(fingerprint.contextStableKey());
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Returns all registered session ids. */
    public Set<String> allIds() {
        return Set.copyOf(byId.keySet());
    }

    /** Returns all registered sessions. */
    public Collection<Session> all() {
        return List.copyOf(byId.values());
    }

    /** Returns the number of registered sessions. */
    public int size() {
        return byId.size();
    }

    /** Removes all sessions. */
    public void clear() {
        byId.clear();
        byFingerprint.clear();
    }
}
