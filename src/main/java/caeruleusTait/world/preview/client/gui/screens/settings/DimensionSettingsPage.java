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
        Identifier selected = rs.dimension;
        if (selected == null || !keys.contains(selected)) {
            selected = dims[0];
            rs.dimension = selected;
        }

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
        java.util.List<Identifier> keys = previewContainer.levelStemKeys();
        if (keys != null && !keys.isEmpty()) {
            rs.dimension = keys.stream()
                    .sorted(Comparator.comparing(Identifier::toString))
                    .findFirst()
                    .orElse(null);
        }
    }
}
