package caeruleusTait.world.preview.backend.color;

import it.unimi.dsi.fastutil.objects.Object2ShortMap;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

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
