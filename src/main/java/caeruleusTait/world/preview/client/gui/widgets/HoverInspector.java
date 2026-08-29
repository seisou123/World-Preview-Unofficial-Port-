package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.client.gui.PreviewDisplayDataProvider;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Hover-query and tooltip subsystem of {@link PreviewDisplay}.
 *
 * <p>Owns the spatial structure-hover grid (populated by the renderer while
 * drawing structure icons) and the mouse/center-keyed hover caches. Hover
 * world coordinates depend on both the cursor position and the map center
 * (including in-progress drag offsets), so the cache key covers both.
 */
class HoverInspector {

    /** Data for the currently hovered map position. */
    record HoverInfo(
            int blockX,
            int blockY,
            int blockZ,
            BiomesList.BiomeEntry entry,
            short height,
            double temperature,
            double humidity,
            double continentalness,
            double erosion,
            double depth,
            double weirdness,
            double pv
    ) {}

    record StructHoverHelperEntry(BoundingBox boundingBox, PreviewSection.PreviewStruct structure) {}

    private record StructHoverHelperCell(List<StructHoverHelperEntry> entries) {}

    private final PreviewDisplay host;

    private StructHoverHelperCell[] hoverGrid;
    private static final int HOVER_GRID_CELL_SIZE = 64;
    private int hoverGridWidth;
    private int hoverGridHeight;

    // --- Mouse / map-center cache for tooltip skip ---
    private double lastMouseX = -1;
    private double lastMouseZ = -1;
    private int lastHoverCenterX = Integer.MIN_VALUE;
    private int lastHoverCenterY = Integer.MIN_VALUE;
    private int lastHoverCenterZ = Integer.MIN_VALUE;
    private HoverInfo cachedHoverInfo = null;
    private List<StructHoverHelperEntry> cachedStructInfos = null;

    HoverInspector(PreviewDisplay host) {
        this.host = host;
    }

    /** (Re-)creates the spatial grid; called whenever the texture is resized. */
    void resizeGrid(int texWidth, int texHeight) {
        hoverGridWidth = (texWidth / HOVER_GRID_CELL_SIZE) + 1;
        hoverGridHeight = (texHeight / HOVER_GRID_CELL_SIZE) + 1;
        hoverGrid = new StructHoverHelperCell[hoverGridWidth * hoverGridHeight];
        for (int i = 0; i < hoverGrid.length; ++i) {
            hoverGrid[i] = new StructHoverHelperCell(new ArrayList<>());
        }
    }

    /** Clears per-cell entries; called when the renderer rebuilds the frame. */
    void clearGridEntries() {
        for (int i = 0; i < hoverGrid.length; i++) {
            hoverGrid[i].entries.clear();
        }
    }

    /** Registers a rendered structure icon for hover hit tests (texture-pixel space). */
    void putStructEntry(int texX, int texZ, int texWidthIcon, int texHeightIcon,
                        PreviewSection.PreviewStruct structure) {
        int cellX = Math.max(0, Math.min(hoverGridWidth - 1, texX / HOVER_GRID_CELL_SIZE));
        int cellZ = Math.max(0, Math.min(hoverGridHeight - 1, texZ / HOVER_GRID_CELL_SIZE));
        hoverGrid[(cellX * hoverGridHeight) + cellZ].entries.add(new StructHoverHelperEntry(
                new BoundingBox(texX, 0, texZ, texX + texWidthIcon, 0, texZ + texHeightIcon),
                structure
        ));
    }

    /** Drops cached hover results so the next frame re-queries storage. */
    void invalidateCache() {
        lastMouseX = -1;
        lastMouseZ = -1;
        lastHoverCenterX = Integer.MIN_VALUE;
        lastHoverCenterY = Integer.MIN_VALUE;
        lastHoverCenterZ = Integer.MIN_VALUE;
        cachedHoverInfo = null;
        cachedStructInfos = null;
    }

    /**
     * Nearest rendered structure of the given type to {@code from}, based on
     * the structure icons drawn during the last render frame. Returns null
     * when no matching structure was rendered (cache not built or nothing in
     * view).
     */
    @Nullable
    BlockPos nearestStructureCenter(short structureId, BlockPos from) {
        List<StructHoverHelperEntry> infos = cachedStructInfos;
        if (infos == null || infos.isEmpty()) {
            return null;
        }
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (StructHoverHelperEntry entry : infos) {
            if (entry.structure().structureId() != structureId) {
                continue;
            }
            BlockPos center = entry.structure().center();
            long dx = (long) center.getX() - from.getX();
            long dz = (long) center.getZ() - from.getZ();
            double distSq = (double) dx * dx + (double) dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = center;
            }
        }
        return best;
    }

    /**
     * Hover queries must work while the user is dragging. AbstractWidget's
     * {@code isHovered} can flicker false during mouse-drag frames, which made
     * the biome/coords data bar vanish every other frame (whole-bar blink).
     */
    private boolean shouldShowHoverData(double mouseX, double mouseY) {
        return host.workManager().previewStorage() != null
                && (host.isClicked() || host.isHoveredFlag() || host.widgetIsMouseOver(mouseX, mouseY));
    }

    HoverInfo hoveredBiome(double mouseX, double mouseY) {
        if (!shouldShowHoverData(mouseX, mouseY)) {
            return null;
        }
        final Minecraft minecraft = host.minecraft();
        int guiScale = (int) minecraft.getWindow().getGuiScale();
        final double scaleBlockPos = host.scaleBlockPos();
        final int texWidth = host.getTexWidth();
        final int texHeight = host.getTexHeight();

        final BlockPos center = host.center();
        final int xMin = center.getX() - (int) (texWidth * scaleBlockPos / 2.0) - 1;
        final int zMin = center.getZ() - (int) (texHeight * scaleBlockPos / 2.0) - 1;

        final int xPos = (int) ((mouseX - host.getX()) * guiScale * scaleBlockPos);
        final int zPos = (int) ((mouseY - host.getY()) * guiScale * scaleBlockPos);

        int quartX = QuartPos.fromBlock(xMin + xPos);
        int quartY = QuartPos.fromBlock(center.getY());
        int quartZ = QuartPos.fromBlock(zMin + zPos);

        // Batch query: fetch biome, height, and all 6 noise channels in a
        // single call to reduce synchronized lock overhead from 8 acquisitions
        // down to 2 (one for the biome/noise y-layer, one for the height layer).
        final short[] batch = host.workManager().previewStorage().getBatchRawData4(quartX, quartY, quartZ);
        final short biome = batch[0];
        final short height = batch[1];
        final short temperature = batch[2];
        final short humidity = batch[3];
        final short continentalness = batch[4];
        final short erosion = batch[5];
        final short depth = batch[6];
        final short weirdness = batch[7];

        if (biome < 0) {
            return new HoverInfo(
                    xMin + xPos, center.getY(), zMin + zPos, null, height,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN
            );
        }

        // Guard against invalid biome IDs that exceed the biome map range.
        // This can happen when storage contains stale data from a different
        // world context (e.g. after a candidate-seed analysis session).
        final var biomeEntry = biome < host.dataProvider().previewData().biomeId2BiomeData().length
                ? host.dataProvider().biome4Id(biome) : null;

        if (temperature == Short.MIN_VALUE && humidity == Short.MIN_VALUE && continentalness == Short.MIN_VALUE && erosion == Short.MIN_VALUE && depth == Short.MIN_VALUE && weirdness == Short.MIN_VALUE) {
            return new HoverInfo(
                    xMin + xPos, center.getY(), zMin + zPos, biomeEntry, height,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN
            );
        } else {
            return new HoverInfo(
                    xMin + xPos, center.getY(), zMin + zPos, biomeEntry, height,
                    temperature / 1.0 / Short.MAX_VALUE,
                    humidity / 1.0 / Short.MAX_VALUE,
                    continentalness / 0.5 / Short.MAX_VALUE,
                    erosion / 1.0 / Short.MAX_VALUE,
                    depth / 0.5 / Short.MAX_VALUE,
                    weirdness / 0.75 / Short.MAX_VALUE,
                    NoiseRouterData.peaksAndValleys(Math.min(1.0f, Math.max(-1.0f, weirdness / 0.75f / Short.MAX_VALUE)))
            );
        }
    }

    private List<StructHoverHelperEntry> hoveredStructures(double mouseX, double mouseY) {
        if (!shouldShowHoverData(mouseX, mouseY)) {
            return List.of();
        }

        int guiScale = (int) host.minecraft().getWindow().getGuiScale();
        final int xTexPos = (int) (mouseX - host.getX()) * guiScale;
        final int zTexPos = (int) (mouseY - host.getY()) * guiScale;

        final int xGridPos = xTexPos / HOVER_GRID_CELL_SIZE;
        final int zGridPos = zTexPos / HOVER_GRID_CELL_SIZE;

        final List<StructHoverHelperEntry> res = new ArrayList<>();
        for (int x = xGridPos - 1; x <= xGridPos + 1; ++x) {
            for (int z = zGridPos - 1; z <= zGridPos + 1; ++z) {
                if (x < 0 || x >= hoverGridWidth || z < 0 || z >= hoverGridHeight) {
                    continue;
                }
                StructHoverHelperCell cell = hoverGrid[(x * hoverGridHeight) + z];
                for (var entry : cell.entries) {
                    if (entry.boundingBox().isInside(xTexPos, 0, zTexPos)) {
                        res.add(entry);
                    }
                }
            }
        }
        return res;
    }

    private static String nameFormatter(String s) {
        int idx = s.indexOf(':');
        if (idx < 0) {
            return "§e" + s + "§r";
        }
        return String.format("§5§o%s§r§5:%s§r", s.substring(0, idx), s.substring(idx + 1));
    }

    /**
     * Split a Component into multiple Components by newline characters.
     * <p>
     * MC 1.21.11's {@code setTooltipForNextFrame(Font, Component, int, int)}
     * calls {@code Component.getVisualOrderText()} which flattens the entire
     * component into a single {@code FormattedCharSequence}.  Newline
     * characters in that path are rendered as literal "LF" glyphs instead
     * of line breaks.
     * <p>
     * {@code setComponentTooltipForNextFrame(Font, List<Component>, int, int)}
     * renders each list element on its own line, so we resolve the component
     * to its string form, split on \n, and wrap each piece back into a
     * Component literal.
     */
    private static List<Component> splitComponentByNewline(Component component) {
        String text = component.getString();
        String[] parts = text.split("\n", -1);
        List<Component> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                result.add(Component.empty());
            } else {
                result.add(Component.literal(part));
            }
        }
        return result;
    }

    void updateTooltip(GuiGraphics guiGraphics, double mouseX, double mouseY) {
        // Cache hover info — re-query when the mouse moves OR the map center
        // changes (including in-progress drag offsets via center()). Hover
        // world coordinates depend on both; mouse-only cache caused stale or
        // blinking tooltips while panning with a still cursor.
        final BlockPos hoverCenter = host.center();
        if (mouseX != lastMouseX
                || mouseY != lastMouseZ
                || hoverCenter.getX() != lastHoverCenterX
                || hoverCenter.getY() != lastHoverCenterY
                || hoverCenter.getZ() != lastHoverCenterZ) {
            lastMouseX = mouseX;
            lastMouseZ = mouseY;
            lastHoverCenterX = hoverCenter.getX();
            lastHoverCenterY = hoverCenter.getY();
            lastHoverCenterZ = hoverCenter.getZ();
            cachedHoverInfo = hoveredBiome(mouseX, mouseY);
            cachedStructInfos = hoveredStructures(mouseX, mouseY);
        }

        final HoverInfo hoverInfo = cachedHoverInfo;
        final List<StructHoverHelperEntry> structuresInfos = cachedStructInfos != null ? cachedStructInfos : List.of();
        if (hoverInfo == null && structuresInfos.isEmpty()) {
            return;
        }

        String blockPosTemplate = "§3X=§b%d§r §3Y=§b%d§r §3Z=§b%d§r";
        Component tooltipComponent;
        WorldPreviewConfig config = host.config();

        if (!structuresInfos.isEmpty()) {
            var structure = structuresInfos.get(0).structure();
            var structEntry = host.dataProvider().structure4Id(structure.structureId());
            String structName = structEntry == null ? "<N/A>" : nameFormatter(structEntry.name());
            if (config.showControls) {
                tooltipComponent = Component.translatable(
                        "world_preview.preview-display.struct.tooltip.controls",
                        structName,
                        blockPosTemplate.formatted(structure.center().getX(), structure.center().getY(), structure.center().getZ())
                );
            } else {
                tooltipComponent = Component.translatable(
                        "world_preview.preview-display.struct.tooltip",
                        structName,
                        blockPosTemplate.formatted(structure.center().getX(), structure.center().getY(), structure.center().getZ())
                );
            }
        } else {
            // Hover panel: biome + coords + height only (noise values removed).
            // When controls are enabled, only the zoom hint is appended via lang.
            String height = hoverInfo.height > Short.MIN_VALUE ? String.format("§b%d§r", hoverInfo.height) : "§7<N/A>§r";

            if (config.showControls) {
                tooltipComponent = Component.translatable(
                        "world_preview.preview-display.tooltip.controls",
                        nameFormatter(hoverInfo.entry == null ? "<N/A>" : hoverInfo.entry.name()),
                        blockPosTemplate.formatted(hoverInfo.blockX, hoverInfo.blockY, hoverInfo.blockZ),
                        height
                );
            } else {
                tooltipComponent = Component.translatable(
                        "world_preview.preview-display.tooltip",
                        nameFormatter(hoverInfo.entry == null ? "<N/A>" : hoverInfo.entry.name()),
                        blockPosTemplate.formatted(hoverInfo.blockX, hoverInfo.blockY, hoverInfo.blockZ),
                        height
                );
            }
        }

        // Draw the hover data bar ourselves. Minecraft's tooltip renderer uses
        // the full screen as its layout surface, so a widget scissor does not
        // reliably keep it inside this map. A local panel gives us hard bounds.
        renderHoverPanel(guiGraphics, tooltipComponent, mouseX, mouseY);
    }

    private void renderHoverPanel(GuiGraphics guiGraphics, Component tooltipComponent,
                                  double mouseX, double mouseY) {
        final Minecraft minecraft = host.minecraft();
        final float textScale = 0.8F;
        final int panelPadding = 6;
        final int mapLeft = host.getX() + 4;
        final int mapTop = host.getY() + 4;
        final int mapRight = host.getX() + host.widgetWidth() - 4;
        final int mapBottom = host.getY() + host.widgetHeight() - 4;
        final int availableWidth = Math.max(1, mapRight - mapLeft - panelPadding * 2);
        final int textWidth = Math.max(1, (int) (availableWidth / textScale));

        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : splitComponentByNewline(tooltipComponent)) {
            List<FormattedCharSequence> wrapped = minecraft.font.split(line, textWidth);
            if (wrapped.isEmpty()) {
                lines.add(FormattedCharSequence.EMPTY);
            } else {
                lines.addAll(wrapped);
            }
        }
        if (lines.isEmpty()) {
            return;
        }

        int textWidthMax = 0;
        for (FormattedCharSequence line : lines) {
            textWidthMax = Math.max(textWidthMax, minecraft.font.width(line));
        }
        int panelWidth = Math.min((int) Math.ceil(textWidthMax * textScale) + panelPadding * 2,
                mapRight - mapLeft);
        final int lineHeight = Math.max(1, (int) Math.ceil(minecraft.font.lineHeight * textScale));
        int panelHeight = Math.min(lines.size() * lineHeight + panelPadding * 2, mapBottom - mapTop);

        final int cursorX = (int) mouseX;
        final int cursorY = (int) mouseY;
        final int cursorGap = 12;

        // Prefer the lower-right of the cursor, but flip to the opposite side
        // when that direction would leave the map. This keeps the panel moving
        // with the cursor instead of pinning it to an edge while dragging.
        int panelX = cursorX + cursorGap;
        if (panelX + panelWidth > mapRight) {
            panelX = cursorX - cursorGap - panelWidth;
        }
        int panelY = cursorY + cursorGap;
        if (panelY + panelHeight > mapBottom) {
            panelY = cursorY - cursorGap - panelHeight;
        }

        // Final safety clamp handles a panel larger than the available map area
        // or a cursor temporarily outside the widget during a drag.
        panelX = Math.max(mapLeft, Math.min(panelX, mapRight - panelWidth));
        panelY = Math.max(mapTop, Math.min(panelY, mapBottom - panelHeight));

        guiGraphics.enableScissor(host.getX(), host.getY(), host.getX() + host.widgetWidth(), host.getY() + host.widgetHeight());
        try {
            guiGraphics.fill(panelX - 1, panelY - 1,
                    panelX + panelWidth + 1, panelY + panelHeight + 1, 0xFF666666);
            guiGraphics.fill(panelX, panelY,
                    panelX + panelWidth, panelY + panelHeight, 0xF0100010);

            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(panelX + panelPadding, panelY + panelPadding);
            guiGraphics.pose().scale(textScale, textScale);
            int lineY = 0;
            for (FormattedCharSequence line : lines) {
                if ((lineY * textScale) >= panelHeight - panelPadding) {
                    break;
                }
                guiGraphics.drawString(minecraft.font, line, 0, lineY, 0xFFFFFFFF, false);
                lineY += minecraft.font.lineHeight;
            }
            guiGraphics.pose().popMatrix();
        } finally {
            guiGraphics.disableScissor();
        }
    }
}
