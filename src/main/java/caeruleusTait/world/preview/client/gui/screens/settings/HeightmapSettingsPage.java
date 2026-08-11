package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.color.ColorMap;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.client.gui.screens.PreviewContainer;
import caeruleusTait.world.preview.domain.ui.PageCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

import java.util.Map;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.*;

public class HeightmapSettingsPage extends AbstractSettingsPage {

    private final WorldPreviewConfig cfg;
    private final PreviewContainer previewContainer;

    private EditBox minYBox;
    private EditBox maxYBox;

    public HeightmapSettingsPage(WorldPreviewConfig cfg, PreviewContainer previewContainer) {
        super("heightmap", PageCategory.HEIGHTMAP);
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

        // Disabled notice
        if (!cfg.sampleHeightmap) {
            widgets.add(new StringWidget(x, y, area.width() - 8, 24,
                    SETTINGS_HEIGHTMAP_DISABLED, mc.font));
            y += 28;
        }

        // Presets
        java.util.List<PreviewData.HeightmapPresetData> presets = previewContainer.previewData().heightmapPresets();
        if (!presets.isEmpty()) {
            PreviewData.HeightmapPresetData[] presetArray = presets.toArray(PreviewData.HeightmapPresetData[]::new);
            PreviewData.HeightmapPresetData selected = presets.stream()
                    .filter(p -> p.minY() == cfg.heightmapMinY && p.maxY() == cfg.heightmapMaxY)
                    .findFirst()
                    .orElse(presetArray[0]);

            CycleButton<PreviewData.HeightmapPresetData> presetBtn = CycleButton.<PreviewData.HeightmapPresetData>builder(
                    p -> Component.literal(String.format("%s: y=%d-%d", p.name(), p.minY(), p.maxY())),
                    selected
            ).withValues(presetArray)
             .create(x, y, SettingsTheme.CONTROL_WIDTH, 20,
                     SETTINGS_HEIGHTMAP_PRESETS,
                     (btn, p) -> {
                         cfg.heightmapMinY = p.minY();
                         cfg.heightmapMaxY = p.maxY();
                         if (minYBox != null) minYBox.setValue(String.valueOf(p.minY()));
                         if (maxYBox != null) maxYBox.setValue(String.valueOf(p.maxY()));
                     });
            widgets.add(presetBtn);
            y += rowH;
        }

        // Min Y
        widgets.add(new StringWidget(x, y, SettingsTheme.LABEL_WIDTH, SettingsTheme.ROW_HEIGHT,
                SETTINGS_HEIGHTMAP_MIN_Y, mc.font));
        minYBox = new EditBox(mc.font, x + SettingsTheme.LABEL_WIDTH + 10, y, 60, 20,
                SETTINGS_HEIGHTMAP_MIN_Y);
        minYBox.setValue(String.valueOf(cfg.heightmapMinY));
        minYBox.setResponder(s -> {
            if (!s.isEmpty()) {
                if (s.matches("-?\\d*") && parseIntSafe(s) >= -64 && parseIntSafe(s) <= 512) {
                    cfg.heightmapMinY = parseIntSafe(s);
                } else {
                    minYBox.setValue(String.valueOf(cfg.heightmapMinY));
                }
            }
        });
        widgets.add(minYBox);
        y += rowH;

        // Max Y
        widgets.add(new StringWidget(x, y, SettingsTheme.LABEL_WIDTH, SettingsTheme.ROW_HEIGHT,
                SETTINGS_HEIGHTMAP_MAX_Y, mc.font));
        maxYBox = new EditBox(mc.font, x + SettingsTheme.LABEL_WIDTH + 10, y, 60, 20,
                SETTINGS_HEIGHTMAP_MAX_Y);
        maxYBox.setValue(String.valueOf(cfg.heightmapMaxY));
        maxYBox.setResponder(s -> {
            if (!s.isEmpty()) {
                if (s.matches("-?\\d*") && parseIntSafe(s) >= -64 && parseIntSafe(s) <= 512) {
                    cfg.heightmapMaxY = parseIntSafe(s);
                } else {
                    maxYBox.setValue(String.valueOf(cfg.heightmapMaxY));
                }
            }
        });
        widgets.add(maxYBox);
        y += rowH;

        // Visual range
        addCheckboxRow(x, y, SETTINGS_HEIGHTMAP_VISUAL, cfg.onlySampleInVisualRange,
                val -> cfg.onlySampleInVisualRange = val);
        y += rowH;

        // Colormap
        Map<String, ColorMap> colorMaps = previewContainer.previewData().colorMaps();
        if (!colorMaps.isEmpty()) {
            String[] keys = colorMaps.keySet().stream().sorted().toArray(String[]::new);
            String selectedKey = colorMaps.containsKey(cfg.colorMap) ? cfg.colorMap : keys[0];

            CycleButton<String> colormapBtn = CycleButton.<String>builder(
                    key -> {
                        ColorMap map = colorMaps.get(key);
                        return map != null ? Component.literal(map.name()) : Component.literal(key);
                    },
                    selectedKey
            ).withValues(keys)
             .create(x, y, SettingsTheme.CONTROL_WIDTH, 20,
                     SETTINGS_HEIGHTMAP_COLORMAP,
                     (btn, val) -> cfg.colorMap = val);
            widgets.add(colormapBtn);
        }
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    @Override
    public boolean validate() {
        if (minYBox == null || maxYBox == null) {
            return cfg.heightmapMinY >= -64 && cfg.heightmapMaxY <= 512
                    && cfg.heightmapMinY < cfg.heightmapMaxY;
        }
        String minText = minYBox.getValue();
        String maxText = maxYBox.getValue();
        if (!minText.matches("-?\\d+") || !maxText.matches("-?\\d+")) {
            return false;
        }
        try {
            int min = Integer.parseInt(minText);
            int max = Integer.parseInt(maxText);
            if (min < -64 || max > 512 || min >= max) {
                return false;
            }
            cfg.heightmapMinY = min;
            cfg.heightmapMaxY = max;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void reset() {
        cfg.heightmapMinY = 32;
        cfg.heightmapMaxY = 255;
        cfg.onlySampleInVisualRange = true;
        // colorMap reset to first available - handled at runtime
    }
}
