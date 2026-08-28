package caeruleusTait.world.preview.backend.analysis;

import caeruleusTait.world.preview.util.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Persistent store for the seed search history and favorite seeds.
 * <p>
 * Backed by a single JSON file in the World Preview config directory.
 * Entries are keyed by seed string and keep the criteria label that produced
 * them, the last-used timestamp and a favorite flag. The list is capped so a
 * long session cannot grow the file without bound.
 * </p>
 */
public class SeedSearchHistory {

    public static final int MAX_ENTRIES = 100;

    /** One remembered seed. */
    public static class Entry {
        public String seed;
        public String label = "";
        public long timestamp;
        public boolean favorite;

        public Entry() {
        }

        public Entry(String seed, String label, long timestamp, boolean favorite) {
            this.seed = seed;
            this.label = label == null ? "" : label;
            this.timestamp = timestamp;
            this.favorite = favorite;
        }

        public String displayLabel() {
            return (label == null || label.isBlank()) ? seed : label;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = TypeToken.getParameterized(List.class, Entry.class).getType();

    private final List<Entry> entries = new ArrayList<>();
    private final Path file;

    public SeedSearchHistory(Path file) {
        this.file = file;
    }

    // ===== Persistence =====

    /** Loads entries from disk. Corrupt or missing files yield an empty store. */
    public void load() {
        entries.clear();
        try {
            if (file == null || !Files.exists(file)) {
                return;
            }
            String json = Files.readString(file);
            List<Entry> loaded = GSON.fromJson(json, LIST_TYPE);
            if (loaded == null) {
                return;
            }
            for (Entry entry : loaded) {
                if (entry != null && entry.seed != null && !entry.seed.isBlank()) {
                    entries.add(entry);
                }
            }
        } catch (Exception e) {
            entries.clear();
        }
    }

    /** Writes entries to disk atomically. Failures are swallowed (best effort). */
    public void save() {
        if (file == null) {
            return;
        }
        try {
            AtomicFiles.writeStringAtomic(file, GSON.toJson(entries) + "\n");
        } catch (IOException ignored) {
            // Best effort persistence; losing history is acceptable.
        }
    }

    // ===== Mutation =====

    /**
     * Records a seed as used. Existing entries move to the front (fresh
     * timestamp, refreshed label); favorites keep their flag. Trims to
     * {@link #MAX_ENTRIES} (oldest non-favorite entries are dropped first).
     */
    public void record(String seed, @Nullable String label) {
        Objects.requireNonNull(seed, "seed");
        long now = System.currentTimeMillis();
        Entry existing = find(seed);
        if (existing != null) {
            existing.timestamp = now;
            if (label != null && !label.isBlank()) {
                existing.label = label;
            }
        } else {
            entries.add(new Entry(seed, label, now, false));
        }
        trim();
        save();
    }

    /** Toggles the favorite flag of a seed. Returns the new state, or false when unknown. */
    public boolean toggleFavorite(String seed) {
        Entry entry = find(seed);
        if (entry == null) {
            return false;
        }
        entry.favorite = !entry.favorite;
        save();
        return entry.favorite;
    }

    /** Removes a seed from the store. Returns true when it existed. */
    public boolean remove(String seed) {
        boolean removed = entries.removeIf(entry -> entry.seed.equals(seed));
        if (removed) {
            save();
        }
        return removed;
    }

    // ===== Queries =====

    /** All entries, newest first. */
    public List<Entry> byRecency() {
        List<Entry> copy = new ArrayList<>(entries);
        copy.sort(Comparator.comparingLong((Entry e) -> e.timestamp).reversed());
        return copy;
    }

    /** Favorite entries only, newest first. */
    public List<Entry> favorites() {
        return byRecency().stream().filter(e -> e.favorite).toList();
    }

    @Nullable
    public Entry find(String seed) {
        for (Entry entry : entries) {
            if (entry.seed.equals(seed)) {
                return entry;
            }
        }
        return null;
    }

    public int size() {
        return entries.size();
    }

    // ===== Internals =====

    private void trim() {
        if (entries.size() <= MAX_ENTRIES) {
            return;
        }
        List<Entry> byAge = new ArrayList<>(entries);
        byAge.sort(Comparator.comparingLong(e -> e.timestamp));
        for (Entry entry : byAge) {
            if (entries.size() <= MAX_ENTRIES) {
                break;
            }
            if (!entry.favorite) {
                entries.remove(entry);
            }
        }
    }
}
