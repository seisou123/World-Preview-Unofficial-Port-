package caeruleusTait.world.preview.infra.minecraft;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.util.*;

/**
 * Implementation of {@link MinecraftResourceRegistry} using RegistryAccess.
 *
 * <p>Provides type-safe lookup of registered Minecraft objects
 * (biomes, structures, dimensions, etc.) by resource key.
 *
 * <p>Uses the same registry access patterns as {@code SampleUtils}
 * to maintain compatibility with the existing codebase.
 */
public class MinecraftResourceRegistryImpl implements MinecraftResourceRegistry {

    private final RegistryAccess registryAccess;

    public MinecraftResourceRegistryImpl(RegistryAccess registryAccess) {
        this.registryAccess = registryAccess;
    }

    @Override
    public Optional<Object> lookup(String registryName, String identifier) {
        try {
            // Use raw types to avoid generic casting issues
            @SuppressWarnings("rawtypes")
            net.minecraft.core.Registry registry = (net.minecraft.core.Registry)
                    registryAccess.lookupOrThrow(ResourceKey.createRegistryKey(
                            Identifier.parse(registryName)));
            return Optional.of(registry.get(Identifier.parse(identifier)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Map<String, Object> entries(String registryName) {
        try {
            @SuppressWarnings("rawtypes")
            net.minecraft.core.Registry registry = (net.minecraft.core.Registry)
                    registryAccess.lookupOrThrow(ResourceKey.createRegistryKey(
                            Identifier.parse(registryName)));
            Map<String, Object> result = new HashMap<>();
            registry.forEach(v -> {
                var key = registry.getKey(v);
                if (key != null) result.put(key.toString(), v);
            });
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    @Override
    public Set<String> biomeIds() {
        return getIds(Registries.BIOME);
    }

    @Override
    public Set<String> structureIds() {
        return getIds(Registries.STRUCTURE);
    }

    @Override
    public Set<String> dimensionIds() {
        return getIds(Registries.LEVEL_STEM);
    }

    /**
     * Returns all resource IDs in the specified registry.
     *
     * @param registryKey the registry key to query
     * @return a set of resource ID strings
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Set<String> getIds(ResourceKey<? extends net.minecraft.core.Registry<?>> registryKey) {
        try {
            net.minecraft.core.Registry registry = (net.minecraft.core.Registry)
                    registryAccess.lookupOrThrow(registryKey);
            Set<String> ids = new HashSet<>();
            registry.forEach(v -> {
                var key = registry.getKey(v);
                if (key != null) ids.add(key.toString());
            });
            return ids;
        } catch (Exception e) {
            return Set.of();
        }
    }
}
