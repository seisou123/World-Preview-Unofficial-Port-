package caeruleusTait.world.preview.config;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreviewConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private final Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private ConfigLoader loader() {
        return new ConfigLoader(gson);
    }

    private Path configFile() {
        return tempDir.resolve("config.json");
    }

    @Test
    void missingFileYieldsDefaults() {
        WorldPreviewConfig config = loader().loadConfig(configFile());

        assertEquals(WorldPreviewConfig.defaults().numThreads(), config.numThreads());
        assertEquals(4, config.configVersion);
        assertNotNull(config.savedSeeds);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void validFileLoadsRoundTrip() throws IOException {
        WorldPreviewConfig original = WorldPreviewConfig.defaults();
        original.setNumThreads(3);
        original.savedSeeds.add("12345");
        Files.writeString(configFile(), gson.toJson(original));

        WorldPreviewConfig loaded = loader().loadConfig(configFile());

        assertEquals(3, loaded.numThreads());
        assertEquals(1, loaded.savedSeeds.size());
        assertEquals("12345", loaded.savedSeeds.get(0));
    }

    @Test
    void corruptJsonFallsBackToDefaults() throws IOException {
        Files.writeString(configFile(), "{not valid json !!!");

        WorldPreviewConfig loaded = loader().loadConfig(configFile());

        assertEquals(4, loaded.configVersion);
        assertTrue(loaded.savedSeeds.isEmpty());
    }

    @Test
    void corruptJsonRecoversFromBackup() throws IOException {
        // Create a backup containing a valid old-style (v0) config
        Path backups = tempDir.resolve("backups");
        Files.createDirectories(backups);
        WorldPreviewConfig oldConfig = WorldPreviewConfig.defaults();
        oldConfig.configVersion = 0;
        oldConfig.setNumThreads(2);
        Files.writeString(backups.resolve("config.json.2026-01-01_00-00-00.bak"), gson.toJson(oldConfig));

        // Primary file is corrupt
        Files.writeString(configFile(), "garbage{");

        WorldPreviewConfig loaded = loader().loadConfig(configFile());

        assertEquals(2, loaded.numThreads());
        // Backup content was migrated to the current version
        assertEquals(ConfigMigrator.TARGET_VERSION, loaded.configVersion);
    }

    @Test
    void missingFieldsAreFilledFromDefaultsNotJavaPrimitives() throws IOException {
        // A v4 config that omits many fields — Gson would silently use 0/false,
        // but fillMissingFields must inject the real defaults first.
        Files.writeString(configFile(), "{\"configVersion\":4,\"colorMap\":\"world_preview:viridis\"}");

        WorldPreviewConfig loaded = loader().loadConfig(configFile());

        assertEquals("world_preview:viridis", loaded.colorMap);
        assertEquals(WorldPreviewConfig.defaults().preloadRadius, loaded.preloadRadius);
        assertEquals(WorldPreviewConfig.defaults().analysisMaxRegionBlocks, loaded.analysisMaxRegionBlocks);
        assertTrue(loaded.usePerNoiseTypeGradients);
        assertEquals("auto", loaded.activeCompatProfile);
    }

    @Test
    void outOfRangeValuesAreNormalized() throws IOException {
        Files.writeString(configFile(),
                "{\"configVersion\":4,\"analysisDefaultSampleStep\":9999,\"searchMinAreaPercent\":-5}");

        WorldPreviewConfig loaded = loader().loadConfig(configFile());

        assertEquals(256, loaded.analysisDefaultSampleStep);
        assertEquals(0, loaded.searchMinAreaPercent);
    }

    @Test
    void saveConfigWritesAndReloadsSameValues(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("nested").resolve("config.json");
        Files.createDirectories(target.getParent());
        WorldPreviewConfig config = WorldPreviewConfig.defaults();
        config.colorMap = "world_preview:plasma";

        loader().saveConfig(target, config);

        String raw = Files.readString(target);
        assertTrue(raw.endsWith("\n"));
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".tmp")));

        WorldPreviewConfig reloaded = loader().loadConfig(target);
        assertEquals("world_preview:plasma", reloaded.colorMap);
    }

    @Test
    void saveConfigCreatesBackupOfPreviousContent() throws IOException {
        Path target = configFile();
        Files.writeString(target, "{\"configVersion\":4,\"colorMap\":\"old\"}");

        WorldPreviewConfig config = WorldPreviewConfig.defaults();
        config.colorMap = "new";
        loader().saveConfig(target, config);

        assertEquals("new", loader().loadConfig(target).colorMap);
        try (var files = Files.list(tempDir.resolve("backups"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void renderSettingsMissingFileYieldsDefaults() {
        RenderSettings settings = loader().loadRenderSettings(tempDir.resolve("render.json"));

        assertNotNull(settings);
    }

    @Test
    void renderSettingsCorruptFileFallsBackToDefaults() throws IOException {
        Path renderFile = tempDir.resolve("render.json");
        Files.writeString(renderFile, "]]]not json");

        RenderSettings settings = loader().loadRenderSettings(renderFile);

        assertNotNull(settings);
    }
}
