// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.color;

import caeruleusTait.world.preview.WorldPreview;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Map;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public class ColormapReloadListener extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = (new GsonBuilder()).create();

    public ColormapReloadListener() {
        super(JsonElementCodec.CODEC, FileToIdConverter.json("colormap_preview"));
    }

    @Override
    protected void apply(Object object, ResourceManager resourceManager, ProfilerFiller profiler) {
        @SuppressWarnings("unchecked")
        Map<Identifier, JsonElement> map = (Map<Identifier, JsonElement>) object;
        final WorldPreview worldPreview = WorldPreview.get();
        final PreviewMappingData previewMappingData = worldPreview.biomeColorMap();
        previewMappingData.clearColorMappings();

        LOGGER.debug("Loading colormaps:");
        for (Map.Entry<Identifier, JsonElement> entry : map.entrySet()) {
            tryAddColormap(previewMappingData, entry.getKey(), entry.getValue(), GSON);
        }
    }

    /**
     * Deserializes one colormap entry and adds it to {@code target}.  A malformed
     * entry (unreadable JSON shape, missing/null {@code data}, fewer than 2 rows,
     * a row without exactly 3 elements, out-of-range components) is skipped with
     * a warning instead of aborting the whole colormap reload, so the remaining
     * entries are still loaded.
     *
     * @return true when the entry was deserialized and added
     */
    static boolean tryAddColormap(PreviewMappingData target, Identifier key, JsonElement json, Gson gson) {
        try {
            final ColorMap.RawColorMap value = gson.fromJson(json, ColorMap.RawColorMap.class);
            if (value == null) {
                LOGGER.warn(" - {}: Invalid colormap entry", key);
                return false;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(" - {}: {} | {} entries", key, value.name(),
                        value.data() == null ? null : value.data().size());
            }
            target.addColormap(new ColorMap(key, value));
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn(" - {}: Skipping invalid colormap entry: {}", key, e.getMessage());
            return false;
        }
    }
}
