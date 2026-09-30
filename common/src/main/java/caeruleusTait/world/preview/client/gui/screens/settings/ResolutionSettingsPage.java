package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.domain.ui.PageCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.*;

public class ResolutionSettingsPage extends AbstractSettingsPage {

    private final RenderSettings rs;

    public ResolutionSettingsPage(RenderSettings rs) {
        super("resolution", PageCategory.RESOLUTION);
        this.rs = rs;
    }

    @Override
    public void build(ScreenRectangle area, caeruleusTait.world.preview.client.gui.screens.PreviewContainer pc) {
        widgets.clear();

        int x = area.left() + SettingsTheme.CONTENT_PADDING;
        int y = area.top() + 4;
        int rowH = SettingsTheme.ROW_HEIGHT + SettingsTheme.ROW_VSPACE;
        Minecraft mc = Minecraft.getInstance();

        // Descriptions
        widgets.add(new StringWidget(x, y, contentWidth(area, x), 12,
                SETTINGS_SAMPLE_HEAD, mc.font));
        y += 16;

        // Pixels per chunk
        y += 4;
        widgets.add(new StringWidget(x, y, contentWidth(area, x), 12,
                SETTINGS_SAMPLE_PIXELS_TITLE_1, mc.font));
        y += 14;
        widgets.add(new StringWidget(x, y, contentWidth(area, x), 12,
                SETTINGS_SAMPLE_PIXELS_TITLE_2, mc.font));
        y += 14;

        Integer[] pixelOptions = {16, 8, 4, 2, 1};
        int current = rs.pixelsPerChunk();
        Integer selected = java.util.Arrays.stream(pixelOptions)
                .anyMatch(v -> v == current) ? current : 4;

        CycleButton<Integer> pixelBtn = CycleButton.<Integer>builder(
                v -> Component.translatable("world_preview.settings.sample.numChunk.name.NUM_" + v),
                selected
        ).withValues(pixelOptions)
         .create(x, y, controlWidth(area, x), 20,
                 SETTINGS_SAMPLE_PIXELS_TITLE_1,
                 (btn, val) -> {
                     int previous = rs.pixelsPerChunk();
                     rs.setPixelsPerChunk(val);
                     if (previous == val) {
                         return;
                     }
                     pc.previewDisplay().invalidateRenderCache();
                     if (RenderSettings.samplerStrideFor(previous)
                             != RenderSettings.samplerStrideFor(val)) {
                         // Only the 2/1 px levels change the sampling stride
                         // (they skip quarts), which requires recreating the
                         // sampler and storage sections.
                         pc.workManager().cancel();
                         pc.start();
                         // This page's cancel+start already resumed/rebuilt the
                         // preview: refresh the container's structural snapshot
                         // so closing the settings screen does not trigger a
                         // second full rebuild for the same change.
                         pc.markStructuralRebuildApplied();
                     } else {
                         // Render-only rescale: reuse the sampled quarts, just
                         // re-queue the new field of view (same as the wheel).
                         pc.previewDisplay().applyIncrementalZoom();
                     }
                 });
        pixelBtn.setTooltip(Tooltip.create(SETTINGS_SAMPLE_PIXELS_TITLE_2));
        widgets.add(pixelBtn);
        y += rowH + 4;

        // Sampler type
        widgets.add(new StringWidget(x, y, contentWidth(area, x), 12,
                SETTINGS_SAMPLE_SAMPLE_TITLE_1, mc.font));
        y += 14;
        widgets.add(new StringWidget(x, y, contentWidth(area, x), 12,
                SETTINGS_SAMPLE_SAMPLE_TITLE_2, mc.font));
        y += 14;

        CycleButton<RenderSettings.SamplerType> samplerBtn = CycleButton.<RenderSettings.SamplerType>builder(
                v -> Component.translatable("world_preview.settings.sample.sampler.name." + v.name()),
                rs.samplerType
        ).withValues(RenderSettings.SamplerType.values())
         .create(x, y, controlWidth(area, x), 20,
                 SETTINGS_SAMPLE_SAMPLE_TITLE_1,
                 (btn, val) -> rs.samplerType = val);
        samplerBtn.setTooltip(Tooltip.create(SETTINGS_SAMPLE_SAMPLE_TITLE_2));
        widgets.add(samplerBtn);
    }

    @Override
    public void reset() {
        rs.setPixelsPerChunk(4);
        rs.samplerType = RenderSettings.SamplerType.AUTO;
    }
}
