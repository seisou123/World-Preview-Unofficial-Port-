package caeruleusTait.world.preview.config;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreviewConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Comprehensive config loader with multi-layer error recovery.
 * <p>
 * Loading strategy (each layer is tried only if the previous one fails):
 * <ol>
 *   <li><b>Primary load</b> — parse the JSON file, run migrations on the raw
 *       {@link JsonObject}, fill missing fields with defaults, then
 *       deserialize into {@link WorldPreviewConfig}.</li>
 *   <li><b>Backup recovery</b> — if the primary file is missing, unreadable,
 *       or fails validation, try the most recent backup file.</li>
 *   <li><b>Safe defaults</b> — if no backup is available (or the backup also
 *       fails), return a fresh {@link WorldPreviewConfig#defaults()}.</li>
 * </ol>
 * <p>
 * The key improvement over the old system is that migrations and field-default
 * injection happen on the raw JSON tree <em>before</em> Gson deserialization.
 * This avoids Gson's silent substitution of Java primitive defaults (0, 0f,
 * false) for fields that are absent from the JSON — the root cause of many
 * compatibility issues when upgrading or downgrading the mod.
 */
public final class ConfigLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("world_preview");

    private final Gson gson;
    private final ConfigMigrator migrator;
    private final ConfigBackupManager backupManager;

    public ConfigLoader(Gson gson) {
        this.gson = gson;
        this.migrator = new ConfigMigrator();
        this.backupManager = null; // set per-file in load methods
    }

    // BackupManager is created per-config-file because it depends on the file path
    private ConfigBackupManager backupFor(Path configFile) {
        return new ConfigBackupManager(configFile);
    }

    // ===== WorldPreviewConfig loading =====

    /**
     * Load {@link WorldPreviewConfig} from the given path with full error recovery.
     */
    public WorldPreviewConfig loadConfig(Path configFile) {
        ConfigBackupManager backups = backupFor(configFile);

        // Layer 1: Primary load
        WorldPreviewConfig result = tryLoadConfig(configFile);
        if (result != null) {
            return result;
        }

        // Layer 2: Backup recovery
        LOGGER.warn("Primary config load failed; attempting backup recovery");
        String backupJson = backups.loadLatestBackup(configFile);
        if (backupJson != null) {
            result = tryParseConfig(backupJson, "backup");
            if (result != null) {
                LOGGER.info("Config successfully loaded from backup");
                return result;
            }
        }

        // Layer 3: Safe defaults
        LOGGER.warn("All config recovery attempts failed; using safe defaults");
        return WorldPreviewConfig.defaults();
    }

    /**
     * Attempt to load and parse the config file.
     *
     * @return the parsed config, or {@code null} if loading failed
     */
    private WorldPreviewConfig tryLoadConfig(Path configFile) {
        try {
            if (!Files.exists(configFile)) {
                LOGGER.info("Config file does not exist; using defaults");
                return WorldPreviewConfig.defaults();
            }
            String json = Files.readString(configFile);
            return tryParseConfig(json, configFile.toString());
        } catch (Exception e) {
            LOGGER.error("Failed to read config file: {}", configFile, e);
            return null;
        }
    }

    /**
     * Parse a config JSON string with migration and default-filling.
     *
     * @param json     the raw JSON string
     * @param source   a human-readable source identifier for logging
     * @return the parsed config, or {@code null} if parsing failed
     */
    private WorldPreviewConfig tryParseConfig(String json, String source) {
        try {
            // Parse into a generic JsonObject first — this lets us detect
            // missing fields precisely (vs. Gson silently using Java defaults).
            JsonObject root = gson.fromJson(json, JsonObject.class);
            if (root == null) {
                LOGGER.warn("Config from [{}] parsed to null", source);
                return null;
            }

            // Determine file version (0 if absent — treated as very old config)
            int fileVersion = 0;
            JsonElement versionEl = root.get("configVersion");
            if (versionEl != null && versionEl.isJsonPrimitive()) {
                try {
                    fileVersion = versionEl.getAsInt();
                } catch (NumberFormatException ignored) {
                    LOGGER.warn("Invalid configVersion in [{}]; treating as 0", source);
                }
            }

            // Run structured migrations on the raw JSON tree
            int newVersion = migrator.migrate(root, fileVersion);
            if (newVersion < ConfigMigrator.TARGET_VERSION) {
                LOGGER.warn("Config from [{}] could not be fully migrated (ended at v{})", source, newVersion);
            }

            // Fill in any remaining missing fields with defaults from a fresh instance.
            // This catches fields introduced in the current version that the migrator
            // might not explicitly handle.
            fillMissingFields(root);

            // Now deserialize the complete JsonObject into the config class
            WorldPreviewConfig config = gson.fromJson(root, WorldPreviewConfig.class);
            if (config == null) {
                LOGGER.warn("Config from [{}] deserialized to null", source);
                return null;
            }

            // Validate and normalize
            config = config.normalized();

            LOGGER.info("Config loaded successfully from [{}] (v{})", source, config.configVersion);
            return config;

        } catch (JsonSyntaxException e) {
            LOGGER.error("JSON syntax error in config from [{}]: {}", source, e.getMessage());
            return null;
        } catch (Exception e) {
            LOGGER.error("Unexpected error parsing config from [{}]", source, e);
            return null;
        }
    }

    /**
     * Fill in any missing fields by comparing against a default config instance.
     * This is a safety net that catches fields the migrator doesn't explicitly handle.
     */
    private void fillMissingFields(JsonObject root) {
        WorldPreviewConfig defaults = WorldPreviewConfig.defaults();
        JsonObject defaultJson = gson.toJsonTree(defaults).getAsJsonObject();

        for (String key : defaultJson.keySet()) {
            if (!root.has(key) || root.get(key).isJsonNull()) {
                root.add(key, defaultJson.get(key));
            }
        }

        // Special handling for lists that might be null
        ensureArrayField(root, "savedSeeds");
        ensureArrayField(root, "disabledCompatMods");
    }

    private void ensureArrayField(JsonObject root, String field) {
        JsonElement el = root.get(field);
        if (el == null || el.isJsonNull()) {
            root.add(field, new com.google.gson.JsonArray());
        }
    }

    // ===== RenderSettings loading =====

    /**
     * Load {@link RenderSettings} from the given path with error recovery.
     */
    public RenderSettings loadRenderSettings(Path renderConfigFile) {
        try {
            if (!Files.exists(renderConfigFile)) {
                LOGGER.info("Render config file does not exist; using defaults");
                return new RenderSettings();
            }
            String json = Files.readString(renderConfigFile);
            RenderSettings settings = gson.fromJson(json, RenderSettings.class);
            if (settings == null) {
                LOGGER.warn("Render config parsed to null; using defaults");
                return new RenderSettings();
            }
            return settings.normalized();
        } catch (Exception e) {
            LOGGER.error("Failed to load render config; using defaults", e);
            // Try backup
            ConfigBackupManager backups = backupFor(renderConfigFile);
            String backupJson = backups.loadLatestBackup(renderConfigFile);
            if (backupJson != null) {
                try {
                    RenderSettings settings = gson.fromJson(backupJson, RenderSettings.class);
                    if (settings != null) {
                        LOGGER.info("Render config loaded from backup");
                        return settings.normalized();
                    }
                } catch (Exception be) {
                    LOGGER.error("Failed to load render config from backup", be);
                }
            }
            return new RenderSettings();
        }
    }

    // ===== Saving =====

    /**
     * Save config with backup management.
     */
    public void saveConfig(Path configFile, Object config) {
        ConfigBackupManager backups = backupFor(configFile);
        backups.backupBeforeSave(configFile);
        try {
            Path temp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(config) + "\n");
            try {
                Files.move(temp, configFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, configFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save config to {}", configFile, e);
            throw new RuntimeException(e);
        }
    }
}
