// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.client.gui.PreviewContainerDataProvider;
import caeruleusTait.world.preview.mixin.client.CreateWorldScreenAccessor;
import net.minecraft.util.Util;
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
import net.minecraft.world.flag.FeatureFlagSet;
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
import java.util.List;
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

    @Override
    public net.minecraft.client.gui.layouts.Layout getLayout() {
        // PreviewTab uses its own layout system via PreviewContainer; return an empty layout
        return new net.minecraft.client.gui.layouts.Layout() {
            @Override
            public void visitChildren(java.util.function.Consumer<net.minecraft.client.gui.layouts.LayoutElement> consumer) {
            }
            @Override
            public void removeChildren() {
            }
            @Override
            public int getX() { return 0; }
            @Override
            public int getY() { return 0; }
            @Override
            public void setX(int x) {}
            @Override
            public void setY(int y) {}
            @Override
            public int getWidth() { return 0; }
            @Override
            public int getHeight() { return 0; }
        };
    }
    private final Minecraft minecraft;

    // Cached sandbox world-creation context and its fingerprint.
    // See previewWorldCreationContext() for the caching/lifecycle contract.
    private volatile WorldCreationContext cachedSandboxContext;
    private volatile SandboxFingerprint cachedSandboxFingerprint;

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
        cachedSandboxContext = null;
        cachedSandboxFingerprint = null;
        previewContainer.close();
    }

    /**
     * Clears the preview cache files (default behavior) and additionally the
     * process-wide decoded-icon cache.
     */
    @Override
    public void clearCache() {
        PreviewContainerDataProvider.super.clearCache();
        IconCache.invalidate();
    }

    /**
     * Cache key for the sandbox {@link WorldCreationContext}: everything the
     * full {@link WorldLoader#load} result depends on besides the options
     * (seed) component. Per-element {@link List} equality is used because
     * {@link net.minecraft.world.level.DataPackConfig} does not implement
     * value equality.
     */
    private record SandboxFingerprint(List<String> enabledPacks, List<String> disabledPacks,
                                      FeatureFlagSet enabledFeatures, String presetId) {
    }

    private SandboxFingerprint currentFingerprint(WorldDataConfiguration dataConfig) {
        String presetId;
        try {
            presetId = uiState.getWorldType().preset().unwrapKey()
                    .map(key -> key.identifier().toString()).orElse("<fallback>");
        } catch (RuntimeException e) {
            presetId = "<fallback>";
        }
        return new SandboxFingerprint(
                List.copyOf(dataConfig.dataPacks().getEnabled()),     // order-sensitive: pack priority affects the registries
                List.copyOf(dataConfig.dataPacks().getDisabled()),
                dataConfig.enabledFeatures(),
                presetId);
    }

    /**
     * Create a playground for mods to do their thing while minimizing the risk
     * to the real world creation stuff.
     *
     * <p>The expensive datapack/registry reload result is cached per
     * {@link PreviewTab} instance (i.e. per CreateWorldScreen instance, held
     * by the mixin). The cache key is a {@link SandboxFingerprint} of
     * everything the loaded context actually depends on: the enabled/disabled
     * datapack lists (order-sensitive, pack priority affects the registries),
     * the enabled feature flags and the selected world preset. A fingerprint
     * mismatch (e.g. the user toggled datapacks or changed the world type)
     * automatically misses and triggers a full reload. The seed lives only in
     * the options component, so seed changes are served from the cache via
     * {@link WorldCreationContext#withOptions(WorldCreationContext.OptionsModifier)}
     * with zero reloads. The cache dies with this PreviewTab instance (nulled
     * in {@link #close()}); the InGamePreviewScreen path is unaffected (its
     * previewWorldCreationContext() is a separate null-returning implementation
     * in that class).
     */
    @Override
    public @Nullable WorldCreationContext previewWorldCreationContext() {
        WorldCreationContext uiSettings = uiState.getSettings();
        WorldDataConfiguration worldDataConfiguration = uiSettings.dataConfiguration();

        SandboxFingerprint fingerprint = currentFingerprint(worldDataConfiguration);
        WorldCreationContext cached = cachedSandboxContext;
        if (cached != null && fingerprint.equals(cachedSandboxFingerprint)) {
            try {
                return cached.withOptions(o -> uiSettings.options());
            } catch (RuntimeException e) {
                WorldPreview.LOGGER.warn("Sandbox context reuse failed, falling back to full load", e);
            }
        }

        WorldCreationContext fresh = loadSandboxContext(worldDataConfiguration);
        cachedSandboxContext = fresh;
        cachedSandboxFingerprint = fingerprint;
        return fresh;
    }

    /**
     * Full sandbox load, unchanged from the original previewWorldCreationContext()
     * body. The result only depends on the datapack configuration plus the
     * world type preset; the seed is just an options component.
     */
    private WorldCreationContext loadSandboxContext(WorldDataConfiguration worldDataConfiguration) {
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
                    WorldGenSettings worldGenSettings = new WorldGenSettings(uiState.getSettings().options(), worldDimensions);
                    return new WorldLoader.DataLoadOutput<>(
                            new Cookie(worldGenSettings),
                            dataLoadContext.datapackDimensions()
                    );
                }
                ,
                (closeableResourceManager, reloadableServerResources, layeredRegistryAccess, cookie) -> {
                    // Do NOT close closeableResourceManager here.
                    // In MC 1.21.11+/26.x, the vanilla resource pack's ZipFileSystem is shared
                    // between this temporary resource manager and the game's main
                    // resource manager. Closing it here invalidates the vanilla jar's
                    // zip channel for ALL subsequent reads, causing
                    // ClosedChannelException on the next previewWorldCreationContext()
                    // call (e.g. when the user changes seed/dimension/settings).
                    return new WorldCreationContext(cookie.worldGenSettings, layeredRegistryAccess, reloadableServerResources, worldDataConfiguration);
                },
                Util.backgroundExecutor(),
                minecraft
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
        minecraft.gui.setScreen(new PreviewCacheLoadingScreen(SAVING_PREVIEW));
        writeCacheFile(previewContainer.workManager().previewStorage(), cacheDir().resolve(filename(seed)));
        previewContainer.setCacheLoading(true);
        minecraft.gui.setScreen(createWorldScreen);
        previewContainer.setCacheLoading(false);
    }

    @Override
    public PreviewStorage loadPreviewStorage(long seed, int yMin, int yMax) {
        if (!worldPreview.cfg().cacheInNew) {
            return new PreviewStorage(yMin, yMax);
        }

        // When called from within updateSettings_real() (isUpdating == true),
        // do NOT switch screens.  The screen change triggers a nested
        // CreateWorldScreen.init() which calls doLayout() → setSize() →
        // resizeImage(), creating a new black texture that overwrites the
        // one already prepared by the outer init().  The nested init() also
        // calls onScreenReentry() → invalidateRenderCache() at an unexpected
        // time, leaving the render-skip optimisation in a state where the
        // first full render produces a black texture (empty storage) and
        // subsequent frames reuse it indefinitely — the "black screen until
        // drag" bug.  Loading the cache file without a screen change avoids
        // this entirely; the user already sees "Loading…" text from the
        // isUpdating render branch.
        if (previewContainer.isUpdating()) {
            return readCacheFile(yMin, yMax, cacheDir().resolve(filename(seed)));
        }

        previewContainer.setCacheLoading(true);
        minecraft.gui.setScreen(new PreviewCacheLoadingScreen(LOADING_PREVIEW));
        final PreviewStorage res = readCacheFile(yMin, yMax, cacheDir().resolve(filename(seed)));
        minecraft.gui.setScreen(createWorldScreen);
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
