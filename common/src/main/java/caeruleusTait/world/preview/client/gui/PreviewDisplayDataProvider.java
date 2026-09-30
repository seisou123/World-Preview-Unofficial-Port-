package caeruleusTait.world.preview.client.gui;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import caeruleusTait.world.preview.client.gui.widgets.lists.StructuresList;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface PreviewDisplayDataProvider {
    PreviewData previewData();

    BiomesList.BiomeEntry biome4Id(int id);

    StructuresList.StructureEntry structure4Id(int id);

    NativeImage[] structureIcons();

    NativeImage playerIcon();

    NativeImage spawnIcon();

    ItemStack[] structureItems();

    void onBiomeVisuallySelected(BiomesList.BiomeEntry entry);

    void onVisibleBiomesChanged(Short2LongMap visibleBiomes);

    void onVisibleStructuresChanged(Short2LongMap visibleStructures);

    StructureRenderInfo[] renderStructureMap();

    int[] heightColorMap();

    int[] noiseColorMap();

    /**
     * Returns the baked color table for a specific noise render mode.
     * Each noise type (temperature, humidity, etc.) gets its own
     * dedicated gradient for better visual differentiation.
     *
     * @param mode one of the {@code NOISE_*} render modes
     * @return 1024-entry ARGB color table
     */
    int[] noiseColorMapFor(RenderSettings.RenderMode mode);

    int yMin();

    int yMax();

    boolean isUpdating();

    boolean setupFailed();

    @NotNull PlayerData getPlayerData(UUID playerId);

    interface StructureRenderInfo {
        boolean show();
    }

    record PlayerData(@Nullable BlockPos currentPos, @Nullable BlockPos spawnPos) {}
}
