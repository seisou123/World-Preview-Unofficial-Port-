// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.client.gui.PreviewContainerDataProvider;
import caeruleusTait.world.preview.mixin.client.CreateWorldScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.commands.Commands;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.jetbrains.annotations.NotNull;

import org.jetbrains.annotations.Nullable;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.LOADING_PREVIEW;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.SAVING_PREVIEW;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.TITLE;

public class PreviewTab implements Tab, AutoCloseable, PreviewContainerDataProvider {

    private final CreateWorldScreen createWorldScreen;
    private final WorldCreationUiState uiState;
    private final PreviewContainer previewContainer;
    private final WorldPreview worldPreview = WorldPreview.get();

    private final Minecraft minecraft;

    private final ExecutorService loadingExecutor = Executors.newFixedThreadPool(2);

    public PreviewTab(CreateWorldScreen screen, Minecraft _minecraft) {
        createWorldScreen = screen;
        uiState = screen.getUiState();
        minecraft = _minecraft;
        previewContainer = new PreviewContainer(screen, this);
    }

    @Override
    public @NotNull Component getTabTitle() {
        return TITLE;
    }

    @Override
    public void visitChildren(Consumer<AbstractWidget> consumer) {
        previewContainer.widgets().forEach(consumer);
    }

    @Override
    public void doLayout(ScreenRectangle screenRectangle) {
        // When the layout is redone (e.g. after returning from a sub-screen),
        // invalidate the render cache so the preview re-renders properly.
        previewContainer.onScreenReentry();
        previewContainer.doLayout(screenRectangle);
    }

    @Override
    public void close() {
        previewContainer.close();
        if (loadingExecutor != null) {
            loadingExecutor.shutdownNow();
            boolean interrupted = false;
            try {
                if (!loadingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    WorldPreview.LOGGER.warn("loadingExecutor did not terminate within 5s");
                }
            } catch (InterruptedException e) {
                interrupted = true;
                WorldPreview.LOGGER.warn("Interrupted while awaiting loadingExecutor termination");
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }


    /**
     * Create a playground for mods to do their thing while minimizing the risk
     * to the real world creation stuff.
     */
    @Override
    public @Nullable WorldCreationContext previewWorldCreationContext() {
        WorldCreationContext wcContext = uiState.getSettings();
        WorldDataConfiguration worldDataConfiguration = wcContext.dataConfiguration();

        record Cookie(WorldGenSettings worldGenSettings) {}

        PackRepository packRepository = ((CreateWorldScreenAccessor) createWorldScreen).invokeGetDataPackSelectionSettings(worldDataConfiguration).getSecond();
        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packRepository, worldDataConfiguration, false, true);
        WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.INTEGRATED, PermissionSet.NO_PERMISSIONS);
        CompletableFuture<WorldCreationContext> completableFuture = WorldLoader.load(
                initConfig,
                dataLoadContext -> {
                    WorldDimensions worldDimensions;
                    try {
                        // If a WorldPreset is available, use it to generate the dimensions
                        ResourceKey<WorldPreset> worldPresetKey = uiState.getWorldType().preset().unwrapKey().orElseThrow();
                        Holder<WorldPreset> holder = dataLoadContext.datapackWorldgen().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(worldPresetKey);
                        WorldPreset worldPreset = holder.value();
                        worldDimensions = worldPreset.createWorldDimensions();
                    } catch(NullPointerException | NoSuchElementException | IllegalStateException ex) {
                        // Otherwise, create the dimensions using the world data (necessary if re-creating a world)
                        worldDimensions = WorldPresets.createNormalWorldDimensions(dataLoadContext.datapackWorldgen());
                    }
                    WorldGenSettings worldGenSettings = new WorldGenSettings(wcContext.options(), worldDimensions);
                    return new WorldLoader.DataLoadOutput<>(
                            new Cookie(worldGenSettings),
                            dataLoadContext.datapackDimensions()
                    );
                }
                ,
                (closeableResourceManager, reloadableServerResources, layeredRegistryAccess, cookie) -> {
                    // Do NOT close closeableResourceManager here.
                    // In MC 1.21.11, the vanilla resource pack's ZipFileSystem is shared
                    // between this temporary resource manager and the game's main
                    // resource manager. Closing it here invalidates the vanilla jar's
                    // zip channel for ALL subsequent reads, causing
                    // ClosedChannelException on the next previewWorldCreationContext()
                    // call (e.g. when the user changes seed/dimension/settings).
                    return new WorldCreationContext(cookie.worldGenSettings, layeredRegistryAccess, reloadableServerResources, worldDataConfiguration);
                },
                loadingExecutor,
                loadingExecutor
        );

        try {
            return completableFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Path cacheDir() {
        final Path previewDir = worldPreview.configDir().resolve("world-preview");
        previewDir.toFile().mkdirs();
        return previewDir;
    }

    @Override
    public Component getTabExtraNarration() {
        return Component.empty();
    }

    private String filename(long seed) {
        return String.format("%s-%s.zip", seed, cacheFileCompatPart());
    }

    @Override
    public void storePreviewStorage(long seed, PreviewStorage storage) {
        if (!worldPreview.cfg().cacheInNew) {
            return;
        }
        minecraft.setScreen(new PreviewCacheLoadingScreen(SAVING_PREVIEW));
        writeCacheFile(previewContainer.workManager().previewStorage(), cacheDir().resolve(filename(seed)));
        previewContainer.setCacheLoading(true);
        minecraft.setScreen(createWorldScreen);
        previewContainer.setCacheLoading(false);
    }

    @Override
    public PreviewStorage loadPreviewStorage(long seed, int yMin, int yMax) {
        if (!worldPreview.cfg().cacheInNew) {
            return new PreviewStorage(yMin, yMax);
        }

        // When called from within updateSettings_real() (isUpdating == true),
        // do NOT switch screens.  The screen change triggers a nested
        // CreateWorldScreen.init() which corrupts render state and causes
        // the "black screen until drag" bug.
        if (previewContainer.isUpdating()) {
            return readCacheFile(yMin, yMax, cacheDir().resolve(filename(seed)));
        }

        previewContainer.setCacheLoading(true);
        minecraft.setScreen(new PreviewCacheLoadingScreen(LOADING_PREVIEW));
        final PreviewStorage res = readCacheFile(yMin, yMax, cacheDir().resolve(filename(seed)));
        minecraft.setScreen(createWorldScreen);
        previewContainer.setCacheLoading(false);
        return res;
    }

    public void openAnalysisScreen() {
        previewContainer.openAnalysisScreen();
    }

    public PreviewContainer mainScreenWidget() {
        return previewContainer;
    }

    @Override
    public void registerSettingsChangeListener(Runnable listener) {
        uiState.addListener(x -> listener.run());
    }

    @Override
    public String seed() {
        return uiState.getSeed();
    }

    @Override
    public void updateSeed(String newSeed) {
        uiState.setSeed(newSeed);
    }

    @Override
    public boolean seedIsEditable() {
        return true;
    }

    @Override
    public @Nullable Path tempDataPackDir() {
        return ((CreateWorldScreenAccessor) createWorldScreen).invokeGetOrCreateTempDataPackDir();
    }

    @Override
    public @Nullable MinecraftServer minecraftServer() {
        return null;
    }

    @Override
    public WorldOptions worldOptions(@Nullable WorldCreationContext wcContext) {
        if (wcContext == null) throw new AssertionError();
        return wcContext.options();
    }

    @Override
    public WorldDataConfiguration worldDataConfiguration(@Nullable WorldCreationContext wcContext) {
        if (wcContext == null) throw new AssertionError();
        return wcContext.dataConfiguration();
    }

    @Override
    public RegistryAccess.Frozen registryAccess(@Nullable WorldCreationContext wcContext) {
        if (wcContext == null) throw new AssertionError();
        return wcContext.worldgenLoadContext();
    }

    @Override
    public Registry<LevelStem> levelStemRegistry(@Nullable WorldCreationContext wcContext) {
        if (wcContext == null) throw new AssertionError();
        WorldDimensions.Complete worldDimensions = wcContext.selectedDimensions().bake(wcContext.datapackDimensions());
        return worldDimensions.dimensions();
    }

    @Override
    public LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess(@Nullable WorldCreationContext wcContext) {
        if (wcContext == null) throw new AssertionError();
        WorldDimensions.Complete worldDimensions = wcContext.selectedDimensions().bake(wcContext.datapackDimensions());
        return wcContext
                .worldgenRegistries()
                .replaceFrom(RegistryLayer.DIMENSIONS, worldDimensions.dimensionsRegistryAccess());
    }
}
