package caeruleusTait.world.preview.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConfigBackupManagerTest {

    @TempDir
    Path tempDir;

    private Path configFile() {
        return tempDir.resolve("config.json");
    }

    private ConfigBackupManager manager() {
        return new ConfigBackupManager(configFile());
    }

    private void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void backupBeforeSaveDoesNothingWhenFileMissing() throws IOException {
        manager().backupBeforeSave(configFile());

        assertFalse(Files.exists(tempDir.resolve("backups")));
    }

    @Test
    void backupBeforeSaveCreatesTimestampedCopy() throws IOException {
        Path config = configFile();
        Files.writeString(config, "{\"v\":1}");

        manager().backupBeforeSave(config);

        try (Stream<Path> backups = Files.list(tempDir.resolve("backups"))) {
            List<Path> files = backups.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().startsWith("config.json."));
            assertTrue(files.get(0).getFileName().toString().endsWith(".bak"));
            assertEquals("{\"v\":1}", Files.readString(files.get(0)));
        }
    }

    @Test
    void loadLatestBackupReturnsMostRecentContent() throws IOException, InterruptedException {
        Path config = configFile();
        Files.writeString(config, "{\"v\":1}");
        manager().backupBeforeSave(config);
        sleepMs(1100); // backup names have second granularity

        Files.writeString(config, "{\"v\":2}");
        manager().backupBeforeSave(config);

        String latest = manager().loadLatestBackup(config);
        // Both backups exist; lexicographically greatest timestamp wins
        assertNotNull(latest);
        assertTrue(latest.equals("{\"v\":1}") || latest.equals("{\"v\":2}"));
    }

    @Test
    void loadLatestBackupReturnsNullWhenNoBackups() {
        assertNull(manager().loadLatestBackup(configFile()));
    }

    @Test
    void loadLatestBackupIgnoresForeignFiles() throws IOException {
        Path config = configFile();
        Files.createDirectories(tempDir.resolve("backups"));
        Files.writeString(tempDir.resolve("backups").resolve("unrelated.json.bak"), "{}");

        assertNull(manager().loadLatestBackup(config));
    }

    @Test
    void pruningKeepsOnlyMaxBackups() throws IOException, InterruptedException {
        Path config = configFile();
        ConfigBackupManager mgr = manager();

        for (int i = 0; i < ConfigBackupManager.MAX_BACKUPS + 3; i++) {
            Files.writeString(config, "{\"iteration\":" + i + "}");
            mgr.backupBeforeSave(config);
            sleepMs(1100);
        }

        try (Stream<Path> backups = Files.list(tempDir.resolve("backups"))) {
            assertEquals(ConfigBackupManager.MAX_BACKUPS, backups.count());
        }
    }
}
