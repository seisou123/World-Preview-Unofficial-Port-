package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.backend.analysis.Region;
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
    /** One-shot mode: a left click on an existing waypoint opens its editor;
     *  otherwise it places a new waypoint. */
    private boolean waypointMode = false;
    private java.util.function.Consumer<BlockPos> waypointPlaceCallback = null;
    private java.util.function.Consumer<Waypoint> waypointEditCallback = null;

    // === Measure tool (v1.5) ===
    private boolean measureMode = false;
    private BlockPos measureA = null;
    private BlockPos measureB = null;

    // === Scale-bar zoom slider ===
    /** Left press landed on the scale bar's zoom slider: drags re-level the ladder. */
    private boolean zoomSliderDragging = false;

    // === Region select (analysis screen) ===
    /** One-shot mode: left drag draws a rectangle that becomes the analysis region. */
    private boolean regionSelectMode = false;
    private BlockPos regionDragStart = null;
    private BlockPos regionDragEnd = null;
    private java.util.function.Consumer<Region> regionSelectCallback = null;

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

    void setWaypointEditCallback(java.util.function.Consumer<Waypoint> callback) {
        this.waypointEditCallback = callback;
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

    // === Region select API ===

    void setRegionSelectMode(boolean enabled) {
        regionSelectMode = enabled;
        regionDragStart = null;
        regionDragEnd = null;
        if (enabled) {
            // Mode exclusivity: region select is the only one that clears the
            // other one-shot modes (the existing modes do not clear each other).
            setSpawnPinMode(false);
            setWaypointMode(false);
            setMeasureMode(false);
        }
    }

    boolean isRegionSelectMode() {
        return regionSelectMode;
    }

    @org.jetbrains.annotations.Nullable
    BlockPos regionDragStart() {
        return regionDragStart;
    }

    @org.jetbrains.annotations.Nullable
    BlockPos regionDragEnd() {
        return regionDragEnd;
    }

    void setRegionSelectCallback(@org.jetbrains.annotations.Nullable java.util.function.Consumer<Region> callback) {
        regionSelectCallback = callback;
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
        zoomSliderDragging = false;
        totalDragX = 0;
        totalDragZ = 0;
    }

    /**
     * Safety net for missed release events: while a drag is active, poll the
     * raw GLFW button state and end the drag when both buttons are up.
     */
    void endDragIfButtonsReleased() {
        if (!clicked && !zoomSliderDragging) {
            return;
        }
        long window = host.minecraft().getWindow().handle();
        boolean leftPressed = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightPressed = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (!leftPressed && !rightPressed) {
            clicked = false;
            zoomSliderDragging = false;
            totalDragX = 0;
            totalDragZ = 0;
        }
    }

    // === Event handlers ===

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Scale-bar zoom slider: right clicks inside its bounds are consumed so
        // they never fall through to the coordinate-copy path below. Left
        // clicks are NOT consumed here - they must flow through to
        // onClick()/super.mouseClicked() so AbstractWidget sets its dragging
        // flag, without which onDrag never fires and slider dragging dies.
        if (event.button() != 0 && host.zoomSliderHit(event.x(), event.y())) {
            return true;
        }
        // Region select: right click cancels the mode.
        if (regionSelectMode && event.button() == 1 && host.widgetIsMouseOver(event.x(), event.y())) {
            setRegionSelectMode(false);
            return true;
        }
        if (spawnPinMode && event.button() == 1 && host.widgetIsMouseOver(event.x(), event.y())) {
            removeSpawnPin();
            host.playDownSound();
            return true;
        }
        // Right-click actions live here, not in onClick(): AbstractWidget only
        // forwards left clicks (isValidClickButton admits button 0), so any
        // right-click branch placed in onClick() is unreachable dead code.

        // Measure tool: right click clears both points.
        if (measureMode && event.button() == 1 && host.widgetIsMouseOver(event.x(), event.y())) {
            measureA = null;
            measureB = null;
            return true;
        }

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
            return true;
        }
        return false;
    }

    void onClick(MouseButtonEvent event, boolean doubleClick) {
        // Fix: set focus so Screen dispatches onDrag to this widget
        if (host.minecraft().gui.screen() != null) {
            host.minecraft().gui.screen().setFocused(host);
        }

        // Scale-bar zoom slider: a left press grabs it. Runs before every
        // other mode so a click on the slider never places a waypoint, pins
        // the spawn, or selects a biome. AbstractWidget already set its
        // dragging flag, so subsequent onDrag calls keep re-leveling.
        if (event.button() == 0 && host.zoomSliderHit(event.x(), event.y())) {
            zoomSliderDragging = true;
            applySliderLevel(event.x());
            return;
        }

        // Region select: a left press starts a box drag. This MUST return
        // before the pan path below (clicked stays false, so onDrag/onRelease
        // take their region branches instead of panning / biome-selecting).
        if (regionSelectMode && host.widgetIsMouseOver(event.x(), event.y())) {
            if (event.button() == 0) {
                regionDragStart = host.screenToBlock(event.x(), event.y());
                regionDragEnd = regionDragStart;
            }
            return;
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
            }
        }

        // Waypoint placement (one-shot): a left click on an existing waypoint
        // opens its editor instead of placing a duplicate. Either way the mode
        // exits after the click.
        if (waypointMode && host.widgetIsMouseOver(event.x(), event.y())) {
            if (event.button() == 0) {
                BlockPos pos = host.screenToBlock(event.x(), event.y());
                if (pos != null) {
                    Waypoint hit = host.waypointAt(event.x(), event.y());
                    if (hit != null && waypointEditCallback != null) {
                        waypointEditCallback.accept(hit);
                    } else if (waypointPlaceCallback != null) {
                        waypointPlaceCallback.accept(pos);
                    }
                }
                waypointMode = false;
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
            }
        }

        clicked = true;
        host.throttle.touchDragRenderTimer();
        clickMouseX = event.x();
        clickMouseY = event.y();
    }

    void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        if (zoomSliderDragged(event.x())) {
            return;
        }
        if (regionSelectMode) {
            // Box drag: track the moving corner; never accumulate pan offsets.
            if (regionDragStart != null) {
                regionDragEnd = host.screenToBlock(event.x(), event.y());
            }
            return;
        }
        final double guiScale = host.minecraft().getWindow().getGuiScale();
        totalDragX -= (dragX * guiScale) * host.scaleBlockPos();
        totalDragZ -= (dragY * guiScale) * host.scaleBlockPos();
    }

    boolean mouseReleased(MouseButtonEvent event) {
        if (zoomSliderReleased()) {
            return true;
        }
        if (clicked) {
            onRelease(event);
            return true;
        }
        return false;
    }

    void onRelease(MouseButtonEvent event) {
        // Region select finalize: commit the box to the callback (when the
        // drag covered more than a single block) and clear the drag state.
        // Runs before the !clicked guard because the region press path never
        // sets clicked — and it must not fall into biome select / pan finalize.
        if (regionSelectMode) {
            if (regionDragStart != null && regionDragEnd != null && regionSelectCallback != null) {
                BlockPos a = regionDragStart;
                BlockPos b = regionDragEnd;
                Region sel = Region.of(a.getX(), a.getZ(), b.getX(), b.getZ());
                if (sel.blockArea() > 1) {
                    regionSelectCallback.accept(sel);
                }
            }
            regionDragStart = null;
            regionDragEnd = null;
            // A press that started outside the map (pan path) may have set
            // clicked; drop its drag state so the pan path cannot resume.
            clicked = false;
            totalDragX = 0;
            totalDragZ = 0;
            return;
        }

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
     * Anchor-based zoom: remember the world position under the cursor, then
     * shift the center after the discrete zoom step so that same position
     * stays under the cursor. The ladder's ends are reported back to the HUD
     * instead of the wheel silently doing nothing.
     */
    private void scrollZoom(double mouseX, double mouseY, double delta) {
        var renderSettings = host.renderSettings();
        int before = renderSettings.pixelsPerChunk();
        boolean zoomIn = delta > 0.0;
        boolean changed = zoomIn ? renderSettings.zoomIn() : renderSettings.zoomOut();
        int after = changed ? renderSettings.pixelsPerChunk() : before;

        if (changed) {
            applyZoomChange(before, after, mouseX, mouseY, true);
        }

        // Always surface the outcome: silently ignoring a wheel tick at the
        // ladder's end read as "zoom is broken".
        if (changed) {
            host.showTransientHud(Component.translatable(
                    "world_preview.preview-display.hud.zoom", after));
        } else if (zoomIn) {
            host.showTransientHud(Component.translatable(
                    "world_preview.preview-display.hud.zoom.fully_in", after));
        } else {
            host.showTransientHud(Component.translatable(
                    "world_preview.preview-display.hud.zoom.fully_out", after));
        }
    }

    /**
     * Applies a zoom level change that already happened in RenderSettings:
     * re-anchors the view so the world point under (mouseX, mouseY) stays put,
     * then routes the refresh. Steps 16..4 px/chunk only rescale the already
     * sampled map (render-only, applied incrementally), while 2/1 px skip
     * quarts and need the sampling rebuild, which goes through the container.
     * {@code anchor} false keeps the map center fixed (slider input).
     */
    private void applyZoomChange(int before, int after, double mouseX, double mouseY, boolean anchor) {
        var renderSettings = host.renderSettings();
        final int anchorWorldX = worldUnderCursorX(mouseX);
        final int anchorWorldZ = worldUnderCursorZ(mouseY);
        host.applyZoomToVisualizer();

        if (anchor) {
            // Adjust center so the same world position is under the cursor
            final int newAnchorWorldX = worldUnderCursorX(mouseX);
            final int newAnchorWorldZ = worldUnderCursorZ(mouseY);
            renderSettings.setCenter(new BlockPos(
                    host.center().getX() + (anchorWorldX - newAnchorWorldX),
                    host.center().getY(),
                    host.center().getZ() + (anchorWorldZ - newAnchorWorldZ)
            ));
        }

        if (caeruleusTait.world.preview.RenderSettings.samplerStrideFor(before)
                != caeruleusTait.world.preview.RenderSettings.samplerStrideFor(after)) {
            // Crossing into/out of the 2/1 px levels changes the sampling
            // stride: the cached quarts were collected at a different
            // density, so this zoom step needs the container's cancel+rebuild
            // rather than an incremental re-queue.
            if (host.dataProvider() instanceof caeruleusTait.world.preview.client.gui.screens.PreviewContainer pc) {
                pc.requestZoomRebuild();
            } else {
                host.applyIncrementalZoom();
            }
        } else {
            host.applyIncrementalZoom();
        }
    }

    /**
     * Scale-bar slider input: press/drag maps the cursor x onto the nearest
     * ladder level. No anchor re-centering (the slider is a global control,
     * not a point on the map).
     */
    boolean zoomSliderDragged(double mouseX) {
        if (!zoomSliderDragging) {
            return false;
        }
        applySliderLevel(mouseX);
        return true;
    }

    boolean zoomSliderReleased() {
        if (!zoomSliderDragging) {
            return false;
        }
        zoomSliderDragging = false;
        return true;
    }

    private void applySliderLevel(double mouseX) {
        var renderSettings = host.renderSettings();
        int target = RenderSettings.zoomLevelAt(host.zoomSliderIndexAt(mouseX));
        int before = renderSettings.pixelsPerChunk();
        if (target == before) {
            return;
        }
        renderSettings.setPixelsPerChunk(target);
        applyZoomChange(before, target, mouseX, host.widgetHeight() / 2.0, false);
        host.showTransientHud(Component.translatable(
                "world_preview.preview-display.hud.zoom", target));
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
