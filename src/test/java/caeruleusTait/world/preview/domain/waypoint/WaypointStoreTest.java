package caeruleusTait.world.preview.domain.waypoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the persistent waypoint store. */
class WaypointStoreTest {

    private static final long SEED = 123456789L;
    private static final String DIM = "minecraft:overworld";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Add and query by seed+dimension")
    void addAndQuery() {
        WaypointStore store = new WaypointStore(tempDir.resolve("waypoints.json"));
        store.add(Waypoint.create("village", 100, 64, 200, DIM, WaypointStore.PALETTE[0], SEED));
        store.add(Waypoint.create("other seed", 0, 64, 0, DIM, WaypointStore.PALETTE[1], SEED + 1));
        store.add(Waypoint.create("other dim", 50, 64, 50, "minecraft:the_nether", WaypointStore.PALETTE[2], SEED));

        List<Waypoint> matching = store.forSeedDimension(SEED, DIM);
        assertEquals(1, matching.size());
        assertEquals("village", matching.get(0).name());
        assertEquals(100, matching.get(0).x());
    }

    @Test
    @DisplayName("Waypoints persist across load/save roundtrip")
    void persistsAcrossReload() {
        Path file = tempDir.resolve("waypoints.json");
        WaypointStore store = new WaypointStore(file);
        store.add(Waypoint.create("base", -12, 70, 34, DIM, WaypointStore.PALETTE[4], SEED));
        assertTrue(Files.exists(file));

        WaypointStore reloaded = new WaypointStore(file);
        reloaded.load();
        assertEquals(1, reloaded.size());
        List<Waypoint> matching = reloaded.forSeedDimension(SEED, DIM);
        assertEquals("base", matching.get(0).name());
        assertEquals(-12, matching.get(0).x());
        assertEquals(34, matching.get(0).z());
        assertEquals(WaypointStore.PALETTE[4], matching.get(0).color());
    }

    @Test
    @DisplayName("removeNearest removes only waypoints within radius")
    void removeNearest() {
        WaypointStore store = new WaypointStore(tempDir.resolve("waypoints.json"));
        store.add(Waypoint.create("far", 1000, 64, 1000, DIM, 0xFFFFFFFF, SEED));
        store.add(Waypoint.create("near", 10, 64, 10, DIM, 0xFFFFFFFF, SEED));

        Waypoint removed = store.removeNearest(SEED, DIM, 12, 8, 32);
        assertNotNull(removed);
        assertEquals("near", removed.name());
        assertEquals(1, store.size());
        assertNull(store.removeNearest(SEED, DIM, 0, 0, 1));
    }

    @Test
    @DisplayName("Corrupt file yields an empty store and recovers on next save")
    void corruptFileTolerated() throws Exception {
        Path file = tempDir.resolve("waypoints.json");
        Files.writeString(file, "}{ not json");
        WaypointStore store = new WaypointStore(file);
        store.load();
        assertEquals(0, store.size());
        store.add(Waypoint.create("recovered", 0, 64, 0, DIM, 0xFF0000FF, SEED));

        WaypointStore reloaded = new WaypointStore(file);
        reloaded.load();
        assertEquals(1, reloaded.size());
    }

    @Test
    @DisplayName("Blank names are normalized")
    void blankNameNormalized() {
        Waypoint waypoint = Waypoint.create("  ", 0, 64, 0, DIM, 0, SEED);
        assertEquals("waypoint", waypoint.name());
    }
}
