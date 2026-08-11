package caeruleusTait.world.preview.backend.analysis;

import caeruleusTait.world.preview.backend.worker.SampleUtils;
import caeruleusTait.world.preview.compat.ChunkGeneratorAdapter;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Path;
import java.util.Objects;

public final class WorldgenContext implements AutoCloseable {
    private final WorldOptions worldOptions;
    private final LevelStem levelStem;
    private final DimensionType dimensionType;
    private final ChunkGenerator chunkGenerator;
    private final BiomeSource biomeSource;
    private final LayeredRegistryAccess<RegistryLayer> registryAccess;
    private final WorldDataConfiguration worldDataConfiguration;
    private final Proxy proxy;
    private final Path tempDataPackDir;
    private final MinecraftServer server;
    private volatile SampleUtils sampleUtils;
    private volatile boolean ownsSampleUtils = true;
    private volatile ChunkGeneratorAdapter chunkGeneratorAdapter;

    public WorldgenContext(
            WorldOptions worldOptions,
            LevelStem levelStem,
            LayeredRegistryAccess<RegistryLayer> registryAccess,
            WorldDataConfiguration worldDataConfiguration,
            Proxy proxy,
            @Nullable Path tempDataPackDir,
            @Nullable MinecraftServer server
    ) {
        this.worldOptions = Objects.requireNonNull(worldOptions, "worldOptions");
        this.levelStem = Objects.requireNonNull(levelStem, "levelStem");
        this.registryAccess = Objects.requireNonNull(registryAccess, "registryAccess");
        this.worldDataConfiguration = Objects.requireNonNull(worldDataConfiguration, "worldDataConfiguration");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.tempDataPackDir = tempDataPackDir;
        this.server = server;
        this.dimensionType = levelStem.type().value();
        this.chunkGenerator = levelStem.generator();
        this.biomeSource = chunkGenerator.getBiomeSource();
    }

    public SampleUtils createSampleUtils() throws IOException {
        SampleUtils su = sampleUtils;
        if (su != null) return su;
        synchronized (this) {
            su = sampleUtils;
            if (su != null) return su;
            sampleUtils = su = createSampleUtils(worldOptions);
            return su;
        }
    }

    public SampleUtils createSampleUtils(long seed) throws IOException {
        return createSampleUtils(worldOptions.withSeed(java.util.OptionalLong.of(seed)));
    }

    private SampleUtils createSampleUtils(WorldOptions options) throws IOException {
        LevelHeightAccessor height = LevelHeightAccessor.create(dimensionType.minY(), dimensionType.height());
        return SampleUtils.createForWorldgen(
                server,
                biomeSource,
                chunkGenerator,
                registryAccess,
                options,
                levelStem,
                height,
                worldDataConfiguration,
                proxy,
                tempDataPackDir
        );
    }

    public WorldgenContext createSeedContext(long seed, LevelStem seedLevelStem) {
        return new WorldgenContext(
                worldOptions.withSeed(java.util.OptionalLong.of(seed)),
                Objects.requireNonNull(seedLevelStem, "seedLevelStem"),
                registryAccess,
                worldDataConfiguration,
                proxy,
                tempDataPackDir,
                server
        );
    }

    public SeedContextCache<WorldgenContext> createSeedContextCache() {
        return new SeedContextCache<>(seed -> createSeedContext(seed, levelStem));
    }

    public SeedContextCache<SampleUtils> createSampleUtilsCache() {
        return new SeedContextCache<>(seed -> {
            try {
                return createSampleUtils(seed);
            } catch (IOException error) {
                throw new IllegalStateException("unable to create sampler for seed " + seed, error);
            }
        });
    }

    public long seed() {
        return worldOptions.seed();
    }

    public String dimension() {
        return levelStem.type().unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
    }

    public String fingerprint() {
        return Long.toHexString(seed()) + ":" + dimension() + ":" + levelStem.generator().getClass().getName();
    }

    public WorldOptions worldOptions() {
        return worldOptions;
    }

    public LevelStem levelStem() {
        return levelStem;
    }

    public DimensionType dimensionType() {
        return dimensionType;
    }

    public ChunkGenerator chunkGenerator() {
        return chunkGenerator;
    }

    public BiomeSource biomeSource() {
        return biomeSource;
    }

    public LayeredRegistryAccess<RegistryLayer> registryAccess() {
        return registryAccess;
    }

    public WorldDataConfiguration worldDataConfiguration() {
        return worldDataConfiguration;
    }

    public Proxy proxy() {
        return proxy;
    }

    public @Nullable Path tempDataPackDir() {
        return tempDataPackDir;
    }

    public @Nullable MinecraftServer server() {
        return server;
    }

    public @Nullable CloseableResourceManager resourceManager() {
        return sampleUtils == null ? null : sampleUtils.resourceManager();
    }

    /**
     * Returns the chunk generator adapter selected for this context's chunk generator.
     * The adapter is lazily initialized on first access.
     *
     * @return the ChunkGeneratorAdapter
     */
    public ChunkGeneratorAdapter chunkGeneratorAdapter() {
        ChunkGeneratorAdapter adapter = chunkGeneratorAdapter;
        if (adapter == null) {
            synchronized (this) {
                adapter = chunkGeneratorAdapter;
                if (adapter == null) {
                    adapter = chunkGeneratorAdapter =
                            caeruleusTait.world.preview.compat.ModCompatRegistry.getInstance()
                                    .selectAdapter(chunkGenerator, this);
                }
            }
        }
        return adapter;
    }

    /**
     * Sets the chunk generator adapter explicitly. Used when the adapter is
     * selected outside this context (e.g., in SampleUtils.createForWorldgen).
     *
     * @param adapter the adapter to set
     */
    public void setChunkGeneratorAdapter(ChunkGeneratorAdapter adapter) {
        this.chunkGeneratorAdapter = adapter;
    }

    /**
     * Relinquishes ownership of the lazily-created {@link SampleUtils} so that
     * {@link #close()} will not close it. Callers that take responsibility for
     * closing the sampler themselves (e.g. {@code WorkManager} for the shared
     * live-preview sampler) should invoke this after obtaining the sampler.
     */
    public void disownSampleUtils() {
        ownsSampleUtils = false;
    }

    @Override
    public synchronized void close() throws Exception {
        if (ownsSampleUtils && sampleUtils != null) {
            sampleUtils.close();
            sampleUtils = null;
        }
    }
}
