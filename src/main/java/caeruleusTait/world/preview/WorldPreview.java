// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview;

import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.color.BiomeColorMapReloadListener;
import caeruleusTait.world.preview.backend.color.ColormapReloadListener;
import caeruleusTait.world.preview.backend.color.HeightmapPresetReloadListener;
import caeruleusTait.world.preview.backend.color.PreviewMappingData;
import caeruleusTait.world.preview.backend.color.StructureMapReloadListener;
import caeruleusTait.world.preview.backend.storage.AnalysisRepository;
import caeruleusTait.world.preview.backend.storage.FileAnalysisRepository;
import caeruleusTait.world.preview.compat.KnownModCompat;
import caeruleusTait.world.preview.compat.ModCompatRegistry;
import caeruleusTait.world.preview.util.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mod(WorldPreview.MOD_ID)
public class WorldPreview {
    public static final String MOD_ID = "world_preview";
    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger("world_preview");

    private static WorldPreview INSTANCE;

    private Path configDir;
    private Path analysisDir;
    private Path configFile;
    private Path renderConfigFile;
    private Path missingColorsFile;
    private Path missingStructuresFile;
    private Path userColorConfigFile;
    private Gson gson;

    private WorldPreviewConfig cfg;
    private WorkManager workManager;
    private PreviewMappingData previewMappingData;
    private RenderSettings renderSettings;
    private AnalysisRepository analysisRepository;

    public static WorldPreview get() {
        return INSTANCE;
    }

    public WorldPreview(ModContainer modContainer) {
        INSTANCE = this;
        init();
    }

    private void init() {
        gson = new GsonBuilder()
                .serializeNulls()
                .setPrettyPrinting()
                // Identifier must round-trip as a plain "namespace:path" string.
                // Without an adapter Gson reflects over the runtime field names,
                // which differ between the Fabric (obfuscated) and NeoForge (mapped)
                // mappings — a config written by one loader then reads back as an
                // Identifier with null namespace/path on the other, NPE-ing in
                // Identifier.hashCode() during registry lookups.  Reads also accept
                // the legacy object form {"namespace":…,"path":…} so old config
                // files stay loadable.
                .registerTypeAdapter(Identifier.class, new TypeAdapter<Identifier>() {
                    @Override
                    public void write(JsonWriter out, Identifier value) throws IOException {
                        out.value(value == null ? null : value.toString());
                    }

                    @Override
                    public Identifier read(JsonReader in) throws IOException {
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                            return null;
                        }
                        if (in.peek() == JsonToken.STRING) {
                            return Identifier.parse(in.nextString());
                        }
                        // Legacy object form (some older builds of either loader).
                        in.beginObject();
                        String namespace = null;
                        String path = null;
                        while (in.hasNext()) {
                            switch (in.nextName()) {
                                case "namespace", "field_13353" -> {
                                    if (in.peek() != JsonToken.NULL) {
                                        namespace = in.nextString();
                                    } else {
                                        in.nextNull();
                                    }
                                }
                                case "path", "field_13355" -> {
                                    if (in.peek() != JsonToken.NULL) {
                                        path = in.nextString();
                                    } else {
                                        in.nextNull();
                                    }
                                }
                                default -> in.skipValue();
                            }
                        }
                        in.endObject();
                        // Neither half may be null — a bare object means a
                        // corrupt/foreign entry; treat it as absent.
                        return namespace == null || path == null ? null
                                : Identifier.parse(namespace + ":" + path);
                    }
                })
                .create();

        configDir = FMLPaths.CONFIGDIR.get().resolve("world_preview");
        analysisDir = configDir.resolve("analysis");
        try {
            Files.createDirectories(analysisDir);
        } catch (IOException e) {
            throw new RuntimeException("Unable to create World Preview data directories", e);
        }
        configFile = configDir.resolve("config.json");
        renderConfigFile = configDir.resolve("renderConfig.json");
        missingColorsFile = configDir.resolve("missing-colors.json");
        missingStructuresFile = configDir.resolve("missing-structures.json");
        userColorConfigFile = configDir.resolve("biome-colors.json");

        loadConfig();

        workManager = new WorkManager(renderSettings, cfg);
        previewMappingData = new PreviewMappingData();
        analysisRepository = new FileAnalysisRepository();

        initModCompatibility();

        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent event) -> {
            if (!SpawnOverrideManager.shouldApply()) return;
            SpawnOverrideManager.markApplied();
            var server = event.getServer();
            if (server.isDedicatedServer()) return;
            if (!cfg.spawnOverrideEnabled) return;
            String command = "setworldspawn " + cfg.spawnOverrideX + " 100 " + cfg.spawnOverrideZ;
            try {
                server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack(),
                        command
                );
                LOGGER.info("Spawn override applied at X={}, Z={}", cfg.spawnOverrideX, cfg.spawnOverrideZ);
            } catch (Exception e) {
                LOGGER.error("Failed to apply spawn override", e);
            }
        });

        // Register reload listeners via NeoForge event instead of mixin
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
    }

    private void onAddReloadListeners(final AddServerReloadListenersEvent event) {
        event.addListener(
                Identifier.parse("world_preview:biome_color_map"),
                new BiomeColorMapReloadListener()
        );
        event.addListener(
                Identifier.parse("world_preview:structure_map"),
                new StructureMapReloadListener()
        );
        event.addListener(
                Identifier.parse("world_preview:heightmap_preset"),
                new HeightmapPresetReloadListener()
        );
        event.addListener(
                Identifier.parse("world_preview:colormap"),
                new ColormapReloadListener()
        );
    }

    /** Releases globally owned resources before the mod instance is discarded. */
    public synchronized void close() {
        // No globally owned analysis engine; WorkManager owns runtime lifecycle.
    }

    /**
     * Scans for installed mods and registers compatibility strategies.
     * Called during onInitialize() after basic setup.
     *
     * <p>Uses Fabric Loader to enumerate installed mods and populates
     * the {@link ModCompatRegistry} with detected mod IDs.
     *
     * <p>Also registers built-in compatibility strategies for known
     * mods like Terralith and Biomes O' Plenty via {@link KnownModCompat}.
     */
    private void initModCompatibility() {
        ModCompatRegistry registry = ModCompatRegistry.getInstance();

        if (!cfg.autoDetectMods) {
            LOGGER.info("Auto-detect mods disabled, skipping mod compatibility initialization");
            return;
        }

        // Apply disabled mods from config
        if (cfg.disabledCompatMods != null && !cfg.disabledCompatMods.isEmpty()) {
            registry.setDisabledMods(Set.copyOf(cfg.disabledCompatMods));
            LOGGER.debug("Disabled compatibility for mods: {}", cfg.disabledCompatMods);
        }

        // Register known mod compatibility strategies
        KnownModCompat.registerAll(registry);

        // Detect installed mods using Fabric Loader
        try {
            java.util.Set<String> modIds = ModCompatRegistry.detectInstalledMods();
            registry.setInstalledMods(modIds);
            LOGGER.info("Mod compatibility initialized: {} mods detected", modIds.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to detect installed mods for compatibility", e);
        }
    }

    public Executor serverThreadPoolExecutor() {
        // Nothing to do on neoforge
        return null;
    }

    /**
     * Returns the global mod compatibility registry.
     * @return the ModCompatRegistry instance
     */
    public ModCompatRegistry modCompatRegistry() {
        return ModCompatRegistry.getInstance();
    }

    public void loaderSpecificSetup(MinecraftServer minecraftServer) {
        // Nothing to do on neoforge
        // NOTE: We intentionally do NOT trigger server lifecycle events here
        // because it causes conflicts with other mods (e.g., Xaero's mods) that detect
        // multiple servers running. The virtual server is only used for world generation
        // sampling and should not trigger server lifecycle events.
    }

    public void loaderSpecificTeardown(MinecraftServer minecraftServer) {
        // Nothing to do for neoforge
    }

    public WorldPreviewConfig cfg() {
        return cfg;
    }

    public WorkManager workManager() {
        return workManager;
    }

    public PreviewMappingData biomeColorMap() {
        return previewMappingData;
    }

    public RenderSettings renderSettings() {
        return renderSettings;
    }

    public Path userColorConfigFile() {
        return userColorConfigFile;
    }

    public Path configDir() {
        return configDir;
    }

    public Path analysisDir() {
        return analysisDir;
    }

    public AnalysisRepository analysisRepository() {
        return analysisRepository;
    }

    public void clearAnalysisData() {
        clearDirectory(analysisDir);
    }

    private void clearDirectory(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to clear World Preview data: " + path, e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan World Preview data: " + root, e);
        }
    }

    public void loadConfig() {
        LOGGER.info("Loading config file: {}", configFile);
        caeruleusTait.world.preview.config.ConfigLoader configLoader =
                new caeruleusTait.world.preview.config.ConfigLoader(gson);
        cfg = configLoader.loadConfig(configFile);
        renderSettings = configLoader.loadRenderSettings(renderConfigFile);
    }

    public void saveConfig() {
        saveConfig(cfg, renderSettings);
    }

    public void saveConfig(WorldPreviewConfig config, RenderSettings render) {
        saveConfig(config, render, null);
    }

    public void saveConfig(WorldPreviewConfig config, RenderSettings render,
                           Map<Identifier, PreviewMappingData.ColorEntry> userColorConfig) {
        if (config == null || render == null) {
            throw new IllegalArgumentException("config and render are required");
        }
        config.validate();
        render.validate();
        LOGGER.info("Saving config file: {}", configFile);
        caeruleusTait.world.preview.config.ConfigLoader configLoader =
                new caeruleusTait.world.preview.config.ConfigLoader(gson);
        configLoader.saveConfig(configFile, config);
        configLoader.saveConfig(renderConfigFile, render);
        if (userColorConfig != null) {
            writeUserColorConfig(userColorConfig);
        }
    }

    private String userColorJson(Map<Identifier, PreviewMappingData.ColorEntry> userColorConfig) {
        record Entry(int r, int g, int b, boolean cave) {}
        Map<String, Entry> writeData = userColorConfig.entrySet().stream().collect(Collectors.toMap(
                x -> x.getKey().toString(), x -> {
                    PreviewMappingData.ColorEntry raw = x.getValue();
                    return new Entry((raw.color >> 16) & 0xFF, (raw.color >> 8) & 0xFF,
                            raw.color & 0xFF, raw.cave.orElseThrow());
                }));
        return gson.toJson(writeData) + "\n";
    }

    public void writeMissingColors(List<String> missing) {
        try {
            Files.deleteIfExists(missingColorsFile);
            if (missing.isEmpty()) {
                return;
            }
            LOGGER.warn("No color mapping for {} biomes found. The list of biomes without a color mapping can be found in {}", missing.size(), missingColorsFile);
            final String raw = gson.toJson(missing);
            Files.writeString(missingColorsFile, raw + "\n");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeMissingStructures(List<String> missing) {
        try {
            Files.deleteIfExists(missingStructuresFile);
            if (missing.isEmpty()) {
                return;
            }
            LOGGER.warn("No structure data for {} structure found. The list of structures without data can be found in {}", missing.size(), missingStructuresFile);
            final String raw = gson.toJson(missing);
            Files.writeString(missingStructuresFile, raw + "\n");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeUserColorConfig(Map<Identifier, PreviewMappingData.ColorEntry> userColorConfig) {
        try {
            AtomicFiles.writeStringAtomic(userColorConfigFile, userColorJson(userColorConfig));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static int nativeColor(int orig) {
        /*
        final int R = (orig >> 16) & 0xFF;
        final int G = (orig >> 8) & 0xFF;
        final int B = (orig >> 0) & 0xFF;
        return (R << 16) | (G << 8) | (B << 0) | (0xFF << 24);
         */
        return orig | (0xFF << 24);
    }
}