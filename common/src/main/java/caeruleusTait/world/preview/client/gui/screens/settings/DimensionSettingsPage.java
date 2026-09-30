package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.client.gui.screens.PreviewContainer;
import caeruleusTait.world.preview.domain.ui.PageCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Comparator;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.*;

public class DimensionSettingsPage extends AbstractSettingsPage {

    /**
     * The Overworld level-stem key, spelled literally to mirror
     * {@code LevelStem.OVERWORLD.identifier()} as used by PreviewContainer's
     * null fallback in updateSettings_real.  Kept as a plain Identifier because
     * touching {@code LevelStem} class-initializes DimensionType and
     * BuiltInRegistries, which requires bootstrap and would break headless
     * unit tests of {@link #displayDimension}.
     */
    private static final Identifier OVERWORLD = Identifier.withDefaultNamespace("overworld");

    private final RenderSettings rs;
    private final PreviewContainer previewContainer;

    public DimensionSettingsPage(RenderSettings rs, PreviewContainer previewContainer) {
        super("dimension", PageCategory.DIMENSION);
        this.rs = rs;
        this.previewContainer = previewContainer;
    }

    @Override
    public void build(ScreenRectangle area, PreviewContainer previewContainer) {
        widgets.clear();

        int x = area.left() + SettingsTheme.CONTENT_PADDING;
        int y = area.top() + 4;
        Minecraft mc = Minecraft.getInstance();

        widgets.add(new StringWidget(x, y, area.width() - 8, 12,
                SETTINGS_DIM_HEAD, mc.font));
        y += 16;

        java.util.List<Identifier> keys = previewContainer.levelStemKeys();
        if (keys == null || keys.isEmpty()) {
            widgets.add(new StringWidget(x, y, area.width() - 8, 24,
                    Component.translatable("world_preview.settings.dimensions.empty"), mc.font));
            return;
        }

        Identifier[] dims = keys.stream()
                .sorted(Comparator.comparing(Identifier::toString))
                .toArray(Identifier[]::new);
        // Display-only initial selection: never write back into the pending
        // settings.  rs.dimension == null means "default" (the container
        // resolves it to the Overworld once the level stems are known), and
        // coercing it here made rebuildCurrentPage() promote the pending value
        // to an explicit dimension before Done could save the default.
        Identifier selected = displayDimension(rs.dimension, keys);

        CycleButton<Identifier> dimBtn = CycleButton.<Identifier>builder(
                id -> {
                    String langKey = id.toLanguageKey("dimension");
                    if (net.minecraft.locale.Language.getInstance().has(langKey)) {
                        return Component.translatable(langKey);
                    }
                    return Component.literal(id.toString());
                },
                selected
        ).withValues(dims)
         .create(x, y + 4, SettingsTheme.CONTROL_WIDTH, 20,
                 SETTINGS_DIM_TITLE,
                 (btn, val) -> rs.dimension = val);
        widgets.add(dimBtn);
    }

    @Override
    public void reset() {
        // RenderSettings' default is null, which the container resolves to the
        // Overworld once the level stems are known.
        rs.dimension = null;
    }

    /**
     * Display-only fallback for the dimension cycle button's initial value:
     * keeps the current setting while it is still a valid key, otherwise
     * prefers the Overworld (mirroring PreviewContainer's null fallback in
     * updateSettings_real) and finally the first sorted key.  Pure — it never
     * mutates the pending settings, so a null ("default") dimension stays null
     * until the user actively picks one or Done hands it back to the container.
     */
    static Identifier displayDimension(Identifier current, java.util.List<Identifier> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        if (current != null && keys.contains(current)) {
            return current;
        }
        if (keys.contains(OVERWORLD)) {
            return OVERWORLD;
        }
        return keys.stream()
                .sorted(Comparator.comparing(Identifier::toString))
                .findFirst()
                .orElse(null);
    }
}
