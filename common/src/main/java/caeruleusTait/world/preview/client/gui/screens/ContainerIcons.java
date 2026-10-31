// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.backend.color.PreviewData;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

/**
 * The icon set owned by one {@link PreviewContainer} generation: one
 * {@link NativeImage} per structure id plus the player and spawn icons.
 * Every updateSettings cycle closes the previous set (the copies handed
 * out by {@link IconCache} are caller-owned) and loads a fresh one; the
 * container exposes the set through its {@code structureIcons()} /
 * {@code playerIcon()} / {@code spawnIcon()} accessors.
 */
final class ContainerIcons {
    private NativeImage[] structureIcons;
    private NativeImage playerIcon;
    private NativeImage spawnIcon;

    NativeImage[] structureIcons() {
        return structureIcons;
    }

    NativeImage playerIcon() {
        return playerIcon;
    }

    NativeImage spawnIcon() {
        return spawnIcon;
    }

    void loadAll(ResourceManager builtinResourceManager, ResourceManager sampleResourceManager, PreviewData previewData) {
        freeStructureIcons();
        // Fingerprint of the current resource packs; a change (pack
        // added/removed/reordered) automatically generations the IconCache.
        final String fp = IconCache.fingerprint(builtinResourceManager, sampleResourceManager);
        structureIcons = new NativeImage[previewData.structId2StructData().length];

        // Collect unique icon identifiers to avoid loading the same icon twice.
        // Use a LinkedHashMap to preserve insertion order while deduplicating.
        final java.util.LinkedHashMap<ResourceLocation, Integer> uniqueIconIds = new java.util.LinkedHashMap<>();
        for (int i = 0; i < previewData.structId2StructData().length; ++i) {
            PreviewData.StructureData data = previewData.structId2StructData()[i];
            ResourceLocation iconId = data.icon();
            if (iconId == null) {
                iconId = ResourceLocation.parse("world_preview:textures/structure/unknown.png");
            }
            uniqueIconIds.putIfAbsent(iconId, i);
        }

        // Load all unique icons in parallel using virtual threads (Java 21+).
        // Icon loading is I/O-bound (file reads + PNG decoding), so virtual
        // threads provide significant speedup with minimal overhead.
        // The ConcurrentHashMap ensures thread-safe results aggregation.
        final java.util.concurrent.ConcurrentHashMap<ResourceLocation, NativeImage> loadedIcons =
                new java.util.concurrent.ConcurrentHashMap<>();
        final int iconCount = uniqueIconIds.size();
        if (iconCount > 0) {
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                final java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>(iconCount);
                for (ResourceLocation iconId : uniqueIconIds.keySet()) {
                    futures.add(executor.submit(() -> {
                        NativeImage img = IconCache.getOrLoadCopy(iconId, fp, builtinResourceManager, sampleResourceManager);
                        loadedIcons.put(iconId, img);
                    }));
                }
                // Wait for all icons to load
                for (var f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        LOGGER.warn("Failed to load icon in parallel", e);
                    }
                }
            }
        }

        // Assign loaded icons to the array (deduplication: same icon ID -> same NativeImage)
        for (int i = 0; i < previewData.structId2StructData().length; ++i) {
            PreviewData.StructureData data = previewData.structId2StructData()[i];
            ResourceLocation iconId = data.icon();
            if (iconId == null) {
                iconId = ResourceLocation.parse("world_preview:textures/structure/unknown.png");
            }
            structureIcons[i] = loadedIcons.get(iconId);
        }

        // Load player and spawn icons in parallel as well
        freePlayerAndSpawnIcons();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var playerFuture = executor.submit(() -> {
                try {
                    final Optional<Resource> playerResource = builtinResourceManager.getResource(
                            ResourceLocation.parse("world_preview:textures/etc/player.png"));
                    try (InputStream in = playerResource.orElseThrow().open()) {
                        return NativeImage.read(in);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to load the player icon; using a blank fallback", e);
                    return new NativeImage(16, 16, true);
                }
            });
            var spawnFuture = executor.submit(() -> {
                try {
                    final Optional<Resource> spawnResource = builtinResourceManager.getResource(
                            ResourceLocation.parse("world_preview:textures/etc/bed.png"));
                    try (InputStream in = spawnResource.orElseThrow().open()) {
                        return NativeImage.read(in);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to load the spawn icon; using a blank fallback", e);
                    return new NativeImage(16, 16, true);
                }
            });
            try {
                playerIcon = playerFuture.get();
                spawnIcon = spawnFuture.get();
            } catch (Exception e) {
                playerIcon = new NativeImage(16, 16, true);
                spawnIcon = new NativeImage(16, 16, true);
                LOGGER.error("Failed to load the player and spawn icons; using blank fallbacks", e);
            }
        }
    }

    void freeStructureIcons() {
        if (structureIcons == null) {
            return;
        }
        Arrays.stream(structureIcons).filter(Objects::nonNull).forEach(NativeImage::close);
        structureIcons = null;
    }

    void freePlayerAndSpawnIcons() {
        if (playerIcon != null) {
            playerIcon.close();
            playerIcon = null;
        }
        if (spawnIcon != null) {
            spawnIcon.close();
            spawnIcon = null;
        }
    }
}
