// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class WorldPreviewClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Shader registration removed - MC 1.21.11 uses a new render pipeline system
    }


    // Use ConcurrentHashMap for thread-safety (render thread + init thread
    // may both call renderTexture).  This prevents race conditions when
    // multiple threads try to register the same texture.
    private static final ConcurrentHashMap<AbstractTexture, Identifier> textureRegistry = new ConcurrentHashMap<>();

    public static void renderTexture(GuiGraphicsExtractor guiGraphicsExtractor, AbstractTexture texture, double xMin, double yMin, double xMax, double yMax) {
        if (texture == null) return;

        int w = (int)(xMax - xMin);
        int h = (int)(yMax - yMin);

        // For DynamicTexture, use the standard blit method with Identifier
        // The texture must be registered with TextureManager
        final Minecraft mc = Minecraft.getInstance();
        final TextureManager tm = mc.getTextureManager();

        Identifier texId = textureRegistry.get(texture);
        if (texId == null) {
            texId = Identifier.parse("world_preview:dynamic_" + System.identityHashCode(texture));
            // Use putIfAbsent to avoid re-registering if another thread
            // already registered the same texture between our get() and put().
            Identifier existing = textureRegistry.putIfAbsent(texture, texId);
            if (existing != null) {
                texId = existing;
            } else {
                tm.register(texId, texture);
            }
        }

        // Pass w/h as both the render dimensions and the "texture dimensions"
        // used for UV normalisation.  This ensures the UV range is 0.0–1.0 so
        // the *entire* GPU texture is sampled.  Passing the real pixel
        // dimensions (e.g. w*guiScale) here would shrink the UV range to
        // 1/guiScale, causing only the top-left portion of the texture to be
        // displayed and the rest to appear black.
        try {
            guiGraphicsExtractor.blit(RenderPipelines.GUI_TEXTURED, texId, (int)xMin, (int)yMin, 0.0f, 0.0f, w, h, w, h);
        } catch (Exception e) {
            // The texture was likely closed/unregistered between our check and
            // the blit call.  Remove the stale entry from the registry so
            // subsequent frames don't keep trying to use it.
            textureRegistry.remove(texture);
        }
    }

    public static void renderTexture(AbstractTexture texture, double xMin, double yMin, double xMax, double yMax) {
        // Legacy method - tries to get guiGraphicsExtractor from current screen
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) {
            // Can't easily get guiGraphicsExtractor here, so we skip rendering
            // Callers should use the guiGraphicsExtractor version instead
        }
    }

    /**
     * Removes a dynamic texture from the local registry and TextureManager.
     * Call this before or instead of relying solely on {@link AbstractTexture#close()}
     * so repeated open/close of the preview does not leak Identifier entries.
     */
    public static void unregisterTexture(AbstractTexture texture) {
        if (texture == null) {
            return;
        }
        Identifier texId = textureRegistry.remove(texture);
        if (texId == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.getTextureManager().release(texId);
        }
    }

    /** Test/debug helper: number of dynamic textures currently tracked. */
    public static int registeredTextureCount() {
        return textureRegistry.size();
    }

    public static String toTitleCase(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        return Arrays
                .stream(input.split(" "))
                .map(StringUtils::capitalize)
                .collect(Collectors.joining(" "));
    }
}
