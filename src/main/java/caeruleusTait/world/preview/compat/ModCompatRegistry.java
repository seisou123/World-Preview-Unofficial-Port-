package caeruleusTait.world.preview.compat;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.backend.analysis.WorldgenContext;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry for mod compatibility strategies.
 *
 * <p>Detects installed mods at runtime and provides appropriate
 * {@link ChunkGeneratorAdapter} instances for sampling.
 *
 * <p>The registry is initialized during {@code WorldPreview.onInitialize()}
 * and maintains thread-safe access via ConcurrentHashMap.
 *
 * <p>Adapters are selected based on:
 * 1. Installed mod detection (Fabric API ModContainer enumeration)
 * 2. ChunkGenerator class matching
 * 3. User configuration (disabled mods list)
 *
 * <p>Fallback: When no mod-specific adapter is found,
 * {@link VanillaChunkGeneratorAdapter} is used.
 */
public final class ModCompatRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("world_preview_compat");

    /** Map of modId -> ModCompat for all registered compatibility strategies. */
    private final Map<String, ModCompat> compatMap = new ConcurrentHashMap<>();

    /** Set of installed mod IDs detected at runtime. */
    private final Set<String> installedMods = ConcurrentHashMap.newKeySet();

    /** Set of mod IDs disabled by user configuration. */
    private final Set<String> disabledMods = ConcurrentHashMap.newKeySet();

    private ModCompatRegistry() {
    }

    private static ModCompatRegistry instance;

    /**
     * Returns the singleton ModCompatRegistry instance.
     *
     * @return the global registry instance
     */
    public static ModCompatRegistry getInstance() {
        if (instance == null) {
            instance = new ModCompatRegistry();
        }
        return instance;
    }

    /**
     * Registers a mod compatibility strategy.
     *
     * @param compat the compatibility description
     * @throws NullPointerException if compat is null
     */
    public void register(ModCompat compat) {
        Objects.requireNonNull(compat, "compat must not be null");
        compatMap.put(compat.modId(), compat);
        LOGGER.debug("Registered compatibility for mod: {} ({})", compat.modName(), compat.modId());
    }

    /**
     * Returns the compatibility strategy for a given mod ID.
     *
     * @param modId the mod ID to look up
     * @return optional compatibility strategy
     */
    public Optional<ModCompat> getCompat(String modId) {
        Objects.requireNonNull(modId, "modId must not be null");
        ModCompat compat = compatMap.get(modId);
        return compat != null ? Optional.of(compat) : Optional.empty();
    }

    /**
     * Returns all registered compatibility strategies.
     *
     * @return unmodifiable map of modId -> ModCompat
     */
    public Map<String, ModCompat> all() {
        return Collections.unmodifiableMap(compatMap);
    }

    /**
     * Returns all installed mod IDs detected in the runtime.
     *
     * @return unmodifiable set of mod IDs
     */
    public Set<String> installedMods() {
        return Collections.unmodifiableSet(installedMods);
    }

    /**
     * Returns all disabled mod IDs from user configuration.
     *
     * @return unmodifiable set of disabled mod IDs
     */
    public Set<String> disabledMods() {
        return Collections.unmodifiableSet(disabledMods);
    }

    /**
     * Records installed mods at runtime. Called during onInitialize().
     *
     * <p>Uses Fabric Loader to enumerate all installed mod containers
     * via the internal mod list field.
     *
     * @param modIds set of detected mod IDs
     */
    public void setInstalledMods(Set<String> modIds) {
        Objects.requireNonNull(modIds, "modIds must not be null");
        installedMods.clear();
        installedMods.addAll(modIds);
        LOGGER.info("Detected {} installed mods: {}", installedMods.size(), installedMods);
    }

    /**
     * Detects installed mods using Fabric Loader.
     *
     * <p>This method accesses Fabric Loader's internal mod list to enumerate
     * all installed mods. It handles both Fabric and non-Fabric environments
     * gracefully.
     *
     * @return a set of detected mod IDs
     */
    public static Set<String> detectInstalledMods() {
        Set<String> modIds = new java.util.HashSet<>();
        try {
            net.fabricmc.loader.api.FabricLoader fabricLoader =
                    net.fabricmc.loader.api.FabricLoader.getInstance();
            // Access the internal mod list field using reflection
            java.lang.reflect.Field modListField = net.fabricmc.loader.api.FabricLoader.class
                    .getDeclaredField("mods");
            modListField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<net.fabricmc.loader.api.ModContainer> modContainers =
                    (java.util.List<net.fabricmc.loader.api.ModContainer>) modListField.get(fabricLoader);
            if (modContainers != null) {
                for (net.fabricmc.loader.api.ModContainer modContainer : modContainers) {
                    String modId = modContainer.getMetadata().getId();
                    modIds.add(modId);
                }
            }
            LOGGER.info("Detected {} mods via Fabric Loader", modIds.size());
        } catch (Exception e) {
            LOGGER.debug("Failed to detect mods via Fabric Loader: {}", e.getMessage());
        }
        return modIds;
    }

    /**
     * Sets disabled mods from user configuration.
     *
     * @param disabledIds set of mod IDs to disable compatibility for
     */
    public void setDisabledMods(Set<String> disabledIds) {
        Objects.requireNonNull(disabledIds, "disabledIds must not be null");
        disabledMods.clear();
        disabledMods.addAll(disabledIds);
    }

    /**
     * Checks if a mod is installed and enabled.
     *
     * @param modId the mod ID to check
     * @return true if installed and not disabled
     */
    public boolean isModEnabled(String modId) {
        return installedMods.contains(modId) && !disabledMods.contains(modId);
    }

    /**
     * Resolves the active compatibility profile from config.
     *
     * <p>"auto" keeps default detection behaviour, "vanilla" forces the vanilla
     * adapter, and any other value restricts selection to the compat registered
     * for that exact mod id. Falls back to "auto" when the mod or config is not
     * yet available (e.g. during early init or in unit tests).
     */
    private String activeCompatProfile() {
        WorldPreview wp = WorldPreview.get();
        if (wp != null && wp.cfg() != null) {
            String profile = wp.cfg().activeCompatProfile;
            if (profile != null && !profile.isBlank()) {
                return profile;
            }
        }
        return "auto";
    }

    /**
     * Selects the appropriate ChunkGeneratorAdapter for a given ChunkGenerator.
     *
     * <p>Selection strategy (in order of priority):
     * 1. If ChunkGenerator is null, return VanillaChunkGeneratorAdapter
     * 2. Honour {@code activeCompatProfile}: "vanilla" short-circuits to the
     *    vanilla adapter; a specific mod id restricts selection to that mod's
     *    compat; "auto" (the default) keeps previous behaviour
     * 3. Iterate through registered adapters for enabled mods
     * 4. Return the first adapter where isApplicable() returns true
     * 5. Fallback to VanillaChunkGeneratorAdapter
     *
     * <p>This method is called during sampling initialization and should
     * complete quickly to avoid blocking the main thread.
     *
     * @param chunkGenerator the ChunkGenerator to adapt (may be null)
     * @param ctx the world generation context (may be null for vanilla)
     * @return the selected adapter (never null)
     */
    public ChunkGeneratorAdapter selectAdapter(ChunkGenerator chunkGenerator, WorldgenContext ctx) {
        if (chunkGenerator == null) {
            LOGGER.warn("ChunkGenerator is null, falling back to VanillaChunkGeneratorAdapter");
            return VanillaChunkGeneratorAdapter.FACTORY.create(ctx, null);
        }

        String genClass = chunkGenerator.getClass().getSimpleName();
        LOGGER.debug("Selecting adapter for {} class", genClass);

        String profile = activeCompatProfile();
        if ("vanilla".equalsIgnoreCase(profile)) {
            LOGGER.info("activeCompatProfile=vanilla, forcing VanillaChunkGeneratorAdapter for {}", genClass);
            return VanillaChunkGeneratorAdapter.FACTORY.create(ctx, null);
        }

        boolean profileIsAuto = "auto".equalsIgnoreCase(profile);

        // Try to find a mod-specific adapter for the ChunkGenerator class
        for (ModCompat compat : compatMap.values()) {
            if (!isModEnabled(compat.modId())) {
                continue;
            }
            // A specific profile only allows the compat of that mod id.
            if (!profileIsAuto && !profile.equalsIgnoreCase(compat.modId())) {
                continue;
            }
            for (ChunkGeneratorAdapter.Factory factory : compat.adapters()) {
                try {
                    ChunkGeneratorAdapter adapter = factory.create(ctx, compat);
                    if (adapter.isApplicable(chunkGenerator)) {
                        LOGGER.info("Selected adapter {} for {} ({})",
                                compat.modName(), genClass, chunkGenerator.getClass().getName());
                        return adapter;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to create adapter for {}: {}", compat.modName(), e.getMessage());
                }
            }
        }

        // Fallback to vanilla adapter
        LOGGER.debug("No mod-specific adapter found, using VanillaChunkGeneratorAdapter for {}", genClass);
        return VanillaChunkGeneratorAdapter.FACTORY.create(ctx, null);
    }

    /**
     * Returns all enabled mod compatibilities for a given context.
     *
     * @param ctx the world generation context (may be null)
     * @return list of active mod compatibilities
     */
    public List<ModCompat> activeCompat(WorldgenContext ctx) {
        if (ctx == null) return List.of();
        return compatMap.values().stream()
                .filter(c -> isModEnabled(c.modId()))
                .collect(Collectors.toList());
    }

    /**
     * Resets the registry. Used for testing only.
     */
    public void clear() {
        compatMap.clear();
        installedMods.clear();
        disabledMods.clear();
    }

    /**
     * Resets the singleton instance. Used for testing only.
     */
    public static void resetInstance() {
        instance = new ModCompatRegistry();
    }
}
