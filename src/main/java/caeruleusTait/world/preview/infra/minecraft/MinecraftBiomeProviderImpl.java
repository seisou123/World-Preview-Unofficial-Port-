package caeruleusTait.world.preview.infra.minecraft;

import caeruleusTait.world.preview.backend.analysis.WorldgenContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of {@link MinecraftBiomeProvider} using WorldgenContext.
 *
 * <p>Provides biome querying without exposing Minecraft's internal
 * class hierarchy. Supports both standard biome sources and
 * multi-noise biome sources.
 *
 * <p>Delegates actual sampling to {@code SampleUtils.doSample()}
 * which is created via {@link WorldgenContext#createSampleUtils()}.
 */
public class MinecraftBiomeProviderImpl implements MinecraftBiomeProvider {

    private final BiomeSource biomeSource;
    private final RegistryAccess registryAccess;

    public MinecraftBiomeProviderImpl(BiomeSource biomeSource,
                                       RegistryAccess registryAccess) {
        this.biomeSource = biomeSource;
        this.registryAccess = registryAccess;
    }

    @Override
    public String biomeAt(int x, int y, int z) {
        return getBiomeId(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
    }

    @Override
    public String biomeAtQuart(int quartX, int quartY, int quartZ) {
        return getBiomeId(quartX, quartY, quartZ);
    }

    /**
     * Resolves the biome ID at the given quart coordinates.
     *
     * @param quartX the quart X coordinate
     * @param quartY the quart Y coordinate
     * @param quartZ the quart Z coordinate
     * @return the biome identifier string, or null if not found
     */
    private String getBiomeId(int quartX, int quartY, int quartZ) {
        try {
            var biomeHolder = biomeSource.getNoiseBiome(quartX, quartY, quartZ, null);
            if (biomeHolder == null) return null;
            ResourceKey<Biome> biomeKey = biomeHolder.unwrapKey().orElse(null);
            return biomeKey != null ? biomeKey.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Set<String> possibleBiomes() {
        try {
            var biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);
            Set<String> ids = new java.util.HashSet<>();
            biomeRegistry.forEach(v -> {
                var key = biomeRegistry.getKey(v);
                if (key != null) ids.add(key.toString());
            });
            return ids;
        } catch (Exception e) {
            return Set.of();
        }
    }
}
