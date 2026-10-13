package caeruleusTait.world.preview.client.gui;

import caeruleusTait.world.preview.backend.storage.PreviewStorageCacheManager;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public interface PreviewContainerDataProvider extends PreviewStorageCacheManager {

    /**
     * Structural fingerprint of the worldgen inputs this provider feeds into
     * the preview setup (datapack lists, feature flags, world preset; the
     * in-game provider's world is fixed for the session and returns null).
     * Must return equal objects exactly when a setup rebuild would produce
     * identical sampling infrastructure; returning {@code null} always
     * compares equal.
     */
    default Object worldgenStructuralFingerprint() {
        return null;
    }

    @Nullable WorldCreationContext previewWorldCreationContext();

    void registerSettingsChangeListener(Runnable listener);

    String seed();

    void updateSeed(String newSeed);

    boolean seedIsEditable();

    @Nullable Path tempDataPackDir();

    @Nullable MinecraftServer minecraftServer();

    WorldOptions worldOptions(@Nullable WorldCreationContext wcContext);

    WorldDataConfiguration worldDataConfiguration(@Nullable WorldCreationContext wcContext);

    RegistryAccess.Frozen registryAccess(@Nullable WorldCreationContext wcContext);

    Registry<LevelStem> levelStemRegistry(@Nullable WorldCreationContext wcContext);

    LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess(@Nullable WorldCreationContext wcContext);
}
