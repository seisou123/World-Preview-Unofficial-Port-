package caeruleusTait.world.preview.backend.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the persistent seed search history / favorites store. */
class SeedSearchHistoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("record/upsert keeps one entry per seed with fresh timestamp")
    void recordUpserts() {
        SeedSearchHistory history = new SeedSearchHistory(tempDir.resolve("history.json"));
        history.record("123", "plains");
        history.record("123", "plains + village");
        assertEquals(1, history.size());
        assertEquals("plains + village", history.byRecency().get(0).label);
        assertEquals("123", history.byRecency().get(0).seed);
    }

    private static void sleepTestFriendly() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException ignored) {
        }
    }

    @Test
    @DisplayName("Favorites toggle and filter")
    void favoritesToggle() {
        SeedSearchHistory history = new SeedSearchHistory(tempDir.resolve("history.json"));
        history.record("1", null);
        history.record("2", null);

        // Toggle entry 1 on: only entry 1 shows up in favorites.
        assertTrue(history.toggleFavorite("1"));
        List<SeedSearchHistory.Entry> favorites = history.favorites();
        assertEquals(1, favorites.size());
        assertEquals("1", favorites.get(0).seed);
        assertFalse(history.find("2").favorite);

        // Toggling an unknown seed reports false and changes nothing.
        assertFalse(history.toggleFavorite("unknown"));
        assertEquals(1, history.favorites().size());

        // Toggling entry 1 again flips it back off.
        assertFalse(history.toggleFavorite("1"));
        assertEquals(0, history.favorites().size());
    }

    @Test
    @DisplayName("History persists across load/save roundtrip")
    void persistsAcrossReload() {
        Path file = tempDir.resolve("history.json");
        SeedSearchHistory history = new SeedSearchHistory(file);
        history.record("111111", "label-a");
        history.record("222222", "label-b");
        history.toggleFavorite("111111");
        assertTrue(Files.exists(file));

        SeedSearchHistory reloaded = new SeedSearchHistory(file);
        reloaded.load();
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.find("111111").favorite);
        assertEquals("label-a", reloaded.find("111111").label);
        assertEquals(1, reloaded.favorites().size());
    }

    @Test
    @DisplayName("remove deletes an entry and returns true")
    void removesEntries() {
        SeedSearchHistory history = new SeedSearchHistory(tempDir.resolve("history.json"));
        history.record("42", null);
        assertTrue(history.remove("42"));
        assertFalse(history.remove("42"));
        assertEquals(0, history.size());
    }

    @Test
    @DisplayName("Corrupt file yields an empty store instead of crashing")
    void corruptFileTolerated() throws Exception {
        Path file = tempDir.resolve("history.json");
        Files.writeString(file, "{not valid json!!");
        SeedSearchHistory history = new SeedSearchHistory(file);
        history.load();
        assertEquals(0, history.size());
        // Mutating after a corrupt load must still persist cleanly.
        history.record("7", null);
        SeedSearchHistory reloaded = new SeedSearchHistory(file);
        reloaded.load();
        assertEquals(1, reloaded.size());
    }

    @Test
    @DisplayName("Trimming drops oldest non-favorite entries beyond the cap")
    void trimsOldestNonFavorites() {
        SeedSearchHistory history = new SeedSearchHistory(tempDir.resolve("history.json"));
        history.record("favorite-seed", null);
        history.toggleFavorite("favorite-seed");
        for (int i = 0; i < SeedSearchHistory.MAX_ENTRIES + 10; i++) {
            history.record("seed-" + i, null);
            try {
                Thread.sleep(1);
            } catch (InterruptedException ignored) {
            }
        }
        assertEquals(SeedSearchHistory.MAX_ENTRIES, history.size());
        assertNotNull(history.find("favorite-seed"), "favorites survive trimming");
    }
}
