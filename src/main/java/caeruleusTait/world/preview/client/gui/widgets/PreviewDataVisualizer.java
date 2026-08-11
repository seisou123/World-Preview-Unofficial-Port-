package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.client.WorldPreviewClient;
import caeruleusTait.world.preview.client.gui.PreviewDisplayDataProvider;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Owns minimap and statistics overlays for {@link PreviewDisplay}.
 */
final class PreviewDataVisualizer {
    private final Minecraft minecraft;
    private final PreviewDisplayDataProvider dataProvider;
    private final WorkManager workManager;

    private NativeImage minimapImg;
    private DynamicTexture minimapTexture;
    private int[] colorMap;
    private int texWidth;
    private int texHeight;
    private double scaleBlockPos;

    private long minimapCacheWriteCounter = -1;
    private int minimapCacheY = Integer.MIN_VALUE;
    private int[] minimapCacheBounds;
    private boolean minimapNeedsRegenerate = true;

    PreviewDataVisualizer(Minecraft minecraft, PreviewDisplayDataProvider dataProvider, WorkManager workManager) {
        this.minecraft = minecraft;
        this.dataProvider = dataProvider;
        this.workManager = workManager;
    }

    void updateRenderContext(
            NativeImage minimapImg,
            DynamicTexture minimapTexture,
            int[] colorMap,
            int texWidth,
            int texHeight,
            double scaleBlockPos
    ) {
        this.minimapImg = minimapImg;
        this.minimapTexture = minimapTexture;
        this.colorMap = colorMap;
        this.texWidth = texWidth;
        this.texHeight = texHeight;
        this.scaleBlockPos = scaleBlockPos;
        invalidateCache();
    }

    void invalidateCache() {
        minimapCacheWriteCounter = -1;
        minimapCacheBounds = null;
        minimapNeedsRegenerate = true;
    }

    void renderMinimap(GuiGraphicsExtractor guiGraphicsExtractor, int xMin, int yMin, int xMax, int yMax, BlockPos centerPos) {
        if (dataProvider.isUpdating()
                || workManager.previewStorage() == null
                || minimapImg == null
                || minimapTexture == null
                || colorMap == null) {
            return;
        }

        final PreviewStorage storage = workManager.previewStorage();
        final long currentWriteCounter = storage.writeCounter();

        final int currentY = centerPos.getY();
        if (minimapCacheWriteCounter != currentWriteCounter
                || minimapCacheY != currentY
                || minimapCacheBounds == null) {
            minimapCacheBounds = storage.sampledBounds(currentY);
            minimapCacheWriteCounter = currentWriteCounter;
            minimapCacheY = currentY;
            minimapNeedsRegenerate = true;
        }

        final int[] bounds = minimapCacheBounds;
        if (bounds == null) return;

        final int sampledMinX = bounds[0], sampledMinZ = bounds[1];
        final int sampledMaxX = bounds[2], sampledMaxZ = bounds[3];
        final int sampledW = sampledMaxX - sampledMinX;
        final int sampledH = sampledMaxZ - sampledMinZ;
        if (sampledW <= 0 || sampledH <= 0) return;

        final int miniMaxSize = 80;
        final float aspect = (float) sampledW / sampledH;
        int miniW, miniH;
        if (aspect >= 1f) {
            miniW = miniMaxSize;
            miniH = Math.max(1, (int) (miniMaxSize / aspect));
        } else {
            miniH = miniMaxSize;
            miniW = Math.max(1, (int) (miniMaxSize * aspect));
        }

        final int pad = 4;
        final int miniX = xMax - miniW - pad - 2;
        final int miniY = yMin + pad + 2;

        if (minimapNeedsRegenerate) {
            minimapImg.fillRect(0, 0, 80, 80, 0xFF151522);
            storage.fillMinimapImage(currentY, sampledMinX, sampledMinZ, sampledW, sampledH,
                    colorMap, minimapImg, miniW, miniH);
            minimapTexture.upload();
            minimapNeedsRegenerate = false;
        }

        guiGraphicsExtractor.fill(miniX - 2, miniY - 2, miniX + miniW + 2, miniY + miniH + 2, 0xDD0A0A12);
        guiGraphicsExtractor.fill(miniX - 1, miniY - 1, miniX + miniW + 1, miniY + miniH + 1, 0xFF1a1a2e);
        WorldPreviewClient.renderTexture(guiGraphicsExtractor, minimapTexture, miniX, miniY, miniX + miniW, miniY + miniH);
        guiGraphicsExtractor.fill(miniX, miniY, miniX + miniW, miniY + 1, 0x33FFFFFF);

        final int vpMinX = centerPos.getX() - (int)(texWidth * scaleBlockPos / 2.0);
        final int vpMaxX = centerPos.getX() + (int)(texWidth * scaleBlockPos / 2.0);
        final int vpMinZ = centerPos.getZ() - (int)(texHeight * scaleBlockPos / 2.0);
        final int vpMaxZ = centerPos.getZ() + (int)(texHeight * scaleBlockPos / 2.0);

        final int vpX = miniX + (int)((float)(Math.max(vpMinX, sampledMinX) - sampledMinX) / sampledW * miniW);
        final int vpX2 = miniX + (int)((float)(Math.min(vpMaxX, sampledMaxX) - sampledMinX) / sampledW * miniW);
        final int vpY = miniY + (int)((float)(Math.max(vpMinZ, sampledMinZ) - sampledMinZ) / sampledH * miniH);
        final int vpY2 = miniY + (int)((float)(Math.min(vpMaxZ, sampledMaxZ) - sampledMinZ) / sampledH * miniH);

        final int bvX = Math.max(miniX, Math.min(vpX, miniX + miniW));
        final int bvX2 = Math.max(miniX, Math.min(vpX2, miniX + miniW));
        final int bvY = Math.max(miniY, Math.min(vpY, miniY + miniH));
        final int bvY2 = Math.max(miniY, Math.min(vpY2, miniY + miniH));

        final int vpColor = 0xFF00DDFF;
        if (bvX2 > bvX && bvY2 > bvY) {
            guiGraphicsExtractor.fill(bvX, bvY, bvX2, bvY + 1, vpColor);
            guiGraphicsExtractor.fill(bvX, bvY2 - 1, bvX2, bvY2, vpColor);
            guiGraphicsExtractor.fill(bvX, bvY, bvX + 1, bvY2, vpColor);
            guiGraphicsExtractor.fill(bvX2 - 1, bvY, bvX2, bvY2, vpColor);
        }

        final int crossX = Math.max(miniX, Math.min(miniX + miniW,
                miniX + (int)((float)(centerPos.getX() - sampledMinX) / sampledW * miniW)));
        final int crossY = Math.max(miniY, Math.min(miniY + miniH,
                miniY + (int)((float)(centerPos.getZ() - sampledMinZ) / sampledH * miniH)));
        final int crossColor = 0xFFFFFFFF;
        guiGraphicsExtractor.fill(crossX - 2, crossY, crossX + 3, crossY + 1, crossColor);
        guiGraphicsExtractor.fill(crossX, crossY - 2, crossX + 1, crossY + 3, crossColor);

        final String label = Component.translatable("world_preview.preview.minimap").getString();
        final int labelW = minecraft.font.width(label);
        final int labelX = miniX + (miniW - labelW) / 2;
        final int labelY = miniY - minecraft.font.lineHeight - 1;
        if (labelY >= yMin) {
            guiGraphicsExtractor.fill(labelX - 2, labelY - 1, labelX + labelW + 2, labelY + minecraft.font.lineHeight, 0x80000000);
            guiGraphicsExtractor.text(minecraft.font, label, labelX, labelY, 0xBBFFFFFF);
        }
    }

    void renderStatistics(GuiGraphicsExtractor guiGraphicsExtractor, int xMin, int yMin, int xMax, int yMax,
                          Short2LongMap visibleBiomes, Short2LongMap visibleStructures) {
        if (dataProvider.isUpdating() || !workManager.isSetup()) {
            return;
        }

        final int threads = workManager.threadCount();
        final int biomeCount = visibleBiomes.size();
        final int structCount = visibleStructures.size();

        final String stats = Component.translatable(
                "world_preview.preview.statistics",
                biomeCount, structCount, threads
        ).getString();

        int textW = minecraft.font.width(stats);
        int startY = yMax - minecraft.font.lineHeight - 5;
        int startX = xMax - textW - 5;
        guiGraphicsExtractor.fill(startX - 3, startY - 1, xMax - 2, startY + minecraft.font.lineHeight, 0x80000000);
        guiGraphicsExtractor.text(minecraft.font, stats, startX, startY, 0xFFFFFFFF);
    }
}
