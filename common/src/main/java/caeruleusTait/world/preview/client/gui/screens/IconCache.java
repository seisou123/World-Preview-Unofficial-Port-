// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

/**
 * Process-wide cache for decoded structure icons (PNG -&gt; {@link NativeImage}).
 *
 * <p>PreviewContainer needs fresh {@link NativeImage}s every updateSettings
 * cycle because each cycle closes the icons from the previous one. Decoding is
 * expensive (I/O + PNG inflate), so the decoded "master" images are cached
 * here and every caller receives a private copy. Only the copies ever enter
 * the render/close chain; the masters are owned by the cache and intentionally
 * never closed. When a new resource pack fingerprint swaps the cache, old
 * masters are dropped without {@code close()}, trading a small, bounded amount
 * of native memory for the guarantee that no in-use image is ever freed.
 *
 * <p>The cache is automatically generation-invalidated whenever the resource
 * pack fingerprint changes (see {@link #fingerprint(ResourceManager, ResourceManager)}),
 * so adding/removing/reordering resource packs never serves stale icons. A
 * hard upper bound guards against pathological growth.
 */
public final class IconCache {
    private IconCache() {}

    /**
     * Resource pack fingerprint: hash of the sorted {@link PackResources} id
     * sets of both managers; pack add/remove/reorder auto-invalidates.
     */
    public static String fingerprint(ResourceManager builtin, @Nullable ResourceManager sample) {
        String a = builtin.listPacks().map(PackResources::packId).sorted().reduce("", (x, y) -> x + ";" + y);
        String b = sample == null ? "" : sample.listPacks().map(PackResources::packId).sorted().reduce("", (x, y) -> x + ";" + y);
        return Integer.toHexString(a.hashCode()) + ":" + Integer.toHexString(b.hashCode());
    }

    private record Key(String fp, ResourceLocation id) {}

    /** Master icons, owned by the cache, never closed (only caller-owned copies are closed). */
    private static final ConcurrentHashMap<Key, NativeImage> MASTERS = new ConcurrentHashMap<>();
    private static volatile String currentFingerprint = "";

    public static void invalidate() {
        currentFingerprint = "";
        MASTERS.clear();
    }

    /**
     * Returns a fresh private copy of the icon {@code iconId}, decoding it once
     * and caching the master. The copy is owned by the caller and must be
     * closed by the caller; the cached master must not be closed.
     */
    public static NativeImage getOrLoadCopy(ResourceLocation iconId, String fp,
                                            ResourceManager builtin, @Nullable ResourceManager sample) {
        if (!fp.equals(currentFingerprint)) {
            synchronized (IconCache.class) {
                if (!fp.equals(currentFingerprint)) {
                    MASTERS.clear();
                    currentFingerprint = fp;
                }
            }
        }
        if (MASTERS.size() > 4096) {
            MASTERS.clear();   // defensive upper bound
        }
        NativeImage master = MASTERS.computeIfAbsent(new Key(fp, iconId),
                key -> loadSingleIcon(key.id(), builtin, sample));
        NativeImage copy = new NativeImage(master.getWidth(), master.getHeight(), true);
        copy.copyFrom(master);
        return copy;
    }

    /**
     * Loads a single structure icon from the builtin or sample resource manager.
     * Thread-safe: creates a new NativeImage and does not modify shared state.
     */
    private static NativeImage loadSingleIcon(ResourceLocation iconId,
                                               ResourceManager builtinResourceManager,
                                               ResourceManager sampleResourceManager) {
        Optional<Resource> resource = builtinResourceManager.getResource(iconId);
        if (resource.isEmpty()) {
            resource = sampleResourceManager.getResource(iconId);
        }
        if (resource.isEmpty()) {
            LOGGER.error("Failed to load structure icon: '{}'", iconId);
            resource = builtinResourceManager.getResource(
                    new ResourceLocation("world_preview:textures/structure/unknown.png"));
        }
        if (resource.isEmpty()) {
            LOGGER.error("FATAL ERROR LOADING: '{}' -- unable to load fallback!", iconId);
            return new NativeImage(16, 16, true);
        }
        try {
            try (InputStream in = resource.get().open()) {
                return NativeImage.read(in);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to decode structure icon '{}'; using a blank fallback", iconId, e);
            return new NativeImage(16, 16, true);
        }
    }
}
