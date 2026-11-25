package caeruleusTait.world.preview.client.gui;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Single source of truth for structure display names.
 *
 * <p>Structure names used to be resolved in five places with three different
 * rules: the sidebar title-cased the path, the hover panel split the
 * already-localized name on ':' as if it were an identifier, and the analysis
 * screen looked up {@code world_preview.structure.<path>}. The same structure
 * could therefore appear under three different names, and the hover panel
 * leaked its "namespace" colour onto names that had no namespace left.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code world_preview.structure.<namespace>.<path>} — allows one mod to
 *       override a name that clashes with a vanilla path;</li>
 *   <li>{@code world_preview.structure.<path>} — the historical key;</li>
 *   <li>the name declared by the structure's own map resource, if any;</li>
 *   <li>the title-cased path.</li>
 * </ol>
 */
public final class StructureNames {

    private StructureNames() {}

    /** Display name for {@code id}, using {@code explicitName} when the resource supplies one. */
    public static Component display(Identifier id, String explicitName) {
        final String namespaceKey = "world_preview.structure." + id.getNamespace() + "." + id.getPath();
        final Language language = Language.getInstance();
        if (language.has(namespaceKey)) {
            return Component.translatable(namespaceKey);
        }
        final String pathKey = "world_preview.structure." + id.getPath();
        if (language.has(pathKey)) {
            return Component.translatable(pathKey);
        }
        if (explicitName != null && !explicitName.isBlank()
                && !explicitName.equals(id.toString())) {
            return Component.literal(explicitName);
        }
        return Component.literal(titleCase(id.getPath()));
    }

    /** Convenience for callers that need the flattened string (report files, logs). */
    public static String displayString(Identifier id, String explicitName) {
        return display(id, explicitName).getString();
    }

    /** Display name for a structure with no resource-supplied name. */
    public static Component display(Identifier id) {
        return display(id, null);
    }

    /**
     * Name plus a grey identifier line, for tooltips. Kept as one component so
     * existing newline-splitting tooltip paths keep working.
     */
    public static Component withId(Identifier id, String explicitName) {
        return Component.empty()
                .append(display(id, explicitName))
                .append(Component.literal("\n§8" + id + "§r"));
    }

    private static String titleCase(String path) {
        final String spaced = path.replace('_', ' ');
        final StringBuilder sb = new StringBuilder(spaced.length());
        boolean startOfWord = true;
        for (int i = 0; i < spaced.length(); i++) {
            final char c = spaced.charAt(i);
            if (c == ' ') {
                sb.append(c);
                startOfWord = true;
            } else if (startOfWord) {
                sb.append(Character.toUpperCase(c));
                startOfWord = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
