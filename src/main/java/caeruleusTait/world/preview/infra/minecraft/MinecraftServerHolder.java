package caeruleusTait.world.preview.infra.minecraft;

/**
 * Holds a Minecraft server instance and manages its lifecycle.
 *
 * <p>Ensures thread-safe access to the server and its resources,
 * and handles cleanup when the server is no longer needed.
 *
 * <p>Replaces the ad-hoc server lifecycle management in
 * {@code WorldgenContext} and {@code WorkManager}.
 */
public interface MinecraftServerHolder extends AutoCloseable {

    /**
     * Returns the Minecraft server instance.
     *
     * @return the server (may be {@code null} if using a dummy server)
     */
    Object server();

    /**
     * Returns the registry access for this server.
     *
     * @return the layered registry access
     */
    Object registryAccess();

    /**
     * Returns the biome source for the configured dimension.
     *
     * @return the biome source
     */
    Object biomeSource();

    /**
     * Returns the chunk generator for the configured dimension.
     *
     * @return the chunk generator
     */
    Object chunkGenerator();

    /**
     * Returns the dimension type.
     *
     * @return the dimension type
     */
    Object dimensionType();

    /**
     * Returns the level stem (dimension configuration).
     *
     * @return the level stem
     */
    Object levelStem();

    /**
     * Returns the world options (seed, flags, etc.).
     *
     * @return the world options
     */
    Object worldOptions();

    /**
     * Returns the resource manager for loading data packs.
     *
     * @return the closeable resource manager
     */
    Object resourceManager();

    /**
     * Returns whether this holder owns the server (i.e., should close it).
     *
     * @return {@code true} if this holder is responsible for closing the server
     */
    boolean ownsServer();

    /**
     * Releases all resources held by this holder.
     * Safe to call multiple times.
     */
    @Override
    void close();
}
