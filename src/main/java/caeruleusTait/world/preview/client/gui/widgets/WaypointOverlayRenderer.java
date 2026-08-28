// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.domain.waypoint.Waypoint;
import caeruleusTait.world.preview.domain.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * Draws user waypoints on top of the preview map and converts waypoint block
 * coordinates to screen space using the display's viewport math.
 */
public class WaypointOverlayRenderer implements PreviewDisplay.WaypointRenderer {

    /** Screen-space radius (GUI px) in which a click counts as hitting the pin. */
    public static final double HIT_RADIUS_PX = 6.0;

    private final PreviewDisplay display;
    private final WaypointStore store;
    private final Supplier<Long> seedSupplier;
    private final Supplier<@Nullable String> dimensionSupplier;

    public WaypointOverlayRenderer(PreviewDisplay display, WaypointStore store,
                                   Supplier<Long> seedSupplier,
                                   Supplier<@Nullable String> dimensionSupplier) {
        this.display = display;
        this.store = store;
        this.seedSupplier = seedSupplier;
        this.dimensionSupplier = dimensionSupplier;
    }

    private List<Waypoint> currentWaypoints() {
        Long seed = seedSupplier.get();
        String dimension = dimensionSupplier.get();
        if (seed == null || dimension == null) {
            return List.of();
        }
        return store.forSeedDimension(seed, dimension);
    }

    /** Screen position (GUI px) of a block coordinate, or null when off the map. */
    public @Nullable BlockPos screenPos(int blockX, int blockZ) {
        return display.blockToScreen(blockX, blockZ);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int xMin, int yMin, int xMax, int yMax) {
        Font font = Minecraft.getInstance().font;
        BlockPos center = display.center();
        for (Waypoint waypoint : currentWaypoints()) {
            BlockPos pos = screenPos(waypoint.x(), waypoint.z());
            if (pos == null) continue;
            int sx = pos.getX();
            int sz = pos.getZ();
            if (sx < xMin || sx > xMax || sz < yMin || sz > yMax) continue;

            // Pin: 7x7 diamond-ish marker with dark border
            guiGraphics.fill(sx - 3, sz - 3, sx + 4, sz + 4, 0xFF000000);
            guiGraphics.fill(sx - 2, sz - 2, sx + 3, sz + 3, waypoint.color());

            // Label: name + distance from the map center
            long dx = (long) waypoint.x() - center.getX();
            long dz = (long) waypoint.z() - center.getZ();
            int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
            String label = waypoint.name() + " §7[" + distance + "m]§r";
            int textWidth = font.width(label);
            int textX = Math.min(Math.max(sx + 6, xMin), xMax - textWidth - 2);
            int textY = Math.min(Math.max(sz - 4, yMin), yMax - font.lineHeight - 2);
            guiGraphics.fill(textX - 2, textY - 1, textX + textWidth + 2, textY + font.lineHeight + 1, 0x99000000);
            guiGraphics.drawString(font, label, textX, textY, 0xFFFFFFFF);
        }
    }

    /**
     * Finds the waypoint whose pin is nearest to the given screen position
     * within {@link #HIT_RADIUS_PX}, in current viewport coordinates.
     */
    public @Nullable Waypoint waypointAt(double mouseX, double mouseY) {
        Waypoint best = null;
        double bestDistSq = HIT_RADIUS_PX * HIT_RADIUS_PX;
        for (Waypoint waypoint : currentWaypoints()) {
            BlockPos pos = screenPos(waypoint.x(), waypoint.z());
            if (pos == null) continue;
            double dx = pos.getX() - mouseX;
            double dz = pos.getZ() - mouseY;
            double distSq = dx * dx + dz * dz;
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = waypoint;
            }
        }
        return best;
    }
}
