package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.backend.analysis.AnalysisDataState;
import caeruleusTait.world.preview.backend.analysis.ProfilePoint;
import caeruleusTait.world.preview.backend.analysis.ProfileResult;
import caeruleusTait.world.preview.client.gui.PanelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Region profile chart: one biome-colored vertical band per sampled column
 * (from the column's terrain height down to the chart bottom), optional
 * sea-level / mean-height reference lines drawn over the bands, and a
 * self-drawn multi-line hover tooltip.
 *
 * <p>The tooltip is deliberately NOT drawn via
 * {@code Screen.setTooltipForNextRenderPass}: that API differs across the
 * ported MC versions, so a plain {@link GuiGraphicsExtractor#fill} box keeps the
 * cross-version port trivial.</p>
 */
public final class ProfileChart extends AbstractWidget {
    /** Profile line preset cycled by the analysis screen's direction button. */
    public enum Direction { DIAGONAL, EAST_WEST, NORTH_SOUTH }

    /** Band color for biome ids the palette cannot resolve. */
    private static final int UNKNOWN_BIOME_COLOR = 0xFF6A6A7A;
    private static final int SEA_LEVEL_LINE_COLOR = 0xFF6FB7E8;
    private static final int MEAN_LINE_COLOR = 0xFFAAAAAA;
    private static final int TOOLTIP_BACKGROUND = 0xF0181822;
    private static final int TOOLTIP_BORDER = 0xFF3A3A4A;
    private static final int TOOLTIP_TEXT = 0xFFFFFFFF;

    private ProfileResult result;
    /** short biome id → ARGB band color; a null return (or null supplier) falls back to {@link #UNKNOWN_BIOME_COLOR}. */
    @Nullable private IntFunction<Integer> biomeColors;
    /** short biome id → display name; a null return (or null supplier) falls back to {@code "biome_<id>"}. */
    @Nullable private IntFunction<String> biomeNames;
    /** Sea-level reference line Y; -1 = hidden. */
    private int seaLevelY = -1;
    /** Mean-height reference line; null = hidden. */
    @Nullable private Double meanHeight;
    /** Title line; swapped for the short key on narrow columns. */
    private Component title = Component.translatable("world_preview.analysis.profile");

    public ProfileChart(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("world_preview.analysis.profile"));
    }

    public void setResult(@Nullable ProfileResult result) {
        this.result = result;
    }

    @Nullable
    public ProfileResult result() {
        return result;
    }

    /** Supplies the biome id → ARGB color / display name mappings used by bands and tooltip. */
    public void setPalette(@Nullable IntFunction<Integer> colors, @Nullable IntFunction<String> names) {
        this.biomeColors = colors;
        this.biomeNames = names;
    }

    /** Sea-level reference line ({@code -1} hides it). */
    public void setSeaLevel(int seaLevelY) {
        this.seaLevelY = seaLevelY;
    }

    /** Mean-height reference line ({@code null} hides it). */
    public void setMeanHeight(@Nullable Double mean) {
        this.meanHeight = mean;
    }

    /**
     * Draws the short title ({@code world_preview.analysis.profile.short})
     * instead of the full one; used on narrow right columns where the full
     * title would run into the direction button.
     */
    public void setCompactTitle(boolean compact) {
        this.title = Component.translatable(compact
                ? "world_preview.analysis.profile.short"
                : "world_preview.analysis.profile");
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xAA101418);
        graphics.text(Minecraft.getInstance().font, title, getX() + 6, getY() + 6, 0xFFFFFFFF);
        if (result == null) {
            // No profile yet at all (screen opened / fresh run) — dedicated
            // empty-state hint rather than the generic pending text.
            PanelRenderer.emptyHint(graphics, Minecraft.getInstance().font,
                    getX(), getY(), width, height,
                    Component.translatable("world_preview.analysis.empty.profile"));
            return;
        }
        List<ProfilePoint> points = result.points();
        // Domain over the sampled heights only; PENDING points carry no usable
        // height and must not distort the scale.
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int sampledCount = 0;
        for (ProfilePoint point : points) {
            if (point.state() == AnalysisDataState.SAMPLED) {
                sampledCount++;
                min = Math.min(min, point.height());
                max = Math.max(max, point.height());
            }
        }
        if (sampledCount < 2) {
            graphics.text(Minecraft.getInstance().font,
                    result.state() == AnalysisDataState.UNAVAILABLE
                            ? Component.translatable("world_preview.analysis.unavailable")
                            : Component.translatable("world_preview.analysis.pending"),
                    getX() + 6, getY() + 24, 0xFFE0E4E8);
            return;
        }

        int left = getX() + 8;
        int right = getX() + width - 8;
        int top = getY() + 24;
        int bottom = getY() + height - 12;
        if (right <= left || bottom <= top) {
            return;
        }

        // Extend the domain to the reference lines and pad both ends, so the
        // lines (and a margin around the terrain) always fit the chart.
        if (seaLevelY >= 0) {
            min = Math.min(min, seaLevelY);
            max = Math.max(max, seaLevelY);
        }
        if (meanHeight != null) {
            min = (int) Math.min(min, Math.floor(meanHeight));
            max = (int) Math.max(max, Math.ceil(meanHeight));
        }
        min -= 4;
        max += 4;
        if (max == min) {
            max++;
        }

        // Biome-colored bands: one fill per sampled column, from its height
        // down to the chart bottom. Unsampled neighbours leave a background
        // gap, making PENDING stretches visible.
        ProfilePoint first = points.get(0);
        if (first.state() == AnalysisDataState.SAMPLED) {
            int x0 = xFor(0, points.size(), left, right);
            graphics.fill(x0, yFor(first.height(), min, max, top, bottom), x0 + 1, bottom, biomeColor(first.biome()));
        }
        for (int i = 1; i < points.size(); i++) {
            ProfilePoint a = points.get(i - 1);
            ProfilePoint b = points.get(i);
            if (a.state() != AnalysisDataState.SAMPLED || b.state() != AnalysisDataState.SAMPLED) {
                continue;
            }
            int x1 = xFor(i - 1, points.size(), left, right);
            int x2 = xFor(i, points.size(), left, right);
            graphics.fill(x2, yFor(b.height(), min, max, top, bottom), x2 + Math.max(1, x2 - x1), bottom, biomeColor(b.biome()));
        }

        // Reference lines, 1px, drawn over the bands.
        if (seaLevelY >= 0) {
            int y = yFor(seaLevelY, min, max, top, bottom);
            graphics.fill(left, y, right, y + 1, SEA_LEVEL_LINE_COLOR);
        }
        if (meanHeight != null) {
            int y = yFor((int) Math.round(meanHeight), min, max, top, bottom);
            graphics.fill(left, y, right, y + 1, MEAN_LINE_COLOR);
        }

        // Hover tooltip: nearest sample column, clamped box inside the widget.
        if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
            int index = Math.min(points.size() - 1, Math.max(0,
                    Math.round((mouseX - left) * (points.size() - 1f) / Math.max(1, right - left))));
            renderHoverTooltip(graphics, points.get(index), mouseX, mouseY);
        }
    }

    /** Multi-line self-drawn tooltip box: dark background, 1px border, clamped to the widget. */
    private void renderHoverTooltip(GuiGraphicsExtractor graphics, ProfilePoint point, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>(3);
        lines.add(Component.translatable("world_preview.analysis.profile.pos", point.x(), point.z(), point.y()));
        if (point.state() == AnalysisDataState.SAMPLED) {
            lines.add(Component.translatable("world_preview.analysis.profile.height", point.height()));
            lines.add(Component.translatable("world_preview.analysis.profile.biome", biomeName(point.biome())));
        } else {
            lines.add(point.state() == AnalysisDataState.UNAVAILABLE
                    ? Component.translatable("world_preview.analysis.state.unavailable")
                    : Component.translatable("world_preview.analysis.state.pending"));
        }
        Font font = Minecraft.getInstance().font;
        int textWidth = 0;
        for (Component line : lines) {
            textWidth = Math.max(textWidth, font.width(line));
        }
        int boxWidth = textWidth + 8;
        int boxHeight = lines.size() * font.lineHeight + 4;
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
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), boxX + 4, boxY + 2 + i * font.lineHeight, TOOLTIP_TEXT);
        }
    }

    /** Band color for a biome id, with the unknown-id fallback. */
    private int biomeColor(short biomeId) {
        if (biomeColors == null) {
            return UNKNOWN_BIOME_COLOR;
        }
        Integer color = biomeColors.apply(biomeId & 0xFFFF);
        return color != null ? color : UNKNOWN_BIOME_COLOR;
    }

    /** Display name for a biome id, with the {@code biome_<id>} fallback. */
    private String biomeName(short biomeId) {
        if (biomeNames != null) {
            String name = biomeNames.apply(biomeId & 0xFFFF);
            if (name != null) {
                return name;
            }
        }
        return "biome_" + biomeId;
    }

    private static int xFor(int index, int size, int left, int right) {
        return left + (int) ((long) (right - left) * index / Math.max(1, size - 1));
    }

    private static int yFor(int value, int min, int max, int top, int bottom) {
        return bottom - (int) ((long) (bottom - top) * (value - min) / Math.max(1, max - min));
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // Display-only: never consume clicks that should reach the back button.
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
