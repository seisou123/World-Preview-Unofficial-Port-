package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.backend.analysis.RegionMetrics;
import caeruleusTait.world.preview.client.gui.PanelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Height distribution chart for the analysis screen's "heights" tab: one bar
 * per observed Y level over {@code [histogramMinY .. histogramMinY + len - 1]}
 * (bar height proportional to count / maxCount), 1px reference marker lines
 * for min (white) / median (yellow) / mean (gray) heights, each with a small
 * staggered label above the bars, and a self-drawn hover tooltip showing the
 * hovered bucket's {@code Y} and sample count.
 *
 * <p>Like {@link ProfileChart}, the tooltip is deliberately drawn with plain
 * {@link GuiGraphics#fill} boxes instead of
 * {@code Screen.setTooltipForNextRenderPass} so the widget ports across the
 * MC versions unchanged.</p>
 */
public final class HeightHistogramChart extends AbstractWidget {

    private static final Component TITLE = Component.translatable("world_preview.analysis.tab.height");
    private static final Component EMPTY_HINT = Component.translatable("world_preview.analysis.empty.height");

    private static final int BAR_COLOR = 0xFF5B8FD4;
    /** Marker line + label colors: min white, median yellow, mean gray. */
    private static final int MIN_MARKER_COLOR = 0xFFFFFFFF;
    private static final int MEDIAN_MARKER_COLOR = 0xFFFFFF55;
    private static final int MEAN_MARKER_COLOR = 0xFFAAAAAA;
    /** Vertical distance between the staggered marker labels (one row each). */
    private static final int MARKER_LABEL_ROW = 10;
    private static final int TOOLTIP_BACKGROUND = 0xF0181822;
    private static final int TOOLTIP_BORDER = 0xFF3A3A4A;
    private static final int TOOLTIP_TEXT = 0xFFFFFFFF;

    @Nullable private RegionMetrics metrics;

    public HeightHistogramChart(int x, int y, int width, int height) {
        super(x, y, width, height, TITLE);
    }

    /** Replaces the chart data; {@code null} (or an empty histogram) shows the empty hint. */
    public void setData(@Nullable RegionMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xAA101418);
        graphics.drawString(font, TITLE, getX() + 6, getY() + 6, 0xFFFFFFFF);

        int[] histogram = metrics == null ? null : metrics.heightHistogram();
        if (histogram == null || histogram.length == 0 || maxCount(histogram) <= 0) {
            PanelRenderer.emptyHint(graphics, font, getX() + width / 2, getY() + height / 2, EMPTY_HINT);
            return;
        }
        long maxCount = maxCount(histogram);
        int left = getX() + 8;
        int right = getX() + width - 8;
        int top = getY() + 24;
        int bottom = getY() + height - 12;
        if (right <= left || bottom <= top) {
            return;
        }
        int histMinY = metrics.histogramMinY();

        // Marker labels sit in staggered rows above the bars; the bars keep at
        // least half the chart height even when all three markers are present.
        List<Marker> markers = presentMarkers();
        int barsTop = top + markers.size() * MARKER_LABEL_ROW;
        barsTop = Math.min(barsTop, top + (bottom - top) / 2);

        int n = histogram.length;
        for (int i = 0; i < n; i++) {
            int count = histogram[i];
            if (count <= 0) {
                continue;
            }
            int x0 = left + (int) ((long) (right - left) * i / n);
            int x1 = left + (int) ((long) (right - left) * (i + 1) / n);
            x1 = Math.max(x1, x0 + 1);
            int barH = (int) Math.max(1L, (long) (bottom - barsTop) * count / maxCount);
            graphics.fill(x0, bottom - barH, Math.min(x1, right), bottom, BAR_COLOR);
        }

        for (int m = 0; m < markers.size(); m++) {
            Marker marker = markers.get(m);
            int lineX = xForValue(marker.value(), histMinY, n, left, right);
            graphics.fill(lineX, barsTop, lineX + 1, bottom, marker.color());
            int labelX = Math.min(Math.max(lineX, left), Math.max(left, right - font.width(marker.label())));
            graphics.drawString(font, marker.label(), labelX, top + m * MARKER_LABEL_ROW, marker.color());
        }

        if (mouseX >= left && mouseX < right && mouseY >= barsTop && mouseY <= bottom) {
            int index = (int) ((mouseX - left) * n / (right - left));
            index = Math.min(n - 1, Math.max(0, index));
            renderHoverTooltip(graphics, font, histMinY + index, histogram[index], mouseX, mouseY);
        }
    }

    /** Min / median / mean marker lines, in draw order, only those the metrics actually provide. */
    private List<Marker> presentMarkers() {
        List<Marker> markers = new ArrayList<>(3);
        if (metrics.minHeight().isPresent()) {
            markers.add(new Marker(metrics.minHeight().getAsInt(), "min", MIN_MARKER_COLOR));
        }
        if (metrics.medianHeight().isPresent()) {
            markers.add(new Marker((int) Math.round(metrics.medianHeight().getAsDouble()), "med", MEDIAN_MARKER_COLOR));
        }
        if (metrics.meanHeight().isPresent()) {
            markers.add(new Marker((int) Math.round(metrics.meanHeight().getAsDouble()), "avg", MEAN_MARKER_COLOR));
        }
        return markers;
    }

    /** X of a Y value: the center of its bucket, clamped into the chart area. */
    private static int xForValue(int value, int histMinY, int buckets, int left, int right) {
        double fraction = (value - histMinY + 0.5) / buckets;
        int x = left + (int) Math.round(fraction * (right - left));
        return Math.min(right - 1, Math.max(left, x));
    }

    private static long maxCount(int[] histogram) {
        long max = 0;
        for (int count : histogram) {
            max = Math.max(max, count);
        }
        return max;
    }

    /** Single-line self-drawn tooltip box, same visual language as ProfileChart. */
    private void renderHoverTooltip(GuiGraphics graphics, Font font, int y, int count, int mouseX, int mouseY) {
        Component line = Component.translatable("world_preview.analysis.hist.tooltip", y, count);
        int boxWidth = font.width(line) + 8;
        int boxHeight = font.lineHeight + 4;
        int boxX = mouseX + 6;
        if (boxX + boxWidth > getX() + getWidth()) {
            boxX = mouseX - boxWidth - 6;
        }
        if (boxX < getX()) {
            boxX = getX();
        }
        int boxY = mouseY - boxHeight - 4;
        if (boxY < getY()) {
            boxY = mouseY + 12;
        }
        if (boxY + boxHeight > getY() + getHeight()) {
            boxY = getY() + getHeight() - boxHeight;
        }
        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, TOOLTIP_BACKGROUND);
        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + 1, TOOLTIP_BORDER);
        graphics.fill(boxX, boxY + boxHeight - 1, boxX + boxWidth, boxY + boxHeight, TOOLTIP_BORDER);
        graphics.fill(boxX, boxY, boxX + 1, boxY + boxHeight, TOOLTIP_BORDER);
        graphics.fill(boxX + boxWidth - 1, boxY, boxX + boxWidth, boxY + boxHeight, TOOLTIP_BORDER);
        graphics.drawString(font, line, boxX + 4, boxY + 2, TOOLTIP_TEXT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Display-only: never consume clicks that should reach other widgets.
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }

    private record Marker(int value, String label, int color) {
    }
}
