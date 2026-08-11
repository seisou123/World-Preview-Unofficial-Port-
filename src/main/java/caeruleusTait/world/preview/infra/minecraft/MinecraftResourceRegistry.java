package caeruleusTait.world.preview.infra.minecraft;

import java.util.Map;
import java.util.Optional;

/**
 * Abstraction for accessing Minecraft resource registries.
 *
 * <p>Replaces direct calls to
 * {@code Minecraft.getInstance().getRegistryManager()} and
 * {@code server.getRegistryAccess()} scattered across the codebase.
 *
 * <p>Provides type-safe lookup of registered Minecraft objects
 * (biomes, structures, dimensions, etc.) by resource key.
 */
public interface MinecraftResourceRegistry {

    /**
     * Looks up a resource by its registry name and identifier.
     *
     * @param registryName the registry name (e.g., "minecraft:biome")
     * @param identifier the resource identifier (e.g., "minecraft:plains")
     * @return the registered resource, or empty if not found
     */
    Optional<Object> lookup(String registryName, String identifier);

    /**
     * Returns all entries in the given registry.
     *
     * @param registryName the registry name
     * @return a map of identifier → resource
     */
    Map<String, Object> entries(String registryName);

    /**
     * Returns all registered biome identifiers.
     *
     * @return a set of biome identifiers
     */
    java.util.Set<String> biomeIds();

    /**
     * Returns all registered structure identifiers.
     *
     * @return a set of structure identifiers
     */
    java.util.Set<String> structureIds();

    /**
     * Returns all registered dimension identifiers.
     *
     * @return a set of dimension identifiers
     */
    java.util.Set<String> dimensionIds();

    /**
     * Checks whether a resource exists in the given registry.
     *
     * @param registryName the registry name
     * @param identifier the resource identifier
     * @return {@code true} if the resource exists
     */
    default boolean contains(String registryName, String identifier) {
        return lookup(registryName, identifier).isPresent();
    }
}
