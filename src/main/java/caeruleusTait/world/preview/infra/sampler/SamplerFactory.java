package caeruleusTait.world.preview.infra.sampler;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Factory for creating chunk samplers based on configuration.
 *
 * <p>Replaces the ad-hoc sampler creation logic in {@code RenderSettings}
 * and {@code WorkManager}. Supports registration of custom sampler types
 * and lookup by sampler type name.
 */
public final class SamplerFactory {

    private final ConcurrentHashMap<String, Function<SamplerConfig, ChunkSampler>> registry = new ConcurrentHashMap<>();

    /**
     * Registers a sampler creator for the given type name.
     *
     * @param typeName the sampler type name (e.g., "AUTO", "FULL", "QUARTER", "SINGLE")
     * @param creator a function that creates a sampler from a config
     */
    public void register(String typeName, Function<SamplerConfig, ChunkSampler> creator) {
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(creator, "creator");
        if (typeName.isBlank()) {
            throw new IllegalArgumentException("typeName must not be blank");
        }
        registry.put(typeName.toUpperCase(), creator);
    }

    /**
     * Creates a sampler for the given type and configuration.
     *
     * @param typeName the sampler type name
     * @param config the sampler configuration
     * @return a new chunk sampler
     * @throws IllegalArgumentException if the type is not registered
     */
    public ChunkSampler create(String typeName, SamplerConfig config) {
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(config, "config");
        Function<SamplerConfig, ChunkSampler> creator = registry.get(typeName.toUpperCase());
        if (creator == null) {
            throw new IllegalArgumentException("Unknown sampler type: " + typeName
                    + ". Registered types: " + registry.keySet());
        }
        return creator.apply(config);
    }

    /**
     * Checks whether a sampler type is registered.
     *
     * @param typeName the sampler type name
     * @return {@code true} if the type is registered
     */
    public boolean isRegistered(String typeName) {
        return typeName != null && registry.containsKey(typeName.toUpperCase());
    }

    /**
     * Returns all registered sampler type names.
     *
     * @return an unmodifiable set of type names
     */
    public java.util.Set<String> registeredTypes() {
        return java.util.Set.copyOf(registry.keySet());
    }

    /**
     * Unregisters a sampler type.
     *
     * @param typeName the sampler type name
     * @return {@code true} if the type was registered and is now removed
     */
    public boolean unregister(String typeName) {
        return typeName != null && registry.remove(typeName.toUpperCase()) != null;
    }
}
