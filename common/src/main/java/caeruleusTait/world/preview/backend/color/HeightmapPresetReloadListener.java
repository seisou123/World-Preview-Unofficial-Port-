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

public class HeightmapPresetReloadListener extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = (new GsonBuilder()).create();

    public HeightmapPresetReloadListener() {
        super(JsonElementCodec.CODEC, FileToIdConverter.json("heightmap_preview_presets"));
    }

    @Override
    protected void apply(Object object, ResourceManager resourceManager, ProfilerFiller profiler) {
        @SuppressWarnings("unchecked")
        Map<Identifier, JsonElement> map = (Map<Identifier, JsonElement>) object;
        final WorldPreview worldPreview = WorldPreview.get();
        final PreviewMappingData previewMappingData = worldPreview.biomeColorMap();
        previewMappingData.clearHeightmapPresets();

        LOGGER.debug("Loading heightmap presets:");
        for (Map.Entry<Identifier, JsonElement> entry : map.entrySet()) {
            final PreviewData.HeightmapPresetData value = GSON.fromJson(entry.getValue(), PreviewData.HeightmapPresetData.class);
            LOGGER.debug(" - {}: {} | {} to {}", entry.getKey(), value.name(), value.minY(), value.maxY());
            previewMappingData.addHeightmapPreset(value);
        }
    }
}
