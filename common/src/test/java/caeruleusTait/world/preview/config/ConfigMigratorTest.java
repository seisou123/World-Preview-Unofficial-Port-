package caeruleusTait.world.preview.config;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigMigratorTest {

    @Test
    void targetVersionIsFour() {
        assertEquals(4, ConfigMigrator.TARGET_VERSION);
    }

    @Test
    void migrateFromV0RunsDisplayStepAndChainsToTarget() {
        JsonObject root = new JsonObject();
        int result = new ConfigMigrator().migrate(root, 0);

        assertEquals(ConfigMigrator.TARGET_VERSION, result);
        assertEquals(ConfigMigrator.TARGET_VERSION, root.get("configVersion").getAsInt());
        assertFalse(root.get("showStatistics").getAsBoolean());
        assertFalse(root.get("showCoordinates").getAsBoolean());
        assertFalse(root.get("showMinimap").getAsBoolean());
    }

    @Test
    void migrateFromV1RunsAnalysisStep() {
        JsonObject root = new JsonObject();
        int result = new ConfigMigrator().migrate(root, 1);

        assertEquals(ConfigMigrator.TARGET_VERSION, result);
        assertEquals(1, root.get("analysisDefaultSampleStep").getAsInt());
        assertEquals(4_000_000L, root.get("analysisMaxRegionBlocks").getAsLong());
    }

    @Test
    void migrateFromV2RunsTerrainStep() {
        JsonObject root = new JsonObject();
        int result = new ConfigMigrator().migrate(root, 2);

        assertEquals(ConfigMigrator.TARGET_VERSION, result);
        assertFalse(root.get("enableHillshade").getAsBoolean());
        assertEquals(315f, root.get("hillshadeAzimuth").getAsFloat(), 0.001f);
        assertEquals(45f, root.get("hillshadeAltitude").getAsFloat(), 0.001f);
        assertEquals(0.3f, root.get("hillshadeAmbient").getAsFloat(), 0.001f);
        assertTrue(root.get("contourMinorLines").getAsBoolean());
    }

    @Test
    void migrateFromV3RunsCompatStepOnly() {
        JsonObject root = new JsonObject();
        int result = new ConfigMigrator().migrate(root, 3);

        assertEquals(4, result);
        assertTrue(root.get("autoDetectMods").getAsBoolean());
        assertEquals("auto", root.get("activeCompatProfile").getAsString());
        assertFalse(root.get("spawnOverrideEnabled").getAsBoolean());
        assertFalse(root.get("showBiomeCounts").getAsBoolean());
        // Earlier-step fields must NOT be injected when starting at v3
        assertFalse(root.has("showStatistics"));
        assertFalse(root.has("analysisDefaultSampleStep"));
    }

    @Test
    void migrateChainsAllStepsFromZero() {
        JsonObject root = new JsonObject();
        int result = new ConfigMigrator().migrate(root, 0);

        assertEquals(ConfigMigrator.TARGET_VERSION, result);
        // Spot-check fields from every step survived the chain
        assertFalse(root.get("showStatistics").getAsBoolean());
        assertEquals(1, root.get("analysisDefaultSampleStep").getAsInt());
        assertEquals(315f, root.get("hillshadeAzimuth").getAsFloat(), 0.001f);
        assertEquals("auto", root.get("activeCompatProfile").getAsString());
    }

    @Test
    void migrateAtTargetIsNoOpExceptVersionStamp() {
        JsonObject root = new JsonObject();
        root.addProperty("custom", "kept");
        int result = new ConfigMigrator().migrate(root, ConfigMigrator.TARGET_VERSION);

        assertEquals(ConfigMigrator.TARGET_VERSION, result);
        assertEquals(ConfigMigrator.TARGET_VERSION, root.get("configVersion").getAsInt());
        assertEquals("kept", root.get("custom").getAsString());
        // No migration-step fields should have been injected
        assertFalse(root.has("showStatistics"));
    }

    @Test
    void migratePreservesExistingValues() {
        JsonObject root = new JsonObject();
        root.addProperty("showStatistics", true);
        root.addProperty("analysisDefaultSampleStep", 7);

        new ConfigMigrator().migrate(root, 0);

        assertTrue(root.get("showStatistics").getAsBoolean());
        assertEquals(7, root.get("analysisDefaultSampleStep").getAsInt());
    }

    @Test
    void unknownFileVersionSkipsStepsButStampsVersion() {
        JsonObject root = new JsonObject();
        int result = new ConfigMigrator().migrate(root, 99);

        assertEquals(99, result);
        assertEquals(99, root.get("configVersion").getAsInt());
    }

    @Test
    void blankStringProfileIsReplacedByDefault() {
        JsonObject root = new JsonObject();
        root.addProperty("activeCompatProfile", "  ");

        new ConfigMigrator().migrate(root, 3);

        assertEquals("auto", root.get("activeCompatProfile").getAsString());
    }
}
