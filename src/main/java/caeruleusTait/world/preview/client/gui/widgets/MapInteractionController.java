package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.domain.waypoint.Waypoint;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Input handling (drag-pan, zoom/Y-scroll, keyboard pan, spawn-pin placement,
 * right-click coordinate copy) for {@link PreviewDisplay}.
 */
class MapInteractionController {

    /** Drag threshold below which a click is treated as a biome select instead of a pan. */
    private static final double CLICK_DRAG_THRESHOLD = 4;

    private final PreviewDisplay host;

    private boolean clicked = false;
    private double totalDragX = 0;
    private double totalDragZ = 0;
    private double clickMouseX = 0;
    private double clickMouseY = 0;

    // === Spawn Pin ===
    private boolean spawnPinMode = false;
    private BlockPos spawnPinPos = null;
    private java.util.function.Consumer<BlockPos> spawnPinCallback = null;

    // === Waypoints (v1.5) ===
    /** One-shot placement mode: the next left click places a waypoint. */
    private boolean waypointMode = false;
    private java.util.function.Consumer<BlockPos> waypointPlaceCallback = null;
    private java.util.function.Consumer<Waypoint> waypointDeleteCallback = null;

    // === Measure tool (v1.5) ===
    private boolean measureMode = false;
    private BlockPos measureA = null;
    private BlockPos measureB = null;

    MapInteractionController(PreviewDisplay host) {
        this.host = host;
    }

    // === Spawn pin API (delegated from the widget) ===

    void setSpawnPinMode(boolean enabled) {
        this.spawnPinMode = enabled;
    }

    boolean isSpawnPinMode() {
        return spawnPinMode;
    }

    BlockPos spawnPinPos() {
        return spawnPinPos;
    }

    void setSpawnPinPos(BlockPos pos) {
        this.spawnPinPos = pos;
    }

    void setSpawnPinCallback(java.util.function.Consumer<BlockPos> callback) {
        this.spawnPinCallback = callback;
    }

    // === Waypoint API ===

    void setWaypointMode(boolean enabled) {
        this.waypointMode = enabled;
    }

    boolean isWaypointMode() {
        return waypointMode;
    }

    void setWaypointPlaceCallback(java.util.function.Consumer<BlockPos> callback) {
        this.waypointPlaceCallback = callback;
    }

    void setWaypointDeleteCallback(java.util.function.Consumer<Waypoint> callback) {
        this.waypointDeleteCallback = callback;
    }

    // === Measure tool API ===

    void setMeasureMode(boolean enabled) {
        this.measureMode = enabled;
        if (!enabled) {
            measureA = null;
            measureB = null;
        }
    }

    boolean isMeasureMode() {
        return measureMode;
    }

    BlockPos measurePointA() {
        return measureA;
    }

    BlockPos measurePointB() {
        return measureB;
    }

    // === State queries / mutation used by the widget ===

    boolean isClicked() {
        return clicked;
    }

    double totalDragX() {
        return totalDragX;
    }

    double totalDragZ() {
        return totalDragZ;
    }

    /** Drops any in-progress press/drag (used when a screen change interrupts input). */
    void resetInteractionState() {
        clicked = false;
        totalDragX = 0;
        totalDragZ = 0;
    }

    /**
     * Safety net for missed release events: while a drag is active, poll the
     * raw GLFW button state and end the drag when both buttons are up.
     */
    void endDragIfButtonsReleased() {
        if (!clicked) {
            return;
        }
        long window = host.minecraft().getWindow().handle();
        boolean leftPressed = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightPressed = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (!leftPressed && !rightPressed) {
            clicked = false;
            totalDragX = 0;
            totalDragZ = 0;
        }
    }

    // === Event handlers ===

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (spawnPinMode && event.button() == 1 && host.widgetIsMouseOver(event.x(), event.y())) {
            removeSpawnPin();
            host.playDownSound();
            return true;
        }
        return false;
    }

    void onClick(MouseButtonEvent event, boolean doubleClick) {
        // Fix: set focus so Screen dispatches onDrag to this widget
        if (host.minecraft().screen != null) {
            host.minecraft().screen.setFocused(host);
        }

        // Spawn pin placement
        if (spawnPinMode && host.widgetIsMouseOver(event.x(), event.y())) {
            if (event.button() == 0) {
                BlockPos pos = host.screenToBlock(event.x(), event.y());
                if (pos != null) {
                    spawnPinPos = pos;
                    if (spawnPinCallback != null) {
                        spawnPinCallback.accept(pos);
                    }
                    host.playDownSound();
                }
                return;
            } else if (event.button() == 1) {
                removeSpawnPin();
                host.playDownSound();
                return;
            }
        }

        // Waypoint placement (one-shot): left click places and exits the
        // mode; right click deletes the waypoint under the cursor.
        if (waypointMode && host.widgetIsMouseOver(event.x(), event.y())) {
            if (event.button() == 0) {
                BlockPos pos = host.screenToBlock(event.x(), event.y());
                if (pos != null && waypointPlaceCallback != null) {
                    waypointPlaceCallback.accept(pos);
                }
                waypointMode = false;
                host.playDownSound();
                return;
            } else if (event.button() == 1) {
                Waypoint hit = host.waypointAt(event.x(), event.y());
                if (hit != null && waypointDeleteCallback != null) {
                    waypointDeleteCallback.accept(hit);
                }
                host.playDownSound();
                return;
            }
        }

        // Measure tool: first click sets point A, second click sets point B
        // (restarting from A on the next click). Right click clears.
        if (measureMode && host.widgetIsMouseOver(event.x(), event.y())) {
            if (event.button() == 0) {
                BlockPos pos = host.screenToBlock(event.x(), event.y());
                if (pos != null) {
                    if (measureA == null || measureB != null) {
                        measureA = pos;
                        measureB = null;
                    } else {
                        measureB = pos;
                    }
                }
                return;
            } else if (event.button() == 1) {
                measureA = null;
                measureB = null;
                return;
            }
        }

        clicked = true;
        host.throttle.touchDragRenderTimer();
        clickMouseX = event.x();
        clickMouseY = event.y();

        // Copy coordinates on right click:
        // - Ctrl+Right click (or always if height unavailable): plain "x y z" / "x ~ z"
        // - Right click: /tp @s x y z  (game-ready teleport)
        if (event.button() == 1 && host.widgetIsMouseOver(event.x(), event.y())) {
            host.playDownSound();

            final HoverInspector.HoverInfo hoverInfo = host.hoverInspector.hoveredBiome(event.x(), event.y());
            if (hoverInfo != null) {
                final boolean plain = event.hasControlDown()
                        || hoverInfo.height() == Short.MIN_VALUE;
                final String yPart = hoverInfo.height() == Short.MIN_VALUE
                        ? "~"
                        : Integer.toString(hoverInfo.height());
                final String coordinates = plain
                        ? String.format("%s %s %s", hoverInfo.blockX(), yPart, hoverInfo.blockZ())
                        : String.format("/tp @s %s %s %s", hoverInfo.blockX(), yPart, hoverInfo.blockZ());

                host.minecraft().keyboardHandler.setClipboard(coordinates);
                host.showCopiedMessage(Component.translatable(
                        "world_preview.preview-display.coordinates.copied",
                        coordinates
                ));
            }
        }
    }

    void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        final double guiScale = host.minecraft().getWindow().getGuiScale();
        totalDragX -= (dragX * guiScale) * host.scaleBlockPos();
        totalDragZ -= (dragY * guiScale) * host.scaleBlockPos();
    }

    boolean mouseReleased(MouseButtonEvent event) {
        if (clicked) {
            onRelease(event);
            return true;
        }
        return false;
    }

    void onRelease(MouseButtonEvent event) {
        // If we did not click into the canvas at the start, then we ignore this release
        if (!clicked) {
            return;
        }
        clicked = false;

        double mouseX = event.x();
        double mouseY = event.y();

        // Check if dragged was minimal
        if (Math.abs(totalDragX) <= CLICK_DRAG_THRESHOLD && Math.abs(totalDragZ) <= CLICK_DRAG_THRESHOLD) {
            HoverInspector.HoverInfo hoverInfo = host.hoverInspector.hoveredBiome(mouseX, mouseY);
            if (hoverInfo == null || hoverInfo.entry() == null) {
                return;
            }

            host.playDownSoundSuper();
            if (host.selectedBiomeId() == hoverInfo.entry().id()) {
                host.dataProvider().onBiomeVisuallySelected(null);
            } else {
                host.dataProvider().onBiomeVisuallySelected(hoverInfo.entry());
            }
        }

        // Finalize drag: commit the live center, then force a confirmatory
        // sample queue for the final viewport.  During drag, queueGeneration()
        // already samples on a 50ms cadence from the drag center; release still
        // re-queues so the last throttle window (and any pending range) is not lost.
        final boolean didDrag = Math.abs(totalDragX) > CLICK_DRAG_THRESHOLD || Math.abs(totalDragZ) > CLICK_DRAG_THRESHOLD;
        host.renderSettings().setCenter(host.center());
        host.throttle.invalidateRenderedContent();
        host.throttle.resetDragTimers();

        totalDragX = 0;
        totalDragZ = 0;

        if (didDrag) {
            host.resetQueuedRange();
            host.queueGeneration();
        }
    }

    boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        synchronized (host.dataProvider()) {
            if (host.dataProvider().isUpdating()) {
                return true;
            }
            double delta = deltaX + deltaY;
            if (delta == 0.0) {
                return true;
            }

            // Ctrl+scroll always zooms; otherwise honor scrollWheelZooms setting.
            // Alt+scroll always adjusts Y (even when zoom mode is on).
            var window = host.minecraft().getWindow();
            boolean ctrl = InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                    || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL);
            boolean alt = InputConstants.isKeyDown(window, InputConstants.KEY_LALT)
                    || InputConstants.isKeyDown(window, InputConstants.KEY_RALT);
            boolean zoomMode = host.config().scrollWheelZooms;
            if (alt) {
                zoomMode = false;
            } else if (ctrl) {
                zoomMode = true;
            }

            if (zoomMode) {
                scrollZoom(mouseX, mouseY, delta);
            } else {
                scrollYLayer(delta);
            }
            return true;
        }
    }

    /**
     * Anchor-based zoom: remember the world position under the cursor so we can
     * keep it stationary after zooming (inspired by seedviewer's MapCamera.zoomAt,
     * but adapted for discrete zoom levels instead of continuous).
     */
    private void scrollZoom(double mouseX, double mouseY, double delta) {
        var renderSettings = host.renderSettings();
        int before = renderSettings.pixelsPerChunk();

        final int anchorWorldX = worldUnderCursorX(mouseX);
        final int anchorWorldZ = worldUnderCursorZ(mouseY);

        if (delta > 0.0) {
            renderSettings.zoomIn();
        } else {
            renderSettings.zoomOut();
        }
        if (before != renderSettings.pixelsPerChunk()) {
            host.applyZoomToVisualizer();

            // Adjust center so the same world position is under the cursor
            final int newAnchorWorldX = worldUnderCursorX(mouseX);
            final int newAnchorWorldZ = worldUnderCursorZ(mouseY);
            renderSettings.setCenter(new BlockPos(
                    host.center().getX() + (anchorWorldX - newAnchorWorldX),
                    host.center().getY(),
                    host.center().getZ() + (anchorWorldZ - newAnchorWorldZ)
            ));

            host.invalidateRenderCache();
            host.resetQueuedRange();
            host.queueGeneration();
            host.showTransientHud(Component.translatable(
                    "world_preview.preview-display.hud.zoom",
                    renderSettings.pixelsPerChunk()
            ));
        }
    }

    private void scrollYLayer(double delta) {
        var renderSettings = host.renderSettings();
        if (delta > 0.0) {
            renderSettings.decrementY();
        } else {
            renderSettings.incrementY();
        }
        host.invalidateRenderCache();
        host.resetQueuedRange();
        host.queueGeneration();
        host.showTransientHud(Component.translatable(
                "world_preview.preview-display.hud.y",
                host.center().getY()
        ));
    }

    private int worldUnderCursorX(double mouseX) {
        final int guiScale = (int) host.minecraft().getWindow().getGuiScale();
        return host.center().getX()
                - (int) (host.getTexWidth() * host.scaleBlockPos() / 2.0)
                + (int) ((mouseX - host.getX()) * guiScale * host.scaleBlockPos());
    }

    private int worldUnderCursorZ(double mouseY) {
        final int guiScale = (int) host.minecraft().getWindow().getGuiScale();
        return host.center().getZ()
                - (int) (host.getTexHeight() * host.scaleBlockPos() / 2.0)
                + (int) ((mouseY - host.getY()) * guiScale * host.scaleBlockPos());
    }

    boolean keyPressed(KeyEvent event) {
        if (host.dataProvider().isUpdating()) {
            return false;
        }
        // Arrow keys pan the map (16 blocks * scale) when the preview is focused.
        int step = Math.max(16, (int) (16 * host.scaleBlockPos()));
        boolean handled = false;
        if (event.isLeft()) {
            host.renderSettings().setCenter(host.center().offset(-step, 0, 0));
            handled = true;
        } else if (event.isRight()) {
            host.renderSettings().setCenter(host.center().offset(step, 0, 0));
            handled = true;
        } else if (event.isUp()) {
            host.renderSettings().setCenter(host.center().offset(0, 0, -step));
            handled = true;
        } else if (event.isDown()) {
            host.renderSettings().setCenter(host.center().offset(0, 0, step));
            handled = true;
        } else if (event.key() == InputConstants.KEY_HOME) {
            host.renderSettings().resetCenter();
            handled = true;
        }
        if (handled) {
            totalDragX = 0;
            totalDragZ = 0;
            host.invalidateRenderCache();
            host.resetQueuedRange();
            host.queueGeneration();
        }
        return handled;
    }

    private void removeSpawnPin() {
        spawnPinPos = null;
        if (spawnPinCallback != null) {
            spawnPinCallback.accept(null);
        }
    }
}
