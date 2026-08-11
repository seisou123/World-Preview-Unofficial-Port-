// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.color;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseMultiJsonResourceReloadListener extends SimplePreparableReloadListener {
    protected static final Gson GSON = (new GsonBuilder()).create();

    private final String filename;

    protected BaseMultiJsonResourceReloadListener(String filename) {
        this.filename = filename;
    }


    @Override
    protected Map<Identifier, List<JsonElement>> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<Identifier, List<JsonElement>> res = new HashMap<>();

        for (String namespace : resourceManager.getNamespaces()) {
            loadAllForLocation(resourceManager, res, Identifier.fromNamespaceAndPath(namespace, filename));
        }

        loadAllForLocation(resourceManager, res, Identifier.fromNamespaceAndPath("c", "worldgen/" + filename));

        return res;
    }

    private void loadAllForLocation(ResourceManager resourceManager, Map<Identifier, List<JsonElement>> res, Identifier rl) {
        for (Resource x : resourceManager.getResourceStack(rl)) {
            try (Reader reader = x.openAsReader()) {
                final List<JsonElement> jsonElements = res.computeIfAbsent(rl, z -> new ArrayList<>());
                jsonElements.add(GsonHelper.fromJson(GSON, reader, JsonElement.class));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
