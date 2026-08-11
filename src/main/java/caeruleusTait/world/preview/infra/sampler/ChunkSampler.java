package caeruleusTait.world.preview.infra.sampler;

/**
 * Interface for sampling chunk data at various precisions.
 *
 * <p>This is the domain-level abstraction that replaces the existing
 * {@code caeruleusTait.world.preview.backend.sampler.ChunkSampler} interface.
 * The old interface is tied to Minecraft classes ({@code ChunkPos}, {@code BlockPos},
 * {@code WorkResult}); this new interface uses plain Java types.
 *
 * <p>Implementations are provided by the infrastructure layer and delegate
 * to the actual Minecraft world generation code.
 */
public interface ChunkSampler {

    /**
     * Returns the configuration for this sampler.
     *
     * @return the sampler configuration
     */
    SamplerConfig config();

    /**
     * Samples a single chunk at the given coordinates.
     *
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @param y the Y level to sample at
     * @return the sampling result
     */
    SamplerResult sample(int chunkX, int chunkZ, int y);

    /**
     * Returns the block stride (distance between sampled positions).
     * A stride of 1 means every block; 4 means every 4th block.
     *
     * @return the block stride
     */
    default int blockStride() {
        return config().blockStride();
    }

    /**
     * Returns the number of positions sampled per chunk.
     * This is {@code ceil(16 / blockStride())^2} for a standard 16x16 chunk.
     *
     * @return the number of sample positions per chunk
     */
    default int positionsPerChunk() {
        int stride = blockStride();
        int perSide = (16 + stride - 1) / stride; // ceiling division
        return perSide * perSide;
    }
}
