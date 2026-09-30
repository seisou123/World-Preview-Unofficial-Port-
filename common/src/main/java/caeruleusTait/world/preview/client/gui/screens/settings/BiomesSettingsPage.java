package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.client.gui.screens.PreviewContainer;
import caeruleusTait.world.preview.domain.ui.PageCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

public class BiomesSettingsPage extends AbstractSettingsPage {

    private final PreviewContainer previewContainer;
    private final SettingsScreen parentScreen;
    private final net.minecraft.resources.Identifier pendingDimension;

    public BiomesSettingsPage(PreviewContainer previewContainer, SettingsScreen parentScreen) {
        this(previewContainer, parentScreen, null);
    }

    public BiomesSettingsPage(PreviewContainer previewContainer, SettingsScreen parentScreen, net.minecraft.resources.Identifier pendingDimension) {
        super("biomes", PageCategory.BIOME);
        this.previewContainer = previewContainer;
        this.parentScreen = parentScreen;
        this.pendingDimension = pendingDimension;
    }

    @Override
    public void build(ScreenRectangle area, PreviewContainer previewContainer) {
        widgets.clear();

        int x = area.left() + SettingsTheme.CONTENT_PADDING;
        int y = area.top() + 4;
        Minecraft mc = Minecraft.getInstance();

        widgets.add(new StringWidget(x, y, area.width() - 8, 12,
                Component.translatable("world_preview.settings.biomes.open.desc"), mc.font));
        y += 24;

        Button openBtn = Button.builder(
                Component.translatable("world_preview.settings.biomes.open"),
                b -> {
                    Minecraft.getInstance()
                            .gui.setScreen(new BiomesEditorScreen(parentScreen, previewContainer, pendingDimension));
                }
        ).bounds(x, y, 200, 20).build();
        widgets.add(openBtn);
    }
}
