package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.client.gui.screens.PreviewContainer;
import caeruleusTait.world.preview.domain.ui.PageCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.*;

public class CacheSettingsPage extends AbstractSettingsPage {

    private static final Component CLEAR_ANALYSIS = Component.translatable("world_preview.settings.cache.clear.analysis");
    private static final Component CLEAR_ANALYSIS_TOOLTIP = Component.translatable("world_preview.settings.cache.clear.analysis.tooltip");

    private final WorldPreviewConfig cfg;
    private final PreviewContainer previewContainer;

    public CacheSettingsPage(WorldPreviewConfig cfg, PreviewContainer previewContainer) {
        super("cache", PageCategory.CACHE);
        this.cfg = cfg;
        this.previewContainer = previewContainer;
    }

    @Override
    public void build(ScreenRectangle area, PreviewContainer previewContainer) {
        widgets.clear();

        int x = area.left() + SettingsTheme.CONTENT_PADDING;
        int y = area.top() + 4;
        int rowH = SettingsTheme.ROW_HEIGHT + SettingsTheme.ROW_VSPACE;
        Minecraft mc = Minecraft.getInstance();

        // Description
        widgets.add(new StringWidget(x, y, contentWidth(area, x), 12,
                SETTINGS_CACHE_DESC, mc.font));
        y += 16;

        // Cache toggles
        addCheckboxRow(x, y, SETTINGS_CACHE_G_ENABLE, cfg.cacheInGame,
                val -> cfg.cacheInGame = val);
        y += rowH;
        addCheckboxRow(x, y, SETTINGS_CACHE_N_ENABLE, cfg.cacheInNew,
                val -> cfg.cacheInNew = val);
        y += rowH;
        addCheckboxRow(x, y, SETTINGS_CACHE_COMPRESSION, cfg.enableCompression,
                SETTINGS_CACHE_COMPRESSION_TOOLTIP,
                val -> cfg.enableCompression = val);
        y += rowH;

        // Clear cache button
        y += 12;
        Button clearBtn = Button.builder(SETTINGS_CACHE_CLEAR, b -> {
            try {
                previewContainer.dataProvider().clearCache();
            } catch (Exception e) {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(SETTINGS_CACHE_CLEAR_FAILED, false);
                }
            }
        }).bounds(x, y, controlWidth(area, x), 20).build();
        clearBtn.setTooltip(Tooltip.create(SETTINGS_CACHE_CLEAR_TOOLTIP));
        widgets.add(clearBtn);

        y += 28;
        Button clearAnalysisBtn = Button.builder(CLEAR_ANALYSIS, b -> {
            try {
                WorldPreview.get().clearAnalysisData();
            } catch (Exception e) {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(SETTINGS_CACHE_CLEAR_FAILED, false);
                }
            }
        }).bounds(x, y, controlWidth(area, x), 20).build();
        clearAnalysisBtn.setTooltip(Tooltip.create(CLEAR_ANALYSIS_TOOLTIP));
        widgets.add(clearAnalysisBtn);
    }

    @Override
    public void reset() {
        cfg.cacheInGame = true;
        cfg.cacheInNew = false;
        cfg.enableCompression = true;
    }
}
