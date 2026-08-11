package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.backend.analysis.AnalysisDataState;
import caeruleusTait.world.preview.backend.analysis.AnalysisProgress;
import caeruleusTait.world.preview.backend.analysis.AnalysisStatus;
import caeruleusTait.world.preview.backend.analysis.RegionMetrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class AnalysisPanel extends AbstractWidget {
    private RegionMetrics metrics;
    private AnalysisProgress progress;

    public AnalysisPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("world_preview.analysis.metrics"));
    }

    public void setMetrics(RegionMetrics metrics) {
        this.metrics = metrics;
    }

    public void setProgress(AnalysisProgress progress) {
        this.progress = progress;
    }

    public RegionMetrics metrics() {
        return metrics;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int textX = getX() + 6;
        int textY = getY() + 6;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xAA101418);
        graphics.text(Minecraft.getInstance().font, Component.translatable("world_preview.analysis.metrics"), textX, textY, 0xFFFFFFFF);
        if (metrics == null) {
            drawLine(graphics, textX, textY + 18, Component.translatable("world_preview.analysis.pending"));
            return;
        }
        AnalysisStatus runStatus = progress == null ? AnalysisStatus.QUEUED : progress.status();
        drawLine(graphics, textX, textY + 18, Component.translatable("world_preview.analysis.status", runStatusLabel(runStatus)));
        drawLine(graphics, textX, textY + 32, Component.translatable("world_preview.analysis.sample_state", sampleStatus(metrics.state())));
        drawLine(graphics, textX, textY + 46, Component.translatable("world_preview.analysis.coverage", percent(metrics.coverage())));
        if (progress != null) {
            drawLine(graphics, textX, textY + 60, Component.translatable("world_preview.analysis.progress.count",
                    progress.completedUnits(), progress.totalUnits()));
            drawLine(graphics, textX, textY + 74, Component.translatable("world_preview.analysis.biomes", metrics.biomeCounts().size()));
            drawLine(graphics, textX, textY + 88, Component.translatable("world_preview.analysis.height.range", optional(metrics.minHeight()), optional(metrics.maxHeight())));
            drawLine(graphics, textX, textY + 102, Component.translatable("world_preview.analysis.height.mean", optional(metrics.meanHeight())));
            drawLine(graphics, textX, textY + 116, Component.translatable("world_preview.analysis.slope", optional(metrics.meanSlope()), optional(metrics.maxSlope())));
            drawLine(graphics, textX, textY + 130, Component.translatable("world_preview.analysis.flat", percent(metrics.flatRatio())));
            if (metrics.state() == AnalysisDataState.UNAVAILABLE && !metrics.unavailableReason().isBlank()) {
                drawLine(graphics, textX, textY + 144, Component.translatable("world_preview.analysis.unavailable.reason", metrics.unavailableReason()));
            }
        } else {
            drawLine(graphics, textX, textY + 60, Component.translatable("world_preview.analysis.biomes", metrics.biomeCounts().size()));
            drawLine(graphics, textX, textY + 74, Component.translatable("world_preview.analysis.height.range", optional(metrics.minHeight()), optional(metrics.maxHeight())));
            drawLine(graphics, textX, textY + 88, Component.translatable("world_preview.analysis.height.mean", optional(metrics.meanHeight())));
            drawLine(graphics, textX, textY + 102, Component.translatable("world_preview.analysis.slope", optional(metrics.meanSlope()), optional(metrics.maxSlope())));
            drawLine(graphics, textX, textY + 116, Component.translatable("world_preview.analysis.flat", percent(metrics.flatRatio())));
            if (metrics.state() == AnalysisDataState.UNAVAILABLE && !metrics.unavailableReason().isBlank()) {
                drawLine(graphics, textX, textY + 130, Component.translatable("world_preview.analysis.unavailable.reason", metrics.unavailableReason()));
            }
        }
    }

    private void drawLine(GuiGraphicsExtractor graphics, int x, int y, Component text) {
        graphics.text(Minecraft.getInstance().font, text, x, y, 0xFFE0E4E8);
    }

    private static String runStatusLabel(AnalysisStatus status) {
        return Component.translatable("world_preview.analysis.run." + status.name().toLowerCase(Locale.ROOT)).getString();
    }

    private static String sampleStatus(AnalysisDataState state) {
        return Component.translatable("world_preview.analysis.state." + state.name().toLowerCase(Locale.ROOT)).getString();
    }

    private static String optional(java.util.OptionalInt value) {
        return value.isPresent() ? Integer.toString(value.getAsInt()) : "—";
    }

    private static String optional(java.util.OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, "%.2f", value.getAsDouble()) : "—";
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // Display-only: never consume clicks that should reach buttons underneath/nearby.
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
