package caeruleusTait.world.preview.domain.waypoint;

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
import java.util.List;
import java.util.Objects;

/**
 * Persistent store for map waypoints, backed by a single JSON file
 * ({@code config/world_preview/waypoints.json}).
 * <p>
 * Mutations write through to disk immediately; the store is small (handfuls
 * of markers per seed) so full-file rewrites are cheap.
 * </p>
 */
public class WaypointStore {

    public static final int MAX_WAYPOINTS = 500;

    /** Palette offered by the waypoint naming dialog. */
    public static final int[] PALETTE = {
            0xFFE53935, // red
            0xFFFB8C00, // orange
            0xFFFDD835, // yellow
            0xFF43A047, // green
            0xFF1E88E5, // blue
            0xFF8E24AA, // purple
            0xFF00ACC1, // cyan
            0xFFF06292, // pink
    };

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = TypeToken.getParameterized(List.class, Waypoint.class).getType();

    private final List<Waypoint> waypoints = new ArrayList<>();
    private final Path file;

    public WaypointStore(Path file) {
        this.file = file;
    }

    // ===== Persistence =====

    /** Loads waypoints from disk. Corrupt or missing files yield an empty store. */
    public void load() {
        waypoints.clear();
        try {
            if (file == null || !Files.exists(file)) {
                return;
            }
            String json = Files.readString(file);
            List<Waypoint> loaded = GSON.fromJson(json, LIST_TYPE);
            if (loaded == null) {
                return;
            }
            for (Waypoint waypoint : loaded) {
                if (waypoint != null && waypoint.id() != null) {
                    waypoints.add(waypoint);
                }
            }
        } catch (Exception e) {
            waypoints.clear();
        }
    }

    /** Writes waypoints to disk atomically. Failures are swallowed (best effort). */
    public void save() {
        if (file == null) {
            return;
        }
        try {
            AtomicFiles.writeStringAtomic(file, GSON.toJson(waypoints) + "\n");
        } catch (IOException ignored) {
            // Best-effort persistence.
        }
    }

    // ===== Queries =====

    /** All waypoints belonging to the given seed+dimension, map-order stable. */
    public List<Waypoint> forSeedDimension(long seed, @Nullable String dimension) {
        List<Waypoint> result = new ArrayList<>();
        for (Waypoint waypoint : waypoints) {
            if (waypoint.matches(seed, dimension)) {
                result.add(waypoint);
            }
        }
        return result;
    }

    public int size() {
        return waypoints.size();
    }

    // ===== Mutation =====

    /** Adds a waypoint (persisting immediately). Enforces {@link #MAX_WAYPOINTS}. */
    public Waypoint add(Waypoint waypoint) {
        Objects.requireNonNull(waypoint, "waypoint");
        if (waypoints.size() >= MAX_WAYPOINTS) {
            waypoints.remove(0);
        }
        waypoints.add(waypoint);
        save();
        return waypoint;
    }

    /** Removes a waypoint by id. Returns true when it existed. */
    public boolean remove(String id) {
        boolean removed = waypoints.removeIf(waypoint -> waypoint.id().equals(id));
        if (removed) {
            save();
        }
        return removed;
    }

    /**
     * Removes the waypoint nearest to (x, z) within the given seed+dimension
     * and a search radius. Returns the removed waypoint, or null when none is
     * in range.
     */
    @Nullable
    public Waypoint removeNearest(long seed, @Nullable String dimension, int x, int z, double maxDistanceBlocks) {
        Waypoint best = null;
        double bestDistSq = maxDistanceBlocks * maxDistanceBlocks;
        for (Waypoint waypoint : waypoints) {
            if (!waypoint.matches(seed, dimension)) {
                continue;
            }
            double dx = waypoint.x() - x;
            double dz = waypoint.z() - z;
            double distSq = dx * dx + dz * dz;
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = waypoint;
            }
        }
        if (best != null) {
            remove(best.id());
        }
        return best;
    }
}
