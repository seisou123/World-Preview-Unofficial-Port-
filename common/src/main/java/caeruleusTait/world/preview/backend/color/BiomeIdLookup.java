package caeruleusTait.world.preview.backend.color;

import it.unimi.dsi.fastutil.objects.Object2ShortMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Shared biome ID resolution for live preview workers and analysis.
 *
 * <p>IDs come from {@link PreviewData#biome2Id()}, never from hashCode.
 */
public final class BiomeIdLookup {
    private BiomeIdLookup() {
    }

    public static short idFrom(Object2ShortMap<String> biome2Id, String biomeId) {
        Objects.requireNonNull(biome2Id, "biome2Id");
        Objects.requireNonNull(biomeId, "biomeId");
        return biome2Id.getShort(biomeId);
    }

    public static short idFrom(Object2ShortMap<String> biome2Id, Identifier location) {
        Objects.requireNonNull(location, "location");
        return idFrom(biome2Id, location.toString());
    }

    public static short idFrom(Object2ShortMap<String> biome2Id, ResourceKey<Biome> resourceKey) {
        Objects.requireNonNull(resourceKey, "resourceKey");
        return idFrom(biome2Id, resourceKey.identifier());
    }

    /** Identity-cache hit returns directly; miss falls back to the string path and backfills. cache may be null. */
    public static short idFrom(PreviewData previewData, Holder<Biome> holder,
                               @Nullable Map<Holder<Biome>, Short> cache) {
        Objects.requireNonNull(previewData, "previewData");
        if (holder == null) return -1;
        if (cache != null) {
            Short cached = cache.get(holder);
            if (cached != null) return cached;
        }
        short id = holder.unwrapKey()
                .map(key -> previewData.biome2Id().getShort(key.identifier().toString()))
                .orElse((short) -1);
        if (cache != null) cache.putIfAbsent(holder, id);
        return id;
    }

    public static short idFrom(PreviewData previewData, Holder<Biome> holder) {
        return idFrom(previewData, holder, null);
    }

    public static short idFrom(PreviewData previewData, String biomeId) {
        Objects.requireNonNull(previewData, "previewData");
        return idFrom(previewData.biome2Id(), biomeId);
    }

    public static short idFrom(PreviewData previewData, Identifier location) {
        Objects.requireNonNull(previewData, "previewData");
        return idFrom(previewData.biome2Id(), location);
    }

    public static short idFrom(PreviewData previewData, ResourceKey<Biome> resourceKey) {
        Objects.requireNonNull(previewData, "previewData");
        return idFrom(previewData.biome2Id(), resourceKey);
    }
}
