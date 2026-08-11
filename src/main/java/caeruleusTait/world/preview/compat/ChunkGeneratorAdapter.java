package caeruleusTait.world.preview.compat;

import caeruleusTait.world.preview.backend.analysis.WorldgenContext;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.Set;

/**
 * Adapts different ChunkGenerator implementations for World Preview sampling.
 *
 * <p>This interface decouples the sampling logic from specific
 * ChunkGenerator implementations, allowing mod-specific adapters
 * to handle custom world generation mechanics (e.g., Terralith,
 * Biomes O' Plenty, TerraFirmaCraft).
 *
 * <p>Adapters are selected by {@link ModCompatRegistry#selectAdapter(ChunkGenerator, WorldgenContext)}
 * based on the ChunkGenerator type and installed mods.
 */
public interface ChunkGeneratorAdapter {

    /**
     * Returns the supported ChunkGenerator type for this adapter.
     *
     * @return the class of supported ChunkGenerator
     */
    Class<? extends ChunkGenerator> supportedType();

    /**
     * Returns true if this adapter can handle the given ChunkGenerator.
     *
     * @param chunkGenerator the ChunkGenerator to check
     * @return true if applicable
     */
    boolean isApplicable(ChunkGenerator chunkGenerator);

    /**
     * Returns the minimum Y level for this generator's dimension.
     *
     * @param levelStem the dimension stem
     * @return the minimum Y level
     */
    int minY(LevelStem levelStem);

    /**
     * Returns the maximum Y level for this generator's dimension.
     *
     * @param levelStem the dimension stem
     * @return the maximum Y level
     */
    int maxY(LevelStem levelStem);

    /**
     * Generates biome data for a chunk.
     *
     * @param ctx the world generation context
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @return a 2D array of biome IDs indexed as [x][z] (4x4 per chunk section)
     */
    short[][] generateBiomes(WorldgenContext ctx, int chunkX, int chunkZ);

    /**
     * Returns all structure starts at the given chunk.
     *
     * @param ctx the world generation context
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @return a set of structure identifiers
     */
    Set<String> structureStarts(WorldgenContext ctx, int chunkX, int chunkZ);

    /**
     * Computes the surface height at the given block coordinates.
     *
     * @param ctx the world generation context
     * @param x the block X coordinate
     * @param z the block Z coordinate
     * @return the surface height (Y coordinate)
     */
    int surfaceHeight(WorldgenContext ctx, int x, int z);

    /**
     * Whether this adapter supports heightmap sampling.
     *
     * @return true if heightmap sampling is supported
     */
    default boolean supportsHeightmap() {
        return true;
    }

    /**
     * Whether this adapter supports structure sampling.
     *
     * @return true if structure sampling is supported
     */
    default boolean supportsStructures() {
        return true;
    }

    /**
     * Factory for creating adapters from a WorldgenContext and ModCompat.
     */
    @FunctionalInterface
    interface Factory {
        /**
         * Creates a ChunkGeneratorAdapter for the given context and mod compatibility.
         *
         * @param ctx the world generation context
         * @param compat the mod compatibility (may be null for vanilla adapter)
         * @return a new ChunkGeneratorAdapter
         */
        ChunkGeneratorAdapter create(WorldgenContext ctx, ModCompat compat);
    }
}
