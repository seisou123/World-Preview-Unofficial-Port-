package caeruleusTait.world.preview.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured config migration system.
 * <p>
 * Instead of manually patching fields on the deserialized config object (which
 * suffers from Gson's silent Java-default substitution for missing JSON fields),
 * this migrator operates on the raw {@link JsonObject} before deserialization.
 * This allows precise detection of absent fields and correct default injection.
 * <p>
 * Each {@link MigrationStep} handles exactly one version increment and receives
 * the raw JSON object. Steps are executed in order from the file's version up to
 * the target version.
 */
public final class ConfigMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger("world_preview");

    /** The current config version that the mod expects. */
    public static final int TARGET_VERSION = 4;

    private final List<VersionedStep> steps = new ArrayList<>();

    public ConfigMigrator() {
        register(0, 1, this::migrateV0toV1);
        register(1, 2, this::migrateV1toV2);
        register(2, 3, this::migrateV2toV3);
        register(3, 4, this::migrateV3toV4);
    }

    private void register(int from, int to, MigrationStep step) {
        steps.add(new VersionedStep(from, to, step));
    }

    /**
     * Run all applicable migrations on the given JSON object.
     *
     * @param root          the raw JSON root (will be modified in place)
     * @param fileVersion   the {@code configVersion} found in the file (0 if absent)
     * @return the new version after migration
     */
    public int migrate(JsonObject root, int fileVersion) {
        int current = fileVersion;
        for (VersionedStep vs : steps) {
            if (current == vs.from) {
                LOGGER.info("Running config migration v{} → v{}", vs.from, vs.to);
                try {
                    vs.step.migrate(root);
                } catch (Exception e) {
                    LOGGER.warn("Config migration v{} → v{} failed; continuing with safe defaults", vs.from, vs.to, e);
                }
                current = vs.to;
            }
        }
        // Ensure the version field is up-to-date in the JSON
        root.addProperty("configVersion", current);
        return current;
    }

    // ===== Individual migration steps =====

    /** v0 → v1: Reset display settings to defaults (old configs without version). */
    private void migrateV0toV1(JsonObject root) {
        setDefaultIfMissing(root, "showStatistics", false);
        setDefaultIfMissing(root, "showCoordinates", false);
        setDefaultIfMissing(root, "showMinimap", false);
        root.addProperty("configVersion", 1);
    }

    /** v1 → v2: Initialize analysis defaults. */
    private void migrateV1toV2(JsonObject root) {
        setDefaultIfMissing(root, "analysisDefaultSampleStep", 1);
        setDefaultIfMissing(root, "analysisMaxRegionBlocks", 4_000_000L);
        root.addProperty("configVersion", 2);
    }

    /** v2 → v3: Terrain enhancement defaults (hillshade & contours). */
    private void migrateV2toV3(JsonObject root) {
        // These fields may be missing from older configs. Gson would set them
        // to 0f / 0 / false, but the real defaults are non-zero. By operating
        // on the JsonObject we can detect absence precisely.
        setDefaultIfMissing(root, "enableHillshade", false);
        setDefaultIfMissing(root, "hillshadeAzimuth", 315f);
        setDefaultIfMissing(root, "hillshadeAltitude", 45f);
        setDefaultIfMissing(root, "hillshadeAmbient", 0.3f);
        setDefaultIfMissing(root, "hillshadeExaggeration", 1.0f);
        setDefaultIfMissing(root, "enableContours", false);
        setDefaultIfMissing(root, "contourInterval", 10);
        // contourMinorLines: default true, but only force it when enableContours
        // is also absent (i.e. the whole terrain section is new)
        if (!root.has("enableContours") && !root.has("contourMinorLines")) {
            root.addProperty("contourMinorLines", true);
        } else {
            setDefaultIfMissing(root, "contourMinorLines", true);
        }
        root.addProperty("configVersion", 3);
    }

    /** v3 → v4: Full compat-system migration. Ensures all fields have proper defaults. */
    private void migrateV3toV4(JsonObject root) {
        // Ensure compatibility fields exist
        setDefaultIfMissing(root, "autoDetectMods", true);
        setDefaultIfMissing(root, "activeCompatProfile", "auto");
        // Ensure spawn override fields
        setDefaultIfMissing(root, "spawnOverrideEnabled", false);
        setDefaultIfMissing(root, "spawnOverrideX", 0);
        setDefaultIfMissing(root, "spawnOverrideZ", 0);
        // Ensure search fields
        setDefaultIfMissing(root, "searchMinAreaPercent", 0);
        setDefaultIfMissing(root, "searchMaxDistance", 0);
        // Ensure analysis button field
        setDefaultIfMissing(root, "showAnalysisButton", false);
        // Ensure showBiomeCounts
        setDefaultIfMissing(root, "showBiomeCounts", false);
        root.addProperty("configVersion", 4);
    }

    // ===== Helpers =====

    /**
     * Set a default value for a JSON field only if the field is absent.
     * This is the critical fix for Gson's behavior of silently substituting
     * Java defaults (0, false, null) for missing fields.
     */
    static void setDefaultIfMissing(JsonObject root, String field, boolean def) {
        JsonElement el = root.get(field);
        if (el == null || el.isJsonNull()) {
            root.addProperty(field, def);
        }
    }

    static void setDefaultIfMissing(JsonObject root, String field, Number def) {
        JsonElement el = root.get(field);
        if (el == null || el.isJsonNull()) {
            root.addProperty(field, def);
        }
    }

    static void setDefaultIfMissing(JsonObject root, String field, String def) {
        JsonElement el = root.get(field);
        if (el == null || el.isJsonNull() || (el.isJsonPrimitive() && el.getAsString().isBlank())) {
            root.addProperty(field, def);
        }
    }

    @FunctionalInterface
    interface MigrationStep {
        void migrate(JsonObject root);
    }

    private record VersionedStep(int from, int to, MigrationStep step) {}
}
