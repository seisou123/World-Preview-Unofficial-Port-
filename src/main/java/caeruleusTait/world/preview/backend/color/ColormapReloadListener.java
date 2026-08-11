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
            final ColorMap.RawColorMap value = GSON.fromJson(entry.getValue(), ColorMap.RawColorMap.class);
            LOGGER.debug(" - {}: {} | {} entries", entry.getKey(), value.name(), value.data().size());
            previewMappingData.addColormap(new ColorMap(entry.getKey(), value));
        }
    }
}
