package caeruleusTait.world.preview.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Manages rotating backups of config files.
 * <p>
 * Before each save, the current config file is copied to a timestamped backup.
 * If loading fails (corrupted JSON, incompatible schema, etc.), the loader can
 * attempt to restore from the most recent backup.
 * <p>
 * Backups are stored in a {@code backups/} subdirectory next to the config file.
 * Only the {@link #MAX_BACKUPS} most recent backups are retained; older ones are
 * automatically deleted.
 */
public final class ConfigBackupManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("world_preview");

    /** Maximum number of backup files to keep. */
    static final int MAX_BACKUPS = 5;

    private final Path backupDir;

    public ConfigBackupManager(Path configFile) {
        this.backupDir = configFile.toAbsolutePath().getParent().resolve("backups");
    }

    /**
     * Create a backup of the given config file before it gets overwritten.
     *
     * @param configFile the current config file to back up
     */
    public void backupBeforeSave(Path configFile) {
        if (!Files.exists(configFile)) {
            return;
        }
        try {
            Files.createDirectories(backupDir);
            String timestamp = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd_HH-mm-ss")
                    .format(java.time.LocalDateTime.now());
            String fileName = configFile.getFileName().toString();
            Path backupFile = backupDir.resolve(fileName + "." + timestamp + ".bak");
            Files.copy(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("Config backed up to {}", backupFile);
            pruneOldBackups(fileName);
        } catch (IOException e) {
            LOGGER.warn("Failed to create config backup", e);
        }
    }

    /**
     * Attempt to load the most recent valid backup for the given config file.
     *
     * @param configFile the original config file path
     * @return the JSON content of the most recent backup, or {@code null} if no backup exists
     */
    public String loadLatestBackup(Path configFile) {
        if (!Files.exists(backupDir)) {
            return null;
        }
        String fileName = configFile.getFileName().toString();
        try (Stream<Path> files = Files.list(backupDir)) {
            Path latest = files
                    .filter(p -> p.getFileName().toString().startsWith(fileName + "."))
                    .filter(p -> p.getFileName().toString().endsWith(".bak"))
                    .sorted((a, b) -> b.getFileName().toString()
                            .compareTo(a.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
            if (latest != null) {
                LOGGER.info("Loading config from backup: {}", latest);
                return Files.readString(latest);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to list config backups", e);
        }
        return null;
    }

    /**
     * Delete old backups, keeping only the most recent {@link #MAX_BACKUPS}.
     */
    private void pruneOldBackups(String fileName) {
        if (!Files.exists(backupDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(backupDir)) {
            var sorted = files
                    .filter(p -> p.getFileName().toString().startsWith(fileName + "."))
                    .filter(p -> p.getFileName().toString().endsWith(".bak"))
                    .sorted((a, b) -> b.getFileName().toString()
                            .compareTo(a.getFileName().toString()))
                    .toList();
            if (sorted.size() <= MAX_BACKUPS) {
                return;
            }
            for (int i = MAX_BACKUPS; i < sorted.size(); i++) {
                try {
                    Files.deleteIfExists(sorted.get(i));
                    LOGGER.debug("Deleted old config backup: {}", sorted.get(i));
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete old config backup: {}", sorted.get(i), e);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to prune config backups", e);
        }
    }
}
