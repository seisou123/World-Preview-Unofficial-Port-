package caeruleusTait.world.preview.compat;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Describes compatibility information for a single mod.
 *
 * <p>Used by {@link ModCompatRegistry} to register mod-specific
 * compatibility strategies including custom chunk generator adapters.
 *
 * <p>Each ModCompat instance represents one mod's integration point
 * with World Preview, specifying adapters and optional configuration
 * overrides.
 */
public record ModCompat(
    String modId,
    String modName,
    String version,
    boolean required,
    boolean enabledByDefault,
    List<ChunkGeneratorAdapter.Factory> adapters,
    Optional<Consumer<caeruleusTait.world.preview.WorldPreviewConfig>> configOverride
) {
    public ModCompat {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
        if (adapters == null) {
            adapters = List.of();
        }
        if (configOverride == null) {
            configOverride = Optional.empty();
        }
    }

    /**
     * Creates a ModCompat with no adapters and no config override.
     *
     * @param modId the unique mod identifier
     * @param modName the display name of the mod
     * @param version the supported mod version
     * @param required whether this mod is required for compatibility
     * @param enabledByDefault whether compatibility is enabled by default
     */
    public ModCompat(String modId, String modName, String version,
                     boolean required, boolean enabledByDefault) {
        this(modId, modName, version, required, enabledByDefault,
             List.of(), Optional.empty());
    }

    /**
     * Creates a ModCompat with the specified adapters but no config override.
     *
     * @param modId the unique mod identifier
     * @param modName the display name of the mod
     * @param version the supported mod version
     * @param required whether this mod is required for compatibility
     * @param enabledByDefault whether compatibility is enabled by default
     * @param adapters the list of adapter factories
     */
    public ModCompat(String modId, String modName, String version,
                     boolean required, boolean enabledByDefault,
                     List<ChunkGeneratorAdapter.Factory> adapters) {
        this(modId, modName, version, required, enabledByDefault,
             adapters == null ? List.of() : adapters, Optional.empty());
    }

    /**
     * Returns a new ModCompat with the specified adapters, preserving other fields.
     *
     * @param adapters the list of adapter factories
     * @return a new ModCompat instance
     */
    public ModCompat withAdapters(List<ChunkGeneratorAdapter.Factory> adapters) {
        return new ModCompat(modId, modName, version, required,
                enabledByDefault, adapters, configOverride);
    }

    /**
     * Returns a new ModCompat with a config override, preserving other fields.
     *
     * @param override the configuration override consumer
     * @return a new ModCompat instance
     */
    public ModCompat withConfigOverride(Consumer<caeruleusTait.world.preview.WorldPreviewConfig> override) {
        return new ModCompat(modId, modName, version, required,
                enabledByDefault, adapters, Optional.of(override));
    }
}
