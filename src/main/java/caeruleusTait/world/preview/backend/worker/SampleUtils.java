// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.storage.PreviewLevel;
import caeruleusTait.world.preview.backend.stubs.DummyMinecraftServer;
import caeruleusTait.world.preview.backend.stubs.DummyServerLevelData;
import caeruleusTait.world.preview.backend.stubs.EmptyAquifer;
import caeruleusTait.world.preview.backend.stubs.IntersectionAquifer;
import caeruleusTait.world.preview.mixin.MinecraftServerAccessor;
import caeruleusTait.world.preview.mixin.NoiseBasedChunkGeneratorAccessor;
import caeruleusTait.world.preview.mixin.NoiseChunkAccessor;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Lifecycle;
import net.minecraft.util.FileUtil;
import net.minecraft.util.Util;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.*;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.minecraft.world.level.validation.PathAllowList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;
import static net.minecraft.core.registries.Registries.LEVEL_STEM;

public class SampleUtils implements AutoCloseable {
    private static final long EXECUTOR_SHUTDOWN_SECONDS = 5;

    private final Path tempDir;
    private final DataFixer dataFixer;
    private final LevelStorageSource.LevelStorageAccess levelStorageAccess;
    private final LevelHeightAccessor levelHeightAccessor;
    private final CloseableResourceManager resourceManager;
    private final BiomeSource biomeSource;
    private final RandomState randomState;
    private final ChunkGenerator chunkGenerator;
    private final RegistryAccess registryAccess;
    private final ChunkGeneratorStructureState chunkGeneratorStructureState;
    private final StructureCheck structureCheck;
    private final StructureManager structureManager;
    private final StructureTemplateManager structureTemplateManager;
    private final PreviewLevel previewLevel;
    private final Registry<Structure> structureRegistry;
    private final ResourceKey<Level> dimension;
    private final NoiseGeneratorSettings noiseGeneratorSettings;
    private final MinecraftServer minecraftServer;
    private final ServerLevel serverLevel;
    /** First dummy ServerLevel created only to fire constructor mixins; closed in close(). */
    private final ServerLevel mixinBootstrapLevel;
    private final WorldPreviewConfig cfg;
    /** Executors created by this SampleUtils (dummy-server path); shut down in close(). */
    private final List<ExecutorService> ownedExecutors = new ArrayList<>();
    /** When true, close() must close resourceManager (dummy path owns it; real server does not). */
    private final boolean ownsResourceManager;
    /** Mod compatibility adapter selected for this chunk generator. */
    private final caeruleusTait.world.preview.compat.ChunkGeneratorAdapter chunkGeneratorAdapter;
    /** Infrastructure abstraction layer for chunk generation. */
    private final caeruleusTait.world.preview.infra.minecraft.MinecraftChunkGenerator minecraftChunkGenerator;

    public static SampleUtils createForWorldgen(
            @Nullable MinecraftServer server,
            BiomeSource biomeSource,
            ChunkGenerator chunkGenerator,
            LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess,
            WorldOptions worldOptions,
            LevelStem levelStem,
            LevelHeightAccessor levelHeightAccessor,
            WorldDataConfiguration worldDataConfiguration,
            Proxy proxy,
            @Nullable Path tempDataPackDir
    ) throws IOException {
        // Select the appropriate chunk generator adapter via mod compatibility registry
        caeruleusTait.world.preview.compat.ChunkGeneratorAdapter adapter =
                selectAdapter(chunkGenerator, null);

        if (server == null) {
            return new SampleUtils(
                    biomeSource,
                    chunkGenerator,
                    layeredRegistryAccess,
                    worldOptions,
                    levelStem,
                    levelHeightAccessor,
                    worldDataConfiguration,
                    proxy,
                    tempDataPackDir,
                    adapter
            );
        }
        return new SampleUtils(server, biomeSource, chunkGenerator, worldOptions, levelStem, levelHeightAccessor, adapter);
    }

    /**
     * Selects the appropriate ChunkGeneratorAdapter for the given chunk generator.
     *
     * @param chunkGenerator the chunk generator
     * @param ctx the worldgen context (may be null)
     * @return the selected adapter
     */
    private static caeruleusTait.world.preview.compat.ChunkGeneratorAdapter selectAdapter(
            ChunkGenerator chunkGenerator,
            caeruleusTait.world.preview.backend.analysis.WorldgenContext ctx) {
        return caeruleusTait.world.preview.compat.ModCompatRegistry.getInstance()
                .selectAdapter(chunkGenerator, ctx);
    }

    /**
     * Create SampleUtils with a <b>real</b> Minecraft server
     */
    public SampleUtils(
            @NotNull MinecraftServer server,
            BiomeSource biomeSource,
            ChunkGenerator chunkGenerator,
            WorldOptions worldOptions,
            LevelStem levelStem,
            LevelHeightAccessor levelHeightAccessor,
            caeruleusTait.world.preview.compat.ChunkGeneratorAdapter adapter
    ) throws IOException {
        this(server, biomeSource, chunkGenerator, worldOptions, levelStem, levelHeightAccessor, adapter, null);
    }

    /**
     * Create SampleUtils with a <b>real</b> Minecraft server
     *
     * @param server the real Minecraft server
     * @param biomeSource the biome source
     * @param chunkGenerator the chunk generator
     * @param worldOptions the world options
     * @param levelStem the level stem
     * @param levelHeightAccessor the level height accessor
     * @param adapter the chunk generator adapter
     * @param ctx the worldgen context (may be null for real server)
     */
    private SampleUtils(
            @NotNull MinecraftServer server,
            BiomeSource biomeSource,
            ChunkGenerator chunkGenerator,
            WorldOptions worldOptions,
            LevelStem levelStem,
            LevelHeightAccessor levelHeightAccessor,
            caeruleusTait.world.preview.compat.ChunkGeneratorAdapter adapter,
            caeruleusTait.world.preview.backend.analysis.WorldgenContext ctx
    ) throws IOException {
        WorkManager.PREVIEW_GENERATION.set(true);
        try {
            this.cfg = WorldPreview.get().cfg();
            this.tempDir = null;
            this.ownsResourceManager = false;
            this.minecraftServer = server;
            this.dataFixer = minecraftServer.getFixerUpper();
            this.levelStorageAccess = ((MinecraftServerAccessor) minecraftServer).getStorageSource();

            this.levelHeightAccessor = levelHeightAccessor;
            this.resourceManager = (CloseableResourceManager) minecraftServer.getResourceManager();
            this.biomeSource = biomeSource;
            this.chunkGenerator = chunkGenerator;
            this.registryAccess = minecraftServer.registryAccess();
            this.structureRegistry = this.registryAccess.lookupOrThrow(Registries.STRUCTURE);
            this.structureTemplateManager = minecraftServer.getStructureManager();
            this.previewLevel = new PreviewLevel(this.registryAccess, this.levelHeightAccessor);

            ResourceKey<LevelStem> levelStemResourceKey = this.registryAccess.lookupOrThrow(LEVEL_STEM)
                    .getResourceKey(levelStem)
                    .orElseThrow();
            dimension = Registries.levelStemToLevel(levelStemResourceKey);

            if (chunkGenerator instanceof NoiseBasedChunkGenerator noiseBasedChunkGenerator) {
                randomState = RandomState.create(
                        noiseBasedChunkGenerator.generatorSettings().value(),
                        registryAccess.lookupOrThrow(Registries.NOISE),
                        worldOptions.seed()
                );
            } else {
                randomState = RandomState.create(
                        NoiseGeneratorSettings.dummy(),
                        registryAccess.lookupOrThrow(Registries.NOISE),
                        worldOptions.seed()
                );
            }

            this.structureCheck = new StructureCheck(
                    null,
                    // Should never be required because `tryLoadFromStorage` must not be called
                    this.registryAccess,
                    this.structureTemplateManager,
                    dimension,
                    this.chunkGenerator,
                    this.randomState,
                    this.levelHeightAccessor,
                    chunkGenerator.getBiomeSource(),
                    worldOptions.seed(),
                    dataFixer
            );
            this.structureManager = new StructureManager(previewLevel, worldOptions, structureCheck);
            this.chunkGeneratorStructureState = this.chunkGenerator.createState(
                    this.registryAccess.lookupOrThrow(Registries.STRUCTURE_SET),
                    this.randomState,
                    worldOptions.seed()
            );

            if (chunkGenerator instanceof NoiseBasedChunkGenerator noiseBasedChunkGenerator) {
                noiseGeneratorSettings = noiseBasedChunkGenerator.generatorSettings().value();
            } else {
                noiseGeneratorSettings = null;
            }
            serverLevel = null;
            mixinBootstrapLevel = null;

            // Initialize mod compatibility adapter
            this.chunkGeneratorAdapter = adapter;
            this.minecraftChunkGenerator = new caeruleusTait.world.preview.infra.minecraft.MinecraftChunkGeneratorImpl(
                    adapter, ctx, levelStem);
        } finally {
            WorkManager.PREVIEW_GENERATION.remove();
        }
    }

    /**
     * Create SampleUtils <b>and</b> a fake Minecraft server
     */
    public SampleUtils(
            BiomeSource biomeSource,
            ChunkGenerator chunkGenerator,
            LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess,
            WorldOptions worldOptions,
            LevelStem levelStem,
            LevelHeightAccessor levelHeightAccessor,
            WorldDataConfiguration worldDataConfiguration,
            Proxy proxy,
            @Nullable Path tempDataPackDir,
            caeruleusTait.world.preview.compat.ChunkGeneratorAdapter adapter
    ) throws IOException, RuntimeException {
        this(chunkGenerator, biomeSource, chunkGenerator, layeredRegistryAccess,
                worldOptions, levelStem, levelHeightAccessor,
                worldDataConfiguration, proxy, tempDataPackDir, adapter);
    }

    /**
     * Create SampleUtils <b>and</b> a fake Minecraft server
     *
     * @param biomeSource the biome source
     * @param chunkGenerator the chunk generator
     * @param layeredRegistryAccess the layered registry access
     * @param worldOptions the world options
     * @param levelStem the level stem
     * @param levelHeightAccessor the level height accessor
     * @param worldDataConfiguration the world data configuration
     * @param proxy the proxy
     * @param tempDataPackDir the temp data pack directory
     * @param adapter the chunk generator adapter
     */
    private SampleUtils(
            ChunkGenerator genForAdapter,
            BiomeSource biomeSource,
            ChunkGenerator chunkGenerator,
            LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess,
            WorldOptions worldOptions,
            LevelStem levelStem,
            LevelHeightAccessor levelHeightAccessor,
            WorldDataConfiguration worldDataConfiguration,
            Proxy proxy,
            @Nullable Path tempDataPackDir,
            caeruleusTait.world.preview.compat.ChunkGeneratorAdapter adapter
    ) throws IOException, RuntimeException {
        WorkManager.PREVIEW_GENERATION.set(true);
        try {
            this.cfg = WorldPreview.get().cfg();
            this.ownsResourceManager = true;
            try {
                tempDir = Files.createTempDirectory("world_preview");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            this.dataFixer = DataFixers.getDataFixer();
            LevelStorageSource levelStorageSource = new LevelStorageSource(
                    tempDir,
                    tempDir.resolve("backups"),
                    new DirectoryValidator(new PathAllowList(List.of())),
                    dataFixer
            );
            this.levelStorageAccess = levelStorageSource.createAccess("world_preview");
            this.levelHeightAccessor = levelHeightAccessor;

            Path dataPackDir = levelStorageAccess.getLevelPath(LevelResource.DATAPACK_DIR);
            FileUtil.createDirectoriesSafe(dataPackDir);
            if (tempDataPackDir != null) {
                try (Stream<Path> stream = Files.walk(tempDataPackDir)) {
                    stream.filter(x -> !x.equals(tempDataPackDir)).forEach(x -> {
                        try {
                            Util.copyBetweenDirs(tempDataPackDir, dataPackDir, x);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                }
            }

            this.biomeSource = biomeSource;
            this.chunkGenerator = chunkGenerator;
            this.registryAccess = layeredRegistryAccess.compositeAccess();
            this.structureRegistry = this.registryAccess.lookupOrThrow(Registries.STRUCTURE);
            this.previewLevel = new PreviewLevel(this.registryAccess, this.levelHeightAccessor);

            PackRepository packRepository = ServerPacksSource.createPackRepository(levelStorageAccess);
            resourceManager = (new WorldLoader.PackConfig(
                    packRepository,
                    worldDataConfiguration,
                    false,
                    false
            )).createResourceManager().getSecond();

            HolderGetter<Block> holderGetter = this.registryAccess.lookupOrThrow(Registries.BLOCK);
            this.structureTemplateManager = new StructureTemplateManager(
                    resourceManager,
                    levelStorageAccess,
                    dataFixer,
                    holderGetter
            );

            ResourceKey<LevelStem> levelStemResourceKey = this.registryAccess.lookupOrThrow(LEVEL_STEM)
                    .getResourceKey(levelStem)
                    .orElseThrow();
            dimension = Registries.levelStemToLevel(levelStemResourceKey);

            // Some mods listen on the <init> of MinecraftServer
            final int functionCompilationLevel = 0;
            final ExecutorService reloadExecutor = newOwnedSingleThreadExecutor();
            final LevelSettings levelSettings = new LevelSettings("temp", GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, false, worldDataConfiguration);
            final PrimaryLevelData primaryLevelData = new PrimaryLevelData(levelSettings, PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable());
            final WorldGenSettings worldGenSettings = WorldGenSettings.of(worldOptions, layeredRegistryAccess.compositeAccess());
            final var future = ReloadableServerResources.loadResources(resourceManager, layeredRegistryAccess, List.of(), worldDataConfiguration.enabledFeatures(), Commands.CommandSelection.DEDICATED, PermissionSet.NO_PERMISSIONS, reloadExecutor, reloadExecutor);
            final ReloadableServerResources reloadableServerResources;
            try {
                reloadableServerResources = future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            // Pre 1.20.5 version:
            // ReloadableServerResources reloadableServerResources = new ReloadableServerResources(layeredRegistryAccess.compositeAccess(), FeatureFlagSet.of(), Commands.CommandSelection.ALL, 0);
            WorldStem worldStem = new WorldStem(resourceManager, reloadableServerResources, layeredRegistryAccess, new LevelDataAndDimensions.WorldDataAndGenSettings(primaryLevelData, worldGenSettings));

            final LevelLoadListener LevelLoadListener = new LevelLoadListener() {
                @Override
                public void updateFocus(ResourceKey<Level> level, ChunkPos center) {

                }

                @Override
                public void start(Stage stage, int count) {

                }

                @Override
                public void update(Stage stage, int done, int total) {

                }

                @Override
                public void finish(Stage stage) {

                }
            };

            minecraftServer = new DummyMinecraftServer(
                    new Thread(() -> {}), // Dummy thread is required for the spark mod
                    levelStorageAccess,
                    packRepository,
                    worldStem,
                    proxy,
                    dataFixer,
                    new Services(null, null, null, null, null),
                    LevelLoadListener
            );

            // All this stuff, just so we can give Forge a fake minecraft server...
            WorldPreview.get().loaderSpecificSetup(minecraftServer);

            // Use this (or add an option) to do things "properly"
            // ((DummyMinecraftServer) minecraftServer).createLevels();

            // Now "create" a world, to trigger mixins hooking the ServerLevel constructor.
            // Keep the instance so we can close it and avoid leaking its resources.
            mixinBootstrapLevel = new ServerLevel(
                    minecraftServer,
                    newOwnedSingleThreadExecutor(),
                    levelStorageAccess,
                    new DerivedLevelData(
                            worldStem.worldDataAndGenSettings().data(),
                            worldStem.worldDataAndGenSettings().data().overworldData()
                    ),
                    dimension,
                    levelStem,
                    false, // debug
                    BiomeManager.obfuscateSeed(worldOptions.seed()),
                    List.of(),
                    false // tickTime
            );

            // Noise / Heightmap stuff -- and random state
            if (chunkGenerator instanceof NoiseBasedChunkGenerator noiseBasedChunkGenerator) {
                noiseGeneratorSettings = noiseBasedChunkGenerator.generatorSettings().value();
                randomState = RandomState.create(
                        noiseBasedChunkGenerator.generatorSettings().value(),
                        registryAccess.lookupOrThrow(Registries.NOISE),
                        worldOptions.seed()
                );
            } else {
                noiseGeneratorSettings = null;
                randomState = RandomState.create(
                        NoiseGeneratorSettings.dummy(),
                        registryAccess.lookupOrThrow(Registries.NOISE),
                        worldOptions.seed()
                );
            }

            // This needs to happen *after* creating the dummy Minecraft server
            this.structureCheck = new StructureCheck(
                    null, // Should never be required because `tryLoadFromStorage` must not be called
                    this.registryAccess,
                    this.structureTemplateManager,
                    dimension,
                    this.chunkGenerator,
                    this.randomState,
                    this.levelHeightAccessor,
                    chunkGenerator.getBiomeSource(),
                    worldOptions.seed(),
                    dataFixer
            );
            this.structureManager = new StructureManager(this.previewLevel, worldOptions, this.structureCheck);

            this.chunkGeneratorStructureState = this.chunkGenerator.createState(
                    this.registryAccess.lookupOrThrow(Registries.STRUCTURE_SET),
                    this.randomState,
                    worldOptions.seed()
            );

            // Initialize early
            chunkGeneratorStructureState.ensureStructuresGenerated();

            // Create fake , to trigger mixins for some mods...
            serverLevel = new ServerLevel(
                    minecraftServer,
                    newOwnedSingleThreadExecutor(),
                    levelStorageAccess,
                    new DummyServerLevelData(),
                    dimension,
                    levelStem,
                    true, // isDebugWorld
                    worldOptions.seed(),
                    List.of(),
                    false
                );

            // Initialize mod compatibility adapter
            this.chunkGeneratorAdapter = adapter;
            this.minecraftChunkGenerator = new caeruleusTait.world.preview.infra.minecraft.MinecraftChunkGeneratorImpl(
                    adapter, null, levelStem);
        } finally {
            WorkManager.PREVIEW_GENERATION.remove();
        }
    }

    private ExecutorService newOwnedSingleThreadExecutor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ownedExecutors.add(executor);
        return executor;
    }

    public @Nullable ServerPlayer getPlayers(UUID playerId) {
        if (minecraftServer instanceof DummyMinecraftServer) {
            return null;
        }
        return minecraftServer.getPlayerList().getPlayer(playerId);
    }

    public record BiomeResult(ResourceKey<Biome> biome, short[] noiseResult) {}

    private static short doubleToShort(double val, double factor) {
        // Short.MIN_VALUE is the empty-cell sentinel; clamp noise so real samples never collide with it.
        short encoded = (short) Math.min(Short.MAX_VALUE, Math.max(Short.MIN_VALUE, (long) (val * factor * (double) Short.MAX_VALUE)));
        return encoded == Short.MIN_VALUE ? (short) (Short.MIN_VALUE + 1) : encoded;
    }

    public boolean hasRawNoiseInfo() {
        return cfg.storeNoiseSamples && biomeSource instanceof MultiNoiseBiomeSource;
    }

    public BiomeResult doSample(BlockPos pos) {
        final Climate.Sampler sampler = randomState.sampler();
        if (hasRawNoiseInfo()) {
            final var singlePointContext = new DensityFunction.SinglePointContext(pos.getX(), pos.getY(), pos.getZ());
            final double temperature = sampler.temperature().compute(singlePointContext);
            final double humidity = sampler.humidity().compute(singlePointContext);
            final double continentalness = sampler.continentalness().compute(singlePointContext);
            final double erosion = sampler.erosion().compute(singlePointContext);
            final double depth = sampler.depth().compute(singlePointContext);
            final double weirdness = sampler.weirdness().compute(singlePointContext);

            final short[] noiseData = new short[] {
                    doubleToShort(temperature, 1),
                    doubleToShort(humidity, 1),
                    doubleToShort(continentalness, 0.5),
                    doubleToShort(erosion, 1),
                    doubleToShort(depth, 0.5),
                    doubleToShort(weirdness, 0.75),
            };

            final var targetPoint = Climate.target((float) temperature, (float) humidity, (float) continentalness, (float) erosion, (float) depth, (float) weirdness);
            final MultiNoiseBiomeSource noiseBiomeSource = (MultiNoiseBiomeSource) biomeSource;
            final Holder<Biome> biome = noiseBiomeSource.getNoiseBiome(targetPoint);
            return new BiomeResult(biome.unwrapKey().orElseThrow(), noiseData);
        } else {
            return new BiomeResult(
                    biomeSource.getNoiseBiome(
                            QuartPos.fromBlock(pos.getX()),
                            QuartPos.fromBlock(pos.getY()),
                            QuartPos.fromBlock(pos.getZ()),
                            randomState.sampler()
                    ).unwrapKey().orElseThrow(),
                    null
            );
        }
    }

    /*
    public ResourceKey<Biome> doSample(BlockPos pos) {
        return biomeSource.getNoiseBiome(
                QuartPos.fromBlock(pos.getX()),
                QuartPos.fromBlock(pos.getY()),
                QuartPos.fromBlock(pos.getZ()),
                randomState.sampler()
        ).unwrapKey().orElseThrow();
    }
     */

    public List<Pair<Identifier, StructureStart>> doStructures(ChunkPos chunkPos) {
        ProtoChunk protoChunk = (ProtoChunk) previewLevel.getChunk(chunkPos.x(), chunkPos.z(), ChunkStatus.FULL, false);
        chunkGenerator.createStructures(registryAccess, chunkGeneratorStructureState, structureManager, protoChunk, structureTemplateManager, dimension);
        Map<Structure, StructureStart> raw = protoChunk.getAllStarts();
        List<Pair<Identifier, StructureStart>> res = new ArrayList<>(raw.size());
        for (Map.Entry<Structure, StructureStart> x : raw.entrySet()) {
            res.add(new Pair<>(structureRegistry.getKey(x.getKey()), x.getValue()));
        }
        return res;
    }

    public NoiseChunk getNoiseChunk(ChunkPos startChunk, int numChunks, boolean keepAquifer) {
        NoiseSettings noiseSettings = noiseGeneratorSettings.noiseSettings();
        NoiseChunk noiseChunk = new NoiseChunk(
                (numChunks * 16) / noiseSettings.getCellWidth(),
                randomState,
                startChunk.getMinBlockX(),
                startChunk.getMinBlockZ(),
                noiseSettings,
                DensityFunctions.BeardifierMarker.INSTANCE,
                noiseGeneratorSettings,
                ((NoiseBasedChunkGeneratorAccessor) chunkGenerator).getGlobalFluidPicker().get(),
                Blender.empty()
        );
        if (!keepAquifer) {
            ((NoiseChunkAccessor) noiseChunk).setAquifer(new EmptyAquifer());
        } else {
            // MC 1.21.6+: NoiseBasedAquifer.computeSubstance() returns null for BOTH
            // air (density > 0) and stone (density ≤ 0, non-fluid).  Wrap the real
            // aquifer so that null results are converted to distinguishable block
            // states (AIR vs defaultBlock) using the substance/density parameter.
            Aquifer realAquifer = ((NoiseChunkAccessor) noiseChunk).getAquifer();
            ((NoiseChunkAccessor) noiseChunk).setAquifer(
                    new IntersectionAquifer(realAquifer, noiseGeneratorSettings.defaultBlock())
            );
        }
        return noiseChunk;
    }

    public NoiseGeneratorSettings noiseGeneratorSettings() {
        return noiseGeneratorSettings;
    }

    public short doHeightSlow(BlockPos pos) {
        return (short) chunkGenerator.getBaseHeight(
                pos.getX(),
                pos.getZ(),
                Heightmap.Types.OCEAN_FLOOR_WG,
                levelHeightAccessor,
                randomState
        );
    }

    public NoiseColumn doIntersectionsSlow(BlockPos pos) {
        return chunkGenerator.getBaseColumn(pos.getX(), pos.getZ(), levelHeightAccessor, randomState);
    }

    @Override
    public void close() throws Exception {
        Exception closeError = null;

        // Shut down executors we created (dummy-server path).
        for (ExecutorService executor : ownedExecutors) {
            executor.shutdownNow();
        }
        boolean interrupted = false;
        for (ExecutorService executor : ownedExecutors) {
            try {
                if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                    LOGGER.warn("SampleUtils owned executor did not terminate within {}s", EXECUTOR_SHUTDOWN_SECONDS);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                LOGGER.warn("Interrupted while awaiting SampleUtils executor termination");
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        ownedExecutors.clear();

        try {
            if (serverLevel != null) {
                serverLevel.close();
            }
        } catch (Exception e) {
            closeError = e;
        }

        try {
            if (mixinBootstrapLevel != null) {
                mixinBootstrapLevel.close();
            }
        } catch (Exception e) {
            if (closeError == null) {
                closeError = e;
            } else {
                closeError.addSuppressed(e);
            }
        }

        if (ownsResourceManager && resourceManager != null) {
            try {
                resourceManager.close();
            } catch (Exception e) {
                if (closeError == null) {
                    closeError = e;
                } else {
                    closeError.addSuppressed(e);
                }
            }
        }

        if (minecraftServer instanceof DummyMinecraftServer) {
            try {
                WorldPreview.get().loaderSpecificTeardown(minecraftServer);
            } catch (Exception e) {
                if (closeError == null) {
                    closeError = e;
                } else {
                    closeError.addSuppressed(e);
                }
            }
        }

        if (tempDir != null) {
            deleteDirectoryLegacyIO(tempDir.toFile());
        }

        if (closeError != null) {
            throw closeError;
        }
    }

    // Source https://mkyong.com/java/how-to-delete-directory-in-java/
    public static void deleteDirectoryLegacyIO(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            Path root = file.toPath();
            if (!Files.exists(root)) {
                return;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(path);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Unable to delete file or directory tree: {}", file, e);
        }
    }

    public CloseableResourceManager resourceManager() {
        return resourceManager;
    }

    public RegistryAccess registryAccess() {
        return registryAccess;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public caeruleusTait.world.preview.compat.ChunkGeneratorAdapter chunkGeneratorAdapter() {
        return chunkGeneratorAdapter;
    }

    public caeruleusTait.world.preview.infra.minecraft.MinecraftChunkGenerator minecraftChunkGenerator() {
        return minecraftChunkGenerator;
    }
}
