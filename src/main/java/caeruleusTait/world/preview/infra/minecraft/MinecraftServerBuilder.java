package caeruleusTait.world.preview.infra.minecraft;

/**
 * Builder interface for constructing virtual Minecraft server environments.
 *
 * <p>Abstracts the complex server creation logic currently embedded in
 * {@code SampleUtils.createForWorldgen()}, which involves setting up
 * registries, resource packs, data fixers, and dimension configuration.
 *
 * <p>Implementations are provided by the infrastructure layer using
 * Minecraft's internal APIs (accessed via mixins).
 */
public interface MinecraftServerBuilder {

    /**
     * Sets the world seed for the server.
     *
     * @param seed the world seed
     * @return this builder
     */
    MinecraftServerBuilder seed(long seed);

    /**
     * Sets the proxy for network operations.
     *
     * @param proxy the proxy
     * @return this builder
     */
    MinecraftServerBuilder proxy(java.net.Proxy proxy);

    /**
     * Sets the temporary data pack directory.
     *
     * @param path the temp data pack path, or {@code null} for none
     * @return this builder
     */
    MinecraftServerBuilder tempDataPackDir(java.nio.file.Path path);

    /**
     * Sets whether to use an existing server instance instead of creating a new one.
     *
     * @param server the existing server, or {@code null} to create a new one
     * @return this builder
     */
    MinecraftServerBuilder existingServer(Object server);

    /**
     * Builds and returns the configured server environment.
     *
     * @return the server holder containing the built server and related objects
     * @throws Exception if the server cannot be created
     */
    MinecraftServerHolder build() throws Exception;
}
