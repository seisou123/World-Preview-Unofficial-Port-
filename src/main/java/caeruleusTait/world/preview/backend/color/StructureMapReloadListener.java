// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.color;

import caeruleusTait.world.preview.WorldPreview;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public class StructureMapReloadListener extends BaseMultiJsonResourceReloadListener {
    public StructureMapReloadListener() {
        super("structure_icons.json");
    }

    @Override
    protected void apply(Object object, ResourceManager resourceManager, ProfilerFiller profiler) {
        @SuppressWarnings("unchecked")
        Map<Identifier, List<JsonElement>> map = (Map<Identifier, List<JsonElement>>) object;
        final WorldPreview worldPreview = WorldPreview.get();
        final PreviewMappingData previewMappingData = worldPreview.biomeColorMap();
        previewMappingData.clearStructures();

        LOGGER.debug("Loading structure resource entries");
        for (var entry : map.entrySet()) {
            LOGGER.debug(" - loading entries from {}", entry.getKey());
            for (JsonElement jsonElement : entry.getValue()) {
                Map<Identifier, PreviewMappingData.StructureEntry> curr = parseStructureData(
                        entry.getKey().getNamespace(),
                        jsonElement,
                        PreviewData.DataSource.RESOURCE
                );
                previewMappingData.updateStruct(curr);
            }
        }
    }

    public static Map<Identifier, PreviewMappingData.StructureEntry> parseStructureData(String namespace, JsonElement jsonElement, PreviewData.DataSource dataSource) {
        final Map<Identifier, PreviewMappingData.StructureEntry> res = new HashMap<>();
        final JsonObject obj = jsonElement.getAsJsonObject();

        for (var entry : obj.entrySet()) {
            final Identifier location = Identifier.parse(entry.getKey());
            final PreviewMappingData.StructureEntry value = new PreviewMappingData.StructureEntry();
            final JsonElement rawEl = entry.getValue();

            value.dataSource = dataSource;

            try {
                if (rawEl.isJsonPrimitive()) {
                    if (rawEl.getAsString().equals("hidden")) {
                        continue;
                    }
                    value.item = rawEl.getAsString();
                } else {
                    JsonObject raw = rawEl.getAsJsonObject();
                    JsonElement nameEl = raw.get("name");
                    JsonElement itemEl = raw.get("item");
                    JsonElement iconEl = raw.get("icon");
                    JsonElement textureEl = raw.get("texture");
                    value.name = nameEl == null ? null : nameEl.getAsString();
                    if (textureEl == null) {
                        textureEl = iconEl;
                    }
                    if (textureEl != null) {
                        value.texture = textureEl.getAsString();
                    } else if (itemEl != null) {
                        if (itemEl.getAsString().equals("hidden")) {
                            continue;
                        }
                        value.item = itemEl.getAsString();
                    } else {
                        value.texture = "world_preview:textures/structure/unknown.png";
                    }
                }
            } catch (IllegalStateException | UnsupportedOperationException | NullPointerException e) {
                LOGGER.warn("   - {}: Invalid structure entry format: {}", location, e.getMessage());
                continue;
            }

            LOGGER.debug("   - {}: {} - {}", location, value.name, value.texture);
            res.put(location, value);
        }

        return res;
    }
}
