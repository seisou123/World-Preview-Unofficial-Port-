package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.backend.analysis.BiomeRarity;
import caeruleusTait.world.preview.backend.analysis.RegionInsights;
import caeruleusTait.world.preview.backend.analysis.RegionMetrics;
import caeruleusTait.world.preview.backend.export.TerrainCategory;
import caeruleusTait.world.preview.client.gui.PanelRenderer;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Biome share panel for the analysis screen's "biomes" tab, in three stacked
 * sections: a 100% stacked bar of the top {@literal <=}10 biomes with the
 * remainder slot in gray, a terrain-category stacked bar (plus one legend of
 * color chips, reusing the {@code world_preview.terrain_export.category.*}
 * labels and the TerrainExportScreen chip pattern) built from
 * {@link RegionInsights#terrainCounts()}, and a scrollable full biome table
 * (one row per present biome, count-descending).
 *
 * <p>Clicking a row fires {@link RowAction} with the row's biome id — or
 * {@code null} when that row was already selected, so the screen can clear the
 * map highlight. The panel itself never owns the highlight: the screen calls
 * {@link #setSelected} back so the selection survives data refreshes.</p>
 */
public final class BiomeSharePanel extends AbstractWidget {

    private static final Component TITLE = Component.translatable("world_preview.analysis.tab.biomes");
    private static final Component EMPTY_HINT = Component.translatable("world_preview.analysis.empty.biomes");

    /** Height of one biome table row (2px color bar spans y+2..y+14 inside it). */
    private static final int ROW_HEIGHT = 16;
    /** Slot color behind both stacked bars / the unclassified remainder. */
    private static final int REMAINDER_COLOR = 0xFF3A3A4A;
    /** Background of the selected row. */
    private static final int SELECTED_BACKGROUND = 0x33FFFFFF;
    /** Fallback color for biome ids the lookup cannot resolve (ProfileChart's gray). */
    private static final int UNKNOWN_BIOME_COLOR = 0xFF6A6A7A;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_SOFT = 0xFFE0E4E8;
    private static final int TEXT_STARS = 0xFF808080;
    /** How many leading rows the stacked bar shows; everything else stays in the remainder slot. */
    private static final int STACKED_BAR_ROWS = 10;
    private static final int LEGEND_ROW_HEIGHT = 12;
    /** Rows scrolled per wheel notch. */
    private static final int WHEEL_STEP_ROWS = 3;
    /** Thumb color of the table scrollbar (the track reuses REMAINDER_COLOR). */
    private static final int SCROLLBAR_THUMB = 0xFF9AA2AE;
    /** Rendered width of the scrollbar track/thumb. */
    private static final int SCROLLBAR_BAR_WIDTH = 4;
    /** Hit-strip width on the list's right edge (vanilla AbstractScrollArea.SCROLLBAR_WIDTH). */
    private static final int SCROLLBAR_HIT_WIDTH = 6;
    /** Deadzone around the autoscroll anchor, in gui pixels, before scrolling starts. */
    private static final double PAN_DEADZONE_PX = 3.0;
    /** Autoscroll speed in rows per second per gui pixel of anchor displacement. */
    private static final double PAN_ROWS_PER_SEC_PER_PX = 0.75;
    /** Upper autoscroll speed bound, in rows per second. */
    private static final double PAN_MAX_ROWS_PER_SEC = 30.0;
    /** Time constant of the eased scroll animation, in milliseconds. */
    private static final double SCROLL_EASE_MS = 60.0;
    /** Compact-layout list top: just below the biome bar. */
    private static final int COMPACT_LIST_TOP = 44;

    /**
     * Row click callback: the biome id of the clicked row, or {@code null}
     * when the click was on the already-selected row (deselect).
     */
    @FunctionalInterface
    public interface RowAction {
        void onRowClick(@Nullable Short biomeId);
    }

    private record ShareRow(short biomeId, String name, long count, double sharePercent, int stars, int argbColor) {
    }

    private record LegendItem(TerrainCategory category, int x, int y, Component label) {
    }

    private List<ShareRow> rows = List.of();
    /** Total samples the shares are computed against (metrics.presentSamples()). */
    private long totalSamples;
    @Nullable private RegionInsights insights;
    @Nullable private RowAction rowAction;
    /** Highlighted biome id; null = none. Kept in sync by the screen via {@link #setSelected}. */
    @Nullable private Short selected;
    /** First visible table row, clamped to {@code [0, rows * ROW_HEIGHT - listHeight]}. */
    private int scrollOffset;
    /** True between a middle-press autoscroll start and its release (screen-routed). */
    private boolean panActive;
    /** Autoscroll anchor: the cursor Y at the middle press. */
    private double panAnchorY;
    /** True between a left-press on the scrollbar strip and its release. */
    private boolean scrolling;
    /** Sub-row drag accumulator: the precise offset while the thumb is dragged. */
    private double scrollDragRows;
    /** Eased scroll position actually rendered; chases {@link #scrollOffset} for smooth motion. */
    private double renderOffset;
    /** Previous render timestamp, for frame-rate independent easing and autoscroll. */
    private long lastFrameMs = -1;

    public BiomeSharePanel(int x, int y, int width, int height) {
        super(x, y, width, height, TITLE);
    }

    public void setRowAction(@Nullable RowAction action) {
        this.rowAction = action;
    }

    /** Highlights the given biome id in the table (and nowhere else); null clears it. */
    public void setSelected(@Nullable Short biomeId) {
        this.selected = biomeId;
    }

    /**
     * Replaces the panel data. Called on every metrics refresh; the scroll
     * offset survives so a running analysis does not yank the list around.
     */
    public void setData(@Nullable RegionMetrics metrics,
                        @Nullable IntFunction<BiomesList.BiomeEntry> lookup,
                        @Nullable RegionInsights insights) {
        this.insights = insights;
        this.totalSamples = metrics == null ? 0 : metrics.presentSamples();
        this.rows = buildRows(metrics, lookup);
        clampScroll(Minecraft.getInstance().font);
    }

    /**
     * Builds the full sorted row table. {@link BiomeRarity#topBiomes} with an
     * {@code Integer.MAX_VALUE} limit yields exactly one row per counts entry
     * (count desc, name asc), so the biome-id list — sorted with the identical
     * comparator over the identical entry sequence — lines up index-for-index
     * with those rows. (RarityRow carries no id, which the click-to-highlight
     * wiring needs.)
     */
    private static List<ShareRow> buildRows(@Nullable RegionMetrics metrics,
                                            @Nullable IntFunction<BiomesList.BiomeEntry> lookup) {
        if (metrics == null || metrics.biomeCounts().isEmpty()) {
            return List.of();
        }
        IntFunction<String> names = id -> {
            BiomesList.BiomeEntry entry = lookup == null ? null : lookup.apply(id & 0xFFFF);
            return entry != null ? entry.name() : null; // topBiomes falls back to "biome_<id>"
        };
        List<BiomeRarity.RarityRow> sorted = BiomeRarity.topBiomes(metrics.biomeCounts(), names,
                metrics.presentSamples(), Integer.MAX_VALUE);
        List<Map.Entry<Short, Long>> ids = new ArrayList<>(metrics.biomeCounts().entrySet());
        ids.sort(Comparator.comparingLong((Map.Entry<Short, Long> entry) -> entry.getValue()).reversed()
                .thenComparing(entry -> resolvedName(names, entry.getKey())));
        List<ShareRow> rows = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            BiomeRarity.RarityRow row = sorted.get(i);
            short id = ids.get(i).getKey();
            rows.add(new ShareRow(id, row.name(), row.count(), row.sharePercent(), row.stars(),
                    colorOf(lookup, id)));
        }
        return List.copyOf(rows);
    }

    /** Name resolver with the same "biome_<id>" fallback BiomeRarity applies. */
    private static String resolvedName(IntFunction<String> names, short id) {
        String name = names.apply(id & 0xFFFF);
        return name == null || name.isBlank() ? "biome_" + id : name;
    }

    private static int colorOf(@Nullable IntFunction<BiomesList.BiomeEntry> lookup, short id) {
        BiomesList.BiomeEntry entry = lookup == null ? null : lookup.apply(id & 0xFFFF);
        // entry.color() is stored without the alpha byte (the biome lists draw
        // it through WorldPreview.nativeColor), so force an opaque ARGB here.
        return entry != null ? entry.color() | 0xFF000000 : UNKNOWN_BIOME_COLOR;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xAA101418);
        graphics.drawString(font, TITLE, getX() + 6, getY() + 6, TEXT);

        if (rows.isEmpty()) {
            PanelRenderer.emptyHint(graphics, font, getX(), getY(), width, height, EMPTY_HINT);
            return;
        }

        // Diversity summary (single line above the stacked bars).
        if (insights != null) {
            Component diversity = Component.translatable("world_preview.analysis.diversity",
                    String.format(Locale.ROOT, "%.2f", insights.shannonDiversity()),
                    String.format(Locale.ROOT, "%.1f", insights.effectiveBiomeCount()));
            graphics.drawString(font, diversity, getX() + 6, getY() + 20, TEXT_SOFT);
        }

        int barX = getX() + 6;
        int barW = Math.max(0, width - 12);

        // (a) 100% stacked bar of the top <=10 biomes: the gray slot fill is
        // the remainder — everything beyond the top-10 (and any samples that
        // carry no biome id) stays gray.
        int biomeBarY = getY() + 32;
        int barRight = barX + barW;
        graphics.fill(barX, biomeBarY, barRight, biomeBarY + 8, REMAINDER_COLOR);
        if (totalSamples > 0 && barW > 0) {
            long cum = 0;
            int segments = Math.min(STACKED_BAR_ROWS, rows.size());
            for (int i = 0; i < segments; i++) {
                ShareRow row = rows.get(i);
                long next = cum + row.count();
                int x0 = barX + (int) (cum * barW / totalSamples);
                int x1 = Math.min(barRight, barX + (int) (next * barW / totalSamples));
                if (x1 > x0) {
                    graphics.fill(x0, biomeBarY, x1, biomeBarY + 8, row.argbColor());
                }
                cum = next;
            }
        }

        // (b) Terrain-category stacked bar + one legend row of chips. Same
        // remainder-slot treatment for rounding gaps / unclassified samples.
        // The compact layout drops both: its viewport cannot afford the
        // header, and the biome bar above stays meaningful on its own.
        if (insights != null && !compactLayout(font)) {
            int terrainBarY = getY() + 44;
            graphics.fill(barX, terrainBarY, barRight, terrainBarY + 8, REMAINDER_COLOR);
            long classified = insights.classifiedSamples();
            if (classified > 0 && barW > 0) {
                long cum = 0;
                for (Map.Entry<TerrainCategory, Long> entry : insights.terrainCounts().entrySet()) {
                    long count = entry.getValue();
                    if (count <= 0) {
                        continue;
                    }
                    long next = cum + count;
                    int x0 = barX + (int) (cum * barW / classified);
                    int x1 = Math.min(barRight, barX + (int) (next * barW / classified));
                    if (x1 > x0) {
                        graphics.fill(x0, terrainBarY, x1, terrainBarY + 8, entry.getKey().argbColor());
                    }
                    cum = next;
                }
            }
            for (LegendItem item : layoutLegend(font)) {
                // 5px chip with a 1px black outline (TerrainExportScreen legend pattern).
                graphics.fill(item.x(), item.y() + 2, item.x() + 5, item.y() + 7, item.category().argbColor());
                graphics.fill(item.x(), item.y() + 2, item.x() + 5, item.y() + 3, 0xFF000000);
                graphics.fill(item.x(), item.y() + 6, item.x() + 5, item.y() + 7, 0xFF000000);
                graphics.fill(item.x(), item.y() + 2, item.x() + 1, item.y() + 7, 0xFF000000);
                graphics.fill(item.x() + 4, item.y() + 2, item.x() + 5, item.y() + 7, 0xFF000000);
                graphics.drawString(font, item.label(), item.x() + 8, item.y(), TEXT_SOFT);
            }
        }

        // (c) Scrollable full biome table, clipped to the list area.
        int listTop = listTop(font);
        int listBottom = listBottom();
        tickScroll(mouseY, font);
        clampScroll(font);
        graphics.enableScissor(getX(), listTop, getX() + width, listBottom);
        int firstRow = Math.max(0, (int) Math.floor(renderOffset));
        for (int i = firstRow; i < rows.size(); i++) {
            int rowY = listTop + (int) Math.round((i - renderOffset) * ROW_HEIGHT);
            if (rowY >= listBottom) {
                break;
            }
            ShareRow row = rows.get(i);
            if (selected != null && selected.shortValue() == row.biomeId()) {
                graphics.fill(getX() + 2, rowY, getX() + width - 2, rowY + ROW_HEIGHT - 1, SELECTED_BACKGROUND);
            }
            int rowX = getX() + 4;
            graphics.fill(rowX, rowY + 2, rowX + 2, rowY + 14, row.argbColor());
            graphics.drawString(font, row.name(), rowX + 5, rowY + 4, TEXT);
            String share = String.format(Locale.ROOT, "%.1f%%", row.sharePercent());
            int shareX = getX() + width - 6 - font.width(share);
            graphics.drawString(font, share, shareX, rowY + 4, TEXT_SOFT);
            if (row.stars() > 0) {
                String stars = "★".repeat(row.stars());
                graphics.drawString(font, stars, shareX - 4 - font.width(stars), rowY + 4, TEXT_STARS);
            }
        }
        graphics.disableScissor();

        // Scrollbar: track + thumb on the list's right edge, only when the
        // table overflows the viewport. The thumb mirrors the visible range;
        // the strip is interactive (see mouseClicked/mouseDragged) and shows
        // the same cursor feedback as the vanilla AbstractScrollArea.
        int trackLen = listBottom - listTop;
        int content = rows.size() * ROW_HEIGHT;
        if (content > trackLen && trackLen > 0) {
            int trackX = getX() + width - 1 - SCROLLBAR_BAR_WIDTH;
            graphics.fill(trackX, listTop, trackX + SCROLLBAR_BAR_WIDTH, listBottom, REMAINDER_COLOR);
            int thumbHeight = Math.max(4, trackLen * trackLen / content);
            // scrollOffset counts rows while trackLen is pixels: convert via
            // maxScrollRows, or the thumb crawls at a sixteenth of the cursor.
            int thumbY = listTop + (int) Math.round((trackLen - thumbHeight) * renderOffset
                    / Math.max(1, maxScrollRows(font)));
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_BAR_WIDTH, thumbY + thumbHeight, SCROLLBAR_THUMB);
            if (isOverScrollbar(mouseX, mouseY, font)) {
                graphics.requestCursor(scrolling ? CursorTypes.RESIZE_NS : CursorTypes.POINTING_HAND);
            }
        }
    }
    /**
     * Positions the legend chips (only categories present in the data, in
     * enum order — UNKNOWN stays last), wrapping to the next 12px row at the
     * panel's right edge. Shared by the render pass and the list geometry.
     */
    private List<LegendItem> layoutLegend(Font font) {
        List<LegendItem> items = new ArrayList<>();
        if (insights == null) {
            return items;
        }
        int startX = getX() + 6;
        int maxRight = getX() + width - 6;
        int curX = startX;
        int rowY = getY() + 55;
        for (Map.Entry<TerrainCategory, Long> entry : insights.terrainCounts().entrySet()) {
            Long count = entry.getValue();
            if (count == null || count <= 0) {
                continue;
            }
            Component label = Component.translatable(
                    "world_preview.terrain_export.category." + entry.getKey().name().toLowerCase(Locale.ROOT));
            int itemW = 5 + 3 + font.width(label);
            if (curX != startX && curX + itemW > maxRight) {
                curX = startX;
                rowY += LEGEND_ROW_HEIGHT;
            }
            items.add(new LegendItem(entry.getKey(), curX, rowY, label));
            curX += itemW + 6;
        }
        return items;
    }

    /**
     * Top of the scrollable list: below the legend (or below the stacked bars
     * when no insights yet). The compact layout pins it just below the biome
     * bar instead.
     */
    private int listTop(Font font) {
        return compactLayout(font) ? getY() + COMPACT_LIST_TOP : normalListTop(font);
    }

    /** Normal-layout list top. */
    private int normalListTop(Font font) {
        List<LegendItem> items = layoutLegend(font);
        if (items.isEmpty()) {
            return getY() + 56;
        }
        int legendRows = (items.get(items.size() - 1).y() - (getY() + 55)) / LEGEND_ROW_HEIGHT + 1;
        return getY() + 55 + legendRows * LEGEND_ROW_HEIGHT + 4;
    }

    /**
     * True when the normal header would leave the table less than one row:
     * the terrain bar and the legend are dropped and the list starts right
     * below the biome bar. A small-window safety net; the vanilla 240px
     * automatic-scale floor keeps the normal layout usable otherwise.
     */
    private boolean compactLayout(Font font) {
        return getHeight() - normalListTop(font) < ROW_HEIGHT;
    }

    private int listBottom() {
        return getY() + getHeight() - 4;
    }

    private void clampScroll(Font font) {
        scrollOffset = Math.min(maxScrollRows(font), Math.max(0, scrollOffset));
    }

    /**
     * Maximum scroll target in rows. scrollOffset counts rows while the
     * viewport and content heights are pixels: clamps and thumb geometry
     * must convert between the two, or the thumb crawls at a sixteenth of
     * the cursor speed and the list can scroll past its last row.
     */
    private int maxScrollRows(Font font) {
        int visible = Math.max(0, listBottom() - listTop(font));
        return Math.max(0, (rows.size() * ROW_HEIGHT - visible) / ROW_HEIGHT);
    }

    /**
     * Per-render-frame scroll animation, ticked from the render pass so it
     * keeps running while the mouse is still. Two jobs: ease the rendered
     * offset toward the target (wheel steps glide instead of jumping), and
     * while a middle-press autoscroll is active move the rendered position
     * continuously at a speed proportional to the cursor's distance from
     * the press point — browser autoscroll feel, same direction (cursor
     * below the anchor scrolls down), pixel-smooth at any speed.
     */
    private void tickScroll(double mouseY, Font font) {
        long now = System.currentTimeMillis();
        long dt = lastFrameMs < 0 ? 16 : Math.min(100, now - lastFrameMs);
        lastFrameMs = now;
        if (panActive && !rows.isEmpty()) {
            double displacement = mouseY - panAnchorY;
            double speed = 0.0;
            if (Math.abs(displacement) > PAN_DEADZONE_PX) {
                speed = Math.min(
                        PAN_MAX_ROWS_PER_SEC,
                        (displacement - Math.signum(displacement) * PAN_DEADZONE_PX) * PAN_ROWS_PER_SEC_PER_PX);
            }
            // Integrate the velocity straight into the rendered position, so
            // rows slide pixel by pixel and slow autoscroll stays as smooth
            // as fast; the integer target follows the rendered position.
            renderOffset = Math.min(maxScrollRows(font),
                    Math.max(0, renderOffset + speed * dt / 1000.0));
            scrollOffset = (int) Math.round(renderOffset);
        } else if (scrolling) {
            renderOffset = scrollOffset;
        } else {
            renderOffset += (scrollOffset - renderOffset) * (1.0 - Math.exp(-dt / SCROLL_EASE_MS));
            if (Math.abs(scrollOffset - renderOffset) < 0.01) {
                renderOffset = scrollOffset;
            }
            int maxRows = maxScrollRows(font);
            renderOffset = Math.min(maxRows, Math.max(0, renderOffset));
        }
    }

    /**
     * A hit target only while visible: the 1.21.11+ pipeline resolves a
     * single click/scroll winner through {@code isMouseOver}, whose
     * vanilla default ignores {@code visible}, so a hidden panel would
     * otherwise swallow clicks and scrolls meant for the widgets sharing
     * this rectangle.
     */
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible && super.isMouseOver(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!visible || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        double mouseX = event.x();
        double mouseY = event.y();
        if (!isMouseOver(mouseX, mouseY) || rows.isEmpty()) {
            return false;
        }
        Font font = Minecraft.getInstance().font;
        // Pressing the scrollbar strip starts a thumb drag (vanilla
        // AbstractScrollArea semantics). Returning true makes the container
        // focus this panel and set the dragging flag, so the following left
        // drags route back into {@link #mouseDragged} and the release into
        // {@link #mouseReleased}.
        if (overflows(font) && isOverScrollbar(mouseX, mouseY, font)) {
            // Jump the thumb under the cursor, then drag it 1:1.
            scrolling = true;
            int trackLen = listBottom() - listTop(font);
            int thumbHeight = Math.max(4, trackLen * trackLen / (rows.size() * ROW_HEIGHT));
            int maxRows = maxScrollRows(font);
            double target = (mouseY - listTop(font) - thumbHeight / 2.0) * maxRows
                    / Math.max(1, trackLen - thumbHeight);
            scrollOffset = (int) Math.round(Math.min(maxRows, Math.max(0, target)));
            scrollDragRows = scrollOffset;
            renderOffset = scrollOffset;
            return true;
        }
        int listTop = listTop(font);
        int listBottom = listBottom();
        if (mouseY < listTop || mouseY >= listBottom) {
            return false;
        }
        int index = (int) Math.floor(renderOffset) + (int) ((mouseY - listTop) / ROW_HEIGHT);
        if (index < 0 || index >= rows.size()) {
            return false;
        }
        // Rows partially clipped by the scissor rectangle stay unclickable.
        int rowY = listTop + (index - scrollOffset) * ROW_HEIGHT;
        if (rowY + ROW_HEIGHT > listBottom) {
            return false;
        }
        ShareRow row = rows.get(index);
        boolean deselect = selected != null && selected.shortValue() == row.biomeId();
        if (rowAction != null) {
            rowAction.onRowClick(deselect ? null : row.biomeId());
        } else {
            setSelected(deselect ? null : row.biomeId());
        }
        return true;
    }

    /**
     * Drags the scrollbar thumb. The container only relays drag events for
     * the primary button while the dragging flag is set, which the press in
     * {@link #mouseClicked} arranged; the per-frame {@code dragY} accumulates
     * into a sub-row offset so slow drags are not lost to rounding. Outside
     * the track the offset snaps to the nearest end (vanilla behavior).
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!visible || !scrolling || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        Font font = Minecraft.getInstance().font;
        int listTop = listTop(font);
        int listBottom = listBottom();
        int trackLen = listBottom - listTop;
        int thumbHeight = Math.max(4, trackLen * trackLen / (rows.size() * ROW_HEIGHT));
        int maxRows = maxScrollRows(font);
        if (event.y() < listTop) {
            scrollDragRows = 0;
        } else if (event.y() >= listBottom) {
            scrollDragRows = maxRows;
        } else {
            // Rows per gui pixel such that the thumb tracks the cursor 1:1.
            double rowsPerPx = maxRows / (double) Math.max(1, trackLen - thumbHeight);
            scrollDragRows += dragY * rowsPerPx;
        }
        scrollDragRows = Math.min(maxRows, Math.max(0, scrollDragRows));
        scrollOffset = (int) Math.round(scrollDragRows);
        renderOffset = scrollOffset;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (scrolling && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            scrolling = false;
            return true;
        }
        return false;
    }

    /** True when the table overflows the viewport and the scrollbar shows. */
    private boolean overflows(Font font) {
        int visible = Math.max(0, listBottom() - listTop(font));
        return rows.size() * ROW_HEIGHT > visible && visible > 0;
    }

    /** The 6px hit strip covering the rendered scrollbar on the list's right edge. */
    private boolean isOverScrollbar(double mouseX, double mouseY, Font font) {
        int listTop = listTop(font);
        int listBottom = listBottom();
        return mouseX >= getX() + width - SCROLLBAR_HIT_WIDTH && mouseX <= getX() + width
                && mouseY >= listTop && mouseY < listBottom;
    }

    /**
     * Starts a middle-button autoscroll anchored at the given mouse Y.
     * Returns false when the table cannot scroll (hidden or empty), so the
     * caller can pass the press through. While the button is held, the
     * render pass keeps scrolling at a speed proportional to the cursor's
     * distance from the anchor — browser autoscroll feel. The screen routes
     * the release: vanilla's container does not forward non-primary
     * releases to the focused child.
     */
    public boolean beginScrollPan(double mouseY) {
        if (!visible || rows.isEmpty()) {
            return false;
        }
        panActive = true;
        panAnchorY = mouseY;
        return true;
    }

    /** Ends a middle-button autoscroll. */
    public void endScrollPan() {
        panActive = false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (!visible || rows.isEmpty() || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        int steps = (int) deltaY * WHEEL_STEP_ROWS;
        if (steps == 0 && deltaY != 0.0) {
            steps = deltaY > 0 ? WHEEL_STEP_ROWS : -WHEEL_STEP_ROWS;
        }
        if (panActive) {
            // The wheel takes over, like a browser: cancel the autoscroll
            // and apply the step to the position it leaves behind.
            endScrollPan();
        }
        Font font = Minecraft.getInstance().font;
        scrollOffset = Math.min(maxScrollRows(font), Math.max(0, scrollOffset - steps));
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
