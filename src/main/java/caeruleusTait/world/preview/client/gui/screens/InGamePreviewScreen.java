// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.PreviewContainerDataProvider;
import caeruleusTait.world.preview.mixin.MinecraftServerAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.security.InvalidParameterException;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.LOADING_PREVIEW;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.SAVING_PREVIEW;

public class InGamePreviewScreen extends Screen implements PreviewContainerDataProvider {

    private static final Identifier FOOTER_SEPARATOR =
            Identifier.parse("textures/gui/footer_separator.png");

    private IntegratedServer integratedServer;
    private PreviewContainer previewContainer;
    private final WorldPreview worldPreview = WorldPreview.get();

    public InGamePreviewScreen() {
        super(WorldPreviewComponents.TITLE_FULL);
    }

    @Override
    protected void init() {
        clearWidgets();
        if (integratedServer == null) {
            integratedServer = minecraft.getSingleplayerServer();
            if (integratedServer == null) {
                throw new InvalidParameterException("No integrated server!");
            }
        }

        if (previewContainer == null) {
            previewContainer = new PreviewContainer(this, this);
            previewContainer.start();
        } else {
            // Returning from a sub-screen (analysis, settings).
            // Invalidate the render cache so the preview re-renders immediately
            // instead of appearing transparent until clicked.
            previewContainer.onScreenReentry();
        }

        previewContainer.widgets().forEach(this::addRenderableWidget);
        previewContainer.doLayout(new ScreenRectangle(0, 18, width, height - 38));

        Button btn = Button
                .builder(CommonComponents.GUI_BACK, x -> onClose())
                .width(100)
                .pos(width / 2 - 50, height - 24)
                .build();
        addRenderableWidget(btn);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphicsExtractor, mouseX, mouseY, partialTick);

        guiGraphicsExtractor.centeredText(minecraft.font, WorldPreviewComponents.TITLE_FULL, width / 2, 6, 0xFFFFFFFF);
        guiGraphicsExtractor.blit(FOOTER_SEPARATOR, 0, (int)Mth.roundToward(this.height - 30, 2), this.width, 2, 0.0F, 0.0F, 1.0F, 1.0F);
    }

    @Override
    public void onClose() {
        worldPreview.saveConfig();
        previewContainer.close();
        super.onClose();
    }

    public void openAnalysisScreen() {
        if (previewContainer != null) {
            previewContainer.openAnalysisScreen();
        }
    }

    @Override
    public Path cacheDir() {
        final var access = ((MinecraftServerAccessor) integratedServer).getStorageSource();
        final Path previewDir = access.getLevelPath(LevelResource.ROOT).resolve("world-preview");
        previewDir.toFile().mkdirs();
        return previewDir;
    }

    private String filename(long seed) {
        return String.format("%s-%s.zip", seed, cacheFileCompatPart());
    }

    @Override
    public void storePreviewStorage(long seed, PreviewStorage storage) {
        if (!worldPreview.cfg().cacheInGame) {
            return;
        }
        previewContainer.setCacheLoading(true);
        Screen previous = minecraft.gui.screen();
        minecraft.gui.setScreen(new PreviewCacheLoadingScreen(SAVING_PREVIEW));
        try {
            writeCacheFile(previewContainer.workManager().previewStorage(), cacheDir().resolve(filename(seed)));
        } finally {
            minecraft.gui.setScreen(previous != null ? previous : this);
            previewContainer.setCacheLoading(false);
        }
    }

    @Override
    public PreviewStorage loadPreviewStorage(long seed, int yMin, int yMax) {
        if (!worldPreview.cfg().cacheInGame) {
            return new PreviewStorage(yMin, yMax);
        }

        previewContainer.setCacheLoading(true);
        Screen previous = minecraft.gui.screen();
        minecraft.gui.setScreen(new PreviewCacheLoadingScreen(LOADING_PREVIEW));
        try {
            return readCacheFile(yMin, yMax, cacheDir().resolve(filename(seed)));
        } finally {
            minecraft.gui.setScreen(previous != null ? previous : this);
            previewContainer.setCacheLoading(false);
        }
    }

    /**
     * Nothing to do, since we already have an integrated server
     */
    @Override
    public @Nullable WorldCreationContext previewWorldCreationContext() {
        return null;
    }

    @Override
    public void registerSettingsChangeListener(Runnable listener) {
        // Nothing to do
    }

    @Override
    public String seed() {
        return String.valueOf(integratedServer.overworld().getSeed());
    }

    @Override
    public void updateSeed(String newSeed) {
        // Do nothing
    }

    @Override
    public boolean seedIsEditable() {
        return false;
    }

    @Override
    public @Nullable Path tempDataPackDir() {
        return null;
    }

    @Override
    public @Nullable MinecraftServer minecraftServer() {
        return integratedServer;
    }

    @Override
    public WorldOptions worldOptions(@Nullable WorldCreationContext wcContext) {
        return integratedServer.getWorldGenSettings().options();
    }

    @Override
    public WorldDataConfiguration worldDataConfiguration(@Nullable WorldCreationContext wcContext) {
        return integratedServer.getWorldData().getDataConfiguration();
    }

    @Override
    public RegistryAccess.Frozen registryAccess(@Nullable WorldCreationContext wcContext) {
        return integratedServer.registryAccess();
    }

    @Override
    public Registry<LevelStem> levelStemRegistry(@Nullable WorldCreationContext wcContext) {
        return integratedServer.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
    }

    @Override
    public LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess(@Nullable WorldCreationContext wcContext) {
        return integratedServer.registries();
    }
}
