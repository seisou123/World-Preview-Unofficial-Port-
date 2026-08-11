package caeruleusTait.world.preview.infra.minecraft;

/**
 * Abstraction for accessing biome data from Minecraft.
 *
 * <p>Replaces direct access to {@code BiomeSource} scattered across
 * {@code WorkUnit} implementations and {@code SampleUtils}.
 *
 * <p>Provides simplified methods for querying biome information
 * without exposing Minecraft's internal class hierarchy.
 */
public interface MinecraftBiomeProvider {

    /**
     * Returns the biome ID at the given block coordinates.
     *
     * @param x the block X coordinate
     * @param y the block Y coordinate
     * @param z the block Z coordinate
     * @return the biome identifier (e.g., "minecraft:plains"), or null if not found
     */
    String biomeAt(int x, int y, int z);

    /**
     * Returns the biome ID at the given quart coordinates.
     *
     * @param quartX the quart X coordinate (block X / 4)
     * @param quartY the quart Y coordinate (block Y / 4)
     * @param quartZ the quart Z coordinate (block Z / 4)
     * @return the biome identifier, or null if not found
     */
    String biomeAtQuart(int quartX, int quartY, int quartZ);

    /**
     * Returns all possible biomes for this provider's dimension.
     *
     * @return a set of biome identifiers
     */
    java.util.Set<String> possibleBiomes();

    /**
     * Returns the noise-based biome at the given coordinates.
     * This is used for multi-noise biome sources (Nether, etc.).
     *
     * @param x the block X coordinate
     * @param y the block Y coordinate
     * @param z the block Z coordinate
     * @return the biome identifier, or null if not applicable
     */
    default String noiseBiomeAt(int x, int y, int z) {
        return biomeAt(x, y, z);
    }
}
