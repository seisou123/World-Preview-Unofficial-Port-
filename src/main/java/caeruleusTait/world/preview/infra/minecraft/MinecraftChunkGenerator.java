package caeruleusTait.world.preview.infra.minecraft;

/**
 * Abstraction for chunk generation in Minecraft.
 *
 * <p>Replaces direct access to {@code ChunkGenerator} scattered across
 * {@code WorkUnit} implementations and {@code SampleUtils}.
 *
 * <p>Provides simplified methods for generating chunk data
 * (biomes, structures, heightmaps, noise) without exposing
 * Minecraft's internal class hierarchy.
 */
public interface MinecraftChunkGenerator {

    /**
     * Generates biome data for a chunk.
     *
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @return a 2D array of biome IDs indexed as [z][x] (4x4 per chunk section)
     */
    short[][] generateBiomes(int chunkX, int chunkZ);

    /**
     * Checks whether a structure starts at the given chunk.
     *
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @param structureId the structure identifier (e.g., "minecraft:village")
     * @return {@code true} if the structure starts at this chunk
     */
    boolean hasStructureStart(int chunkX, int chunkZ, String structureId);

    /**
     * Returns all structure starts at the given chunk.
     *
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @return a set of structure identifiers that start at this chunk
     */
    java.util.Set<String> structureStarts(int chunkX, int chunkZ);

    /**
     * Computes the surface height at the given block coordinates.
     *
     * @param x the block X coordinate
     * @param z the block Z coordinate
     * @return the surface height (Y coordinate)
     */
    int surfaceHeight(int x, int z);

    /**
     * Returns the minimum Y level for this generator's dimension.
     *
     * @return the minimum Y level
     */
    int minY();

    /**
     * Returns the maximum Y level for this generator's dimension.
     *
     * @return the maximum Y level
     */
    int maxY();

    /**
     * Returns the total height of this generator's dimension.
     *
     * @return the total height (maxY - minY)
     */
    default int height() {
        return maxY() - minY();
    }
}
