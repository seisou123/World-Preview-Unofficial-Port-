// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.client.WorldPreviewClient;
import caeruleusTait.world.preview.client.gui.PreviewDisplayDataProvider;
import caeruleusTait.world.preview.domain.preview.accuracy.QueueAabb;
import caeruleusTait.world.preview.domain.preview.accuracy.ViewportMapping;
import caeruleusTait.world.preview.domain.waypoint.Waypoint;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;


import static caeruleusTait.world.preview.client.WorldPreviewComponents.MSG_ERROR_SETUP_FAILED;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.MSG_PREVIEW_SETUP_LOADING;

public class PreviewDisplay extends AbstractWidget implements AutoCloseable {
    private final Minecraft minecraft;
    private final PreviewDisplayDataProvider dataProvider;
    private final WorkManager workManager;
    private final RenderSettings renderSettings;
    private final WorldPreviewConfig config;
    private final PreviewDataVisualizer dataVisualizer;

    /**
     * Supplier that returns the list of all widgets in the same container.
     * Used by {@link #isMouseOver(double, double)} to yield mouse priority to
     * buttons, lists, and edit boxes that overlap the map area.
     */
    private Supplier<List<AbstractWidget>> occludingWidgetsSupplier = () -> List.of();

    private Component coordinatesCopiedMsg = null;
    private long coordinatesCopiedNanos = 0;
    private Component transientHudMsg = null;
    private long transientHudNanos = 0;
    private static final long TRANSIENT_HUD_NANOS = 1_500_000_000L;

    private int texWidth = 100;
    private int texHeight = 100;

    private short selectedBiomeId;
    private boolean highlightCaves;

        private double scaleBlockPos = 1;

    @Nullable
    public BlockPos screenToBlock(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY)) return null;
        final BlockPos center = center();
        final int guiScale = (int) minecraft.getWindow().getGuiScale();
        final int xPos = (int) ((mouseX - getX()) * guiScale * scaleBlockPos);
        final int zPos = (int) ((mouseY - getY()) * guiScale * scaleBlockPos);
        final int xMin = center.getX() - (int)(texWidth * scaleBlockPos / 2.0) - 1;
        final int zMin = center.getZ() - (int)(texHeight * scaleBlockPos / 2.0) - 1;
        return new BlockPos(xMin + xPos, center.getY(), zMin + zPos);
    }

    // --- Hover subsystem (grid + tooltip + caches) ---
    final HoverInspector hoverInspector = new HoverInspector(this);

    // --- Host accessors for collaborator classes (package-private) ---
    Minecraft minecraft() { return minecraft; }
    PreviewDisplayDataProvider dataProvider() { return dataProvider; }
    WorkManager workManager() { return workManager; }
    WorldPreviewConfig config() { return config; }
    RenderSettings renderSettings() { return renderSettings; }
    double scaleBlockPos() { return scaleBlockPos; }
    short selectedBiomeId() { return selectedBiomeId; }
    boolean highlightCaves() { return highlightCaves; }
    int widgetWidth() { return width; }
    int widgetHeight() { return height; }
    boolean isDragging() { return interaction.isClicked() && (interaction.totalDragX() != 0 || interaction.totalDragZ() != 0); }
    boolean isClicked() { return interaction.isClicked(); }
    boolean isHoveredFlag() { return isHovered; }
    boolean widgetIsMouseOver(double mouseX, double mouseY) { return isMouseOver(mouseX, mouseY); }

    /** Updates the block-per-pixel scale and re-wires the minimap after a zoom change. */
    void applyZoomToVisualizer() {
        scaleBlockPos = renderSettings.toScaleSpec().blockScale();
        dataVisualizer.updateRenderContext(engine.minimapImg(), engine.minimapTexture(), engine.colorMap(), texWidth, texHeight, scaleBlockPos);
    }

    void resetQueuedRange() {
        lastQueuedRange = null;
    }

    void showCopiedMessage(Component msg) {
        coordinatesCopiedNanos = System.nanoTime();
        coordinatesCopiedMsg = msg;
    }

    void showTransientHud(Component msg) {
        transientHudNanos = System.nanoTime();
        transientHudMsg = msg;
    }

    /** Shows a transient HUD message on the map (public for collaborators). */
    public void showHud(Component msg) {
        showTransientHud(msg);
    }

    /** Mirrors this widget's silent {@link #playDownSound} override. */
    void playDownSound() {
        // intentionally silent
    }

    /** Plays the standard AbstractWidget click sound (parent behavior). */
    void playDownSoundSuper() {
        super.playDownSound(minecraft.getSoundManager());
    }

    private Instant generationStart = null;

    private final MapInteractionController interaction = new MapInteractionController(this);

    final PreviewRenderThrottle throttle = new PreviewRenderThrottle();

    // === Spawn pin API (delegated to the interaction controller) ===
    public void setSpawnPinMode(boolean enabled) { interaction.setSpawnPinMode(enabled); }
    public boolean isSpawnPinMode() { return interaction.isSpawnPinMode(); }
    @Nullable public BlockPos spawnPinPos() { return interaction.spawnPinPos(); }
    public void setSpawnPinPos(@Nullable BlockPos pos) { interaction.setSpawnPinPos(pos); }
    public void setSpawnPinCallback(@Nullable java.util.function.Consumer<BlockPos> callback) { interaction.setSpawnPinCallback(callback); }

    // === Waypoints & measure tool (v1.5) ===

    /** Renders overlay content (waypoints) on top of the map inside the scissor. */
    public interface WaypointRenderer {
        void render(GuiGraphicsExtractor guiGraphics, int xMin, int yMin, int xMax, int yMax);
    }

    @Nullable private WaypointRenderer waypointRenderer = null;

    public void setWaypointRenderer(@Nullable WaypointRenderer renderer) {
        this.waypointRenderer = renderer;
    }

    /** Whether the map is in one-shot "place waypoint" mode. */
    public boolean isWaypointMode() { return interaction.isWaypointMode(); }

    public void setWaypointMode(boolean enabled) { interaction.setWaypointMode(enabled); }

    public void setWaypointPlaceCallback(@Nullable java.util.function.Consumer<BlockPos> callback) {
        interaction.setWaypointPlaceCallback(callback);
    }

    public void setWaypointEditCallback(@Nullable java.util.function.Consumer<Waypoint> callback) {
        interaction.setWaypointEditCallback(callback);
    }

    /** Delegates hit-testing to the waypoint overlay renderer. */
    @Nullable
    Waypoint waypointAt(double mouseX, double mouseY) {
        return waypointRenderer instanceof WaypointOverlayRenderer overlay
                ? overlay.waypointAt(mouseX, mouseY)
                : null;
    }

    // === Measure tool state (owned by the interaction controller) ===

    public boolean isMeasureMode() { return interaction.isMeasureMode(); }

    public void setMeasureMode(boolean enabled) { interaction.setMeasureMode(enabled); }

    /** Maps a block coordinate to widget-relative screen coords (GUI px), or null when off the map. */
    @Nullable
    BlockPos blockToScreen(int blockX, int blockZ) {
        final BlockPos center = center();
        final int guiScale = (int) minecraft.getWindow().getGuiScale();
        final double scale = scaleBlockPos;
        final double xMin = center.getX() - texWidth * scale / 2.0 - 1;
        final double zMin = center.getZ() - texHeight * scale / 2.0 - 1;
        final double sx = getX() + (blockX - xMin) / (guiScale * scale);
        final double sz = getY() + (blockZ - zMin) / (guiScale * scale);
        return new BlockPos((int) Math.round(sx), 0, (int) Math.round(sz));
    }

    private GenerationRange lastQueuedRange = null;

    // --- Viewport force-load safety net ---
    // When sampling is fully idle but the visible viewport still contains
    // chunks without completed biome sampling (lost pending handoff in
    // WorkManager, a batch that failed mid-pass, ...), queueGeneration
    // re-issues the range via WorkManager.forceQueueRange.  Cooldown-limited
    // so a permanently failing chunk retries at a bounded rate instead of
    // hot-looping.
    private static final long FORCE_QUEUE_COOLDOWN_NANOS = 1_000_000_000L;
    private long lastForceQueueNanos = 0;

    // --- Center coordinate string cache ---
    private String cachedCenterStr = null;
    private int cachedCenterX = Integer.MIN_VALUE;
    private int cachedCenterY = Integer.MIN_VALUE;
    private int cachedCenterZ = Integer.MIN_VALUE;

    private List<PreviewRenderEngine.RenderHelper> cachedRenderData = null;

    private final PreviewRenderEngine engine = new PreviewRenderEngine(this);

    public PreviewDisplay(Minecraft minecraft, PreviewDisplayDataProvider dataProvider, Component component) {
        super(0, 0, 100, 100, component);
        this.minecraft = minecraft;
        this.workManager = WorldPreview.get().workManager();
        this.dataProvider = dataProvider;
        this.renderSettings = WorldPreview.get().renderSettings();
        this.config = WorldPreview.get().cfg();
        this.dataVisualizer = new PreviewDataVisualizer(minecraft, dataProvider, workManager);
        resizeImage();
    }

    public void resizeImage() {
        engine.createDisplayTextures(texWidth, texHeight);
        scaleBlockPos = renderSettings.toScaleSpec().blockScale();
        dataVisualizer.updateRenderContext(engine.minimapImg(), engine.minimapTexture(), engine.colorMap(), texWidth, texHeight, scaleBlockPos);
        hoverInspector.resizeGrid(texWidth, texHeight);
        // A new texture was created and uploaded with black, but the actual
        // biome data has not been rendered into it yet.  Mark it so the next
        // render frame performs a full generateRenderData + updateTexture cycle.
        throttle.invalidateAfterResize();
    }

public void setSize(int width, int height) {
// Only rebuild texture if dimensions actually changed
int guiScale = (int) minecraft.getWindow().getGuiScale();
int newTexWidth = width * guiScale;
int newTexHeight = height * guiScale;

if (this.width == width && this.height == height
&& this.texWidth == newTexWidth && this.texHeight == newTexHeight) {
return; // No change needed
}

this.width = width;
this.height = height;
this.texWidth = newTexWidth;
this.texHeight = newTexHeight;
resizeImage();
}

    /**
     * Returns the internal texture width in pixels (widget width × GUI scale).
     * Used by {@code PreviewContainer.queueEarlyPreviewRange()} so that the
     * early-queue range matches the range computed by {@link #queueGeneration()}.
     */
    public int getTexWidth() {
        return texWidth;
    }

    /**
     * Returns the internal texture height in pixels (widget height × GUI scale).
     * @see #getTexWidth()
     */
    public int getTexHeight() {
        return texHeight;
    }

    public void reloadData() {
        // Invalidate the render cache so the next frame does a full re-render
        cachedRenderData = null;
        // A new world configuration means the previously queued range is no
        // longer valid (different seed/dimension/scale).  Drop the dedup guard
        // so the next render frame re-queues sampling for the current center.
        lastQueuedRange = null;
        // Force a fresh queue + render cycle for the new world data.
        throttle.invalidateAll();

        engine.reloadData();
        dataVisualizer.updateRenderContext(engine.minimapImg(), engine.minimapTexture(), engine.colorMap(), texWidth, texHeight, scaleBlockPos);
    }

    public void close() {
        engine.close();
    }

    public BlockPos center() {
        if (interaction.totalDragX() == 0 && interaction.totalDragZ() == 0) {
            return renderSettings.center();
        }
        return new BlockPos(
                (int) (renderSettings.center().getX() + interaction.totalDragX()),
                renderSettings.center().getY(),
                (int) (renderSettings.center().getZ() + interaction.totalDragZ())
        );
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor GuiGraphicsExtractor, int x, int y, float f) {
        // === FIX: GLFW mouse button state polling ===
        interaction.endDragIfButtonsReleased();
        final int colorBorder = 0xFF666666;

        final int xMin = getX();
        final int yMin = getY();
        final int xMax = xMin + width;
        final int yMax = yMin + height;

        // --- Lightweight frame timing + adaptive throttle (see PreviewRenderThrottle) ---
        final long frameStartNanos = System.nanoTime();
        throttle.onFrameStart();

        queueGeneration();
        synchronized (dataProvider) {
            if (dataProvider.setupFailed()) {
                engine.fillBlackAndUpload();
                WorldPreviewClient.renderTexture(GuiGraphicsExtractor, engine.mainTexture(), xMin, yMin, xMax, yMax);

                final List<MutableComponent> lines = MSG_ERROR_SETUP_FAILED.getString().lines().map(Component::literal).toList();

                final int centerX = getX() + (width / 2);
                final int centerY = getY() + (height / 2) - ((lines.size() / 2) * (minecraft.font.lineHeight + 4));

                for (int i = 0; i < lines.size(); ++i) {
                    final Component line = lines.get(i);
                    final int offsetY = i * (minecraft.font.lineHeight + 4);
                    GuiGraphicsExtractor.centeredText(minecraft.font, line, centerX, centerY + offsetY, 0xFFFFFFFF);
                }
            } else if (dataProvider.isUpdating()) {
                engine.fillBlackAndUpload();
                WorldPreviewClient.renderTexture(GuiGraphicsExtractor, engine.mainTexture(), xMin, yMin, xMax, yMax);

                final int centerX = getX() + (width / 2);
                final int centerY = getY() + (height / 2);
                GuiGraphicsExtractor.centeredText(minecraft.font, MSG_PREVIEW_SETUP_LOADING, centerX, centerY, 0xFFFFFFFF);
            } else {
                // --- Render-skip optimization ---
                // Check whether the preview data has changed since the last frame.
                // During drag we throttle expensive re-uploads, but we must NOT
                // early-return from renderWidget: that used to skip tooltips,
                // borders, coordinates and minimap on alternate frames (flicker).
                final boolean dragThrottleSkipHeavy = isDragging() && throttle.dragRenderThrottled(System.nanoTime());
                // If the center position is the same and no worker thread has
                // written new data, we can reuse the cached render data and
                // skip the expensive generateRenderData + updateTexture + upload
                // cycle entirely.  This eliminates ~100% of the per-frame render
                // cost when the user is idle (not dragging, not scrolling).
                final BlockPos currentCenter = center();
                final PreviewStorage storage = workManager.previewStorage();
                final long currentWriteCounter = storage != null ? storage.writeCounter() : 0;
                final boolean needRerender = throttle.shouldRerender(
                        dragThrottleSkipHeavy,
                        storage != null,
                        currentWriteCounter,
                        currentCenter,
                        cachedRenderData != null);

                if (!needRerender) {
                    // Reuse cached render data — just re-render the existing texture
                    // and structures without regenerating or re-uploading.
                    WorldPreviewClient.renderTexture(GuiGraphicsExtractor, engine.mainTexture(), xMin, yMin, xMax, yMax);

                    GuiGraphicsExtractor.enableScissor(xMin, yMin, xMax, yMax);
                    engine.renderStructures(cachedRenderData, GuiGraphicsExtractor);
                    engine.renderPlayerAndSpawn(GuiGraphicsExtractor);
                    engine.renderSpawnPin(GuiGraphicsExtractor);
                    renderOverlays(GuiGraphicsExtractor);
                    GuiGraphicsExtractor.disableScissor();
                } else {
                    throttle.markRendered(currentCenter, currentWriteCounter);

                    engine.beginFrameCounts();
                    hoverInspector.clearGridEntries();
                    // Structure hover grid was rebuilt; force tooltip re-query.
                    hoverInspector.invalidateCache();
                    final List<PreviewRenderEngine.RenderHelper> renderData = engine.generateRenderData();
                    cachedRenderData = renderData;
                    engine.updateTexture(renderData);

                    // Upload the modified NativeImage data to the GPU texture.
                    engine.uploadMainTexture();
                    throttle.markTextureUploaded();

                    // Render the main texture
                    WorldPreviewClient.renderTexture(GuiGraphicsExtractor, engine.mainTexture(), xMin, yMin, xMax, yMax);

                    // Overlay structure icons — clip them to the preview area.
                    GuiGraphicsExtractor.enableScissor(xMin, yMin, xMax, yMax);
                    engine.renderStructures(renderData, GuiGraphicsExtractor);
                    engine.renderPlayerAndSpawn(GuiGraphicsExtractor);
                    engine.renderSpawnPin(GuiGraphicsExtractor);
                    renderOverlays(GuiGraphicsExtractor);
                    GuiGraphicsExtractor.disableScissor();

                    // Sidebar biome list updates are noisy while dragging; defer.
                    if (!isDragging()) {
                        engine.biomesChanged();
                    }
                }

                // Tooltip must be scheduled every frame (setComponentTooltipForNextFrame
                // is single-frame). Always update while the map is shown �?including
                // during drag �?so the hover data bar does not blink on/off.
                double mouseX = (minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()) / minecraft.getWindow()
                        .getScreenWidth();
                double mouseZ = (minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()) / minecraft.getWindow()
                        .getScreenHeight();
                hoverInspector.updateTooltip(GuiGraphicsExtractor, mouseX, mouseZ);
            }
        }

        // Create a border
        GuiGraphicsExtractor.fill(xMin-1, yMin-1, xMax+1, yMin, colorBorder); // Right
        GuiGraphicsExtractor.fill(xMax, yMin, xMax+1, yMax, colorBorder); // Down
        GuiGraphicsExtractor.fill(xMin-1, yMax, xMax+1, yMax+1, colorBorder); // Left
        GuiGraphicsExtractor.fill(xMin-1, yMin, xMin, yMax, colorBorder); // Up

        // Render copied message
        if (coordinatesCopiedMsg != null) {
            GuiGraphicsExtractor.fill(xMin, yMax - 38, xMax, yMax - 19, 0xAA000000);
            GuiGraphicsExtractor.centeredText(minecraft.font, coordinatesCopiedMsg, xMin + ((xMax - xMin) / 2), yMax - 32, 0xFFFFFFFF);
            if ((System.nanoTime() - coordinatesCopiedNanos) >= 8_000_000_000L) {
                coordinatesCopiedMsg = null;
                coordinatesCopiedNanos = 0;
            }
        }

        // Transient HUD: zoom level / Y layer after scroll
        if (transientHudMsg != null) {
            int hudY = yMin + 6;
            int textW = minecraft.font.width(transientHudMsg);
            int hudX = xMin + ((xMax - xMin - textW) / 2);
            GuiGraphicsExtractor.fill(hudX - 4, hudY - 2, hudX + textW + 4, hudY + minecraft.font.lineHeight + 2, 0xAA000000);
            GuiGraphicsExtractor.text(minecraft.font, transientHudMsg, hudX, hudY, 0xFFFFFFFF);
            if ((System.nanoTime() - transientHudNanos) >= TRANSIENT_HUD_NANOS) {
                transientHudMsg = null;
                transientHudNanos = 0;
            }
        }

        final long frameEndNanos = System.nanoTime();
        final long frameTimeNanos = frameEndNanos - frameStartNanos;

        if (config.showFrameTime) {
            final long renderTimeMs = frameTimeNanos / 1_000_000;
            String frameInfo = renderTimeMs + " ms";
            if (throttle.adaptiveSkipEveryN() > 1) {
                frameInfo += " (throttled x" + throttle.adaptiveSkipEveryN() + ")";
            }
            GuiGraphicsExtractor.text(minecraft.font, frameInfo, 5, 5, 0xFFFFFFFF);
        }

        // Display the current center coordinates at the bottom-left of the
        // preview area (inside the preview, not the left panel).
        final BlockPos centerPos = center();
        if (config.showCoordinates) {
            // Cache the center string �?avoid String.format every frame when center hasn't changed
            if (cachedCenterX != centerPos.getX() || cachedCenterY != centerPos.getY() || cachedCenterZ != centerPos.getZ()) {
                cachedCenterX = centerPos.getX();
                cachedCenterY = centerPos.getY();
                cachedCenterZ = centerPos.getZ();
                cachedCenterStr = String.format("§7[§b%d§7, §b%d§7, §b%d§7]§r", cachedCenterX, cachedCenterY, cachedCenterZ);
            }
            GuiGraphicsExtractor.text(minecraft.font, cachedCenterStr, xMin + 5, yMax - minecraft.font.lineHeight - 4, 0xFFFFFFFF);
        }

        // === Minimap ===
        // Shows the full sampled area with a white box indicating the current viewport.
        if (config.showMinimap) {
            dataVisualizer.renderMinimap(GuiGraphicsExtractor, xMin, yMin, xMax, yMax, centerPos);
        }

        // === Generation statistics ===
        // Shows sampling progress, biome/structure counts, thread info.
        if (config.showStatistics) {
            dataVisualizer.renderStatistics(GuiGraphicsExtractor, xMin, yMin, xMax, yMax, engine.visibleBiomes(), engine.visibleStructures());
        }
    }

    /**
     * Draws the v1.5 map overlays: waypoints (via the renderer) and the
     * measure tool line/labels. Called inside the preview scissor.
     */
    private void renderOverlays(GuiGraphicsExtractor guiGraphics) {
        if (waypointRenderer != null) {
            waypointRenderer.render(guiGraphics, getX(), getY(), getX() + width, getY() + height);
        }
        renderMeasureOverlay(guiGraphics);
    }

    private void renderMeasureOverlay(GuiGraphicsExtractor guiGraphics) {
        BlockPos a = interaction.measurePointA();
        if (a == null) {
            return;
        }
        BlockPos sa = blockToScreen(a.getX(), a.getZ());
        if (sa == null) {
            return;
        }
        drawMeasureMarker(guiGraphics, sa, 0xFF29B6F6);

        BlockPos b = interaction.measurePointB();
        if (b == null) {
            return;
        }
        BlockPos sb = blockToScreen(b.getX(), b.getZ());
        if (sb == null) {
            return;
        }

        // Line between the two markers (Bresenham via 1px fills)
        int dx = sb.getX() - sa.getX();
        int dz = sb.getZ() - sa.getZ();
        int steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)));
        for (int i = 0; i <= steps; i++) {
            int px = sa.getX() + dx * i / steps;
            int pz = sa.getZ() + dz * i / steps;
            guiGraphics.fill(px, pz, px + 1, pz + 1, 0xFFFFEB3B);
        }
        drawMeasureMarker(guiGraphics, sb, 0xFFEF5350);

        // Distance label at the midpoint
        int ddx = b.getX() - a.getX();
        int ddz = b.getZ() - a.getZ();
        int dist = (int) Math.round(Math.sqrt((double) ddx * ddx + (double) ddz * ddz));
        String text = String.format("%dm §7(Δ %d, %d)§r", dist, ddx, ddz);
        int lx = (sa.getX() + sb.getX()) / 2;
        int ly = (sa.getZ() + sb.getZ()) / 2 - 12;
        guiGraphics.fill(lx - 2, ly - 1, lx + minecraft.font.width(text) + 2, ly + minecraft.font.lineHeight, 0x99000000);
        guiGraphics.text(minecraft.font, text, lx, ly, 0xFFFFFFFF);
    }

    private void drawMeasureMarker(GuiGraphicsExtractor guiGraphics, BlockPos screenPos, int color) {
        guiGraphics.fill(screenPos.getX() - 2, screenPos.getZ() - 2,
                screenPos.getX() + 3, screenPos.getZ() + 3, 0xFF000000);
        guiGraphics.fill(screenPos.getX() - 1, screenPos.getZ() - 1,
                screenPos.getX() + 2, screenPos.getZ() + 2, color);
    }

    /**
     * Centers the map on the nearest rendered structure of the given type
     * (within the currently drawn viewport data). Returns false when none is
     * on screen.
     */
    public boolean locateStructure(short structureId) {
        BlockPos found = hoverInspector.nearestStructureCenter(structureId, center());
        if (found == null) {
            return false;
        }
        renderSettings.setCenter(new BlockPos(found.getX(), center().getY(), found.getZ()));
        invalidateRenderCache();
        resetQueuedRange();
        queueGeneration();
        showTransientHud(Component.translatable(
                "world_preview.preview.located", found.getX(), found.getZ()));
        return true;
    }

    private record GenerationRange(BlockPos min, BlockPos max) {}

    void queueGeneration() {
        // Live drag center so newly revealed areas start sampling while the user
        // still holds the mouse.  Throttle during drag (50ms) so we do not cancel
        // worker batches every pixel; WorkManager still collapses rapid range
        // updates into a single pending viewport when a queue pass is in flight.
        if (isDragging() && throttle.dragQueueThrottled(System.nanoTime())) {
            return;
        }

        int preload = 0;
        if (config.enablePreload && !throttle.needsInitialQueue() && throttle.initialDataReceived()) {
            // Resource-aware: skip preloading when workers are busy.
            // NOTE: when needsInitialQueue is true, we MUST use preload=0 so
            // that the computed range matches the range already queued by
            // queueEarlyPreviewRange() (which also uses no preload).  If we
            // used preload>0 here, the range would differ from the early queue,
            // causing workManager.queueRange() to NOT dedup, which cancels
            // the early queue's in-flight work and restarts sampling from
            // scratch �?a major cause of the "black screen until drag" bug.
            // BUG FIX: the busy check must also treat a queue pass that is
            // still CREATING its batches as busy.  queueRangeReal clears
            // currentBatches before rebuilding them, which for large viewports
            // takes tens of milliseconds — several frames observe
            // activeBatchCount()==0 during that window, flip preload from 0 to
            // the full radius, and the viewport range starts oscillating
            // between "with preload" and "without".  Each new pass cancels the
            // previous one's batches mid-flight, workers never finish anything
            // and the map never loads (log shows alternating
            // "Queued N {early abort}" / "Queued N+ring" lines several times
            // per second).  isQueueRunning() spans that whole window.
            if (config.preloadOnlyWhenIdle && workManager.isSetup()
                    && (workManager.isQueueRunning() || workManager.activeBatchCount() > 0)) {
                preload = 0;
            } else {
                preload = config.preloadRadius;
            }
        }
        final BlockPos c = center();
        final ViewportMapping map = new ViewportMapping(
                c.getX(),
                c.getY(),
                c.getZ(),
                texWidth,
                texHeight,
                renderSettings.toScaleSpec(),
                minecraft.getWindow().getGuiScale()
        );
        QueueAabb aabb = QueueAabb.fromViewport(map, preload);
        final GenerationRange range = new GenerationRange(
                new BlockPos(aabb.minX(), aabb.y(), aabb.minZ()),
                new BlockPos(aabb.maxX(), aabb.y(), aabb.maxZ())
        );
        // === Safety net: force-load unsampled area visible on screen ===
        // Backstop for the whole class of "map never loads at this drag
        // position" bugs.  When sampling is completely idle and part of the
        // visible viewport has no completed biome sampling, re-issue the
        // viewport range bypassing all dedup guards (display-side and
        // WorkManager-side).  Intentionally no isDragging() gate: pausing
        // mid-drag at an unloaded position must also recover.
        if (throttle.initialDataReceived()
                && workManager.isSetup()
                && workManager.isIdle()
                && viewportHasUnsampledArea(map)) {
            final long now = System.nanoTime();
            if (now - lastForceQueueNanos >= FORCE_QUEUE_COOLDOWN_NANOS) {
                lastForceQueueNanos = now;
                lastQueuedRange = range;
                throttle.clearNeedsInitialQueue();
                WorldPreview.LOGGER.info(
                        "Viewport contains unsampled chunks while sampling is idle — forcing re-queue of {} .. {}",
                        range.min(), range.max()
                );
                workManager.forceQueueRange(range.min(), range.max());
                return;
            }
        }
        // The needsInitialQueue flag guarantees at least one queueRange() call
        // after setup, bypassing the dedup check.  Without this, if the computed
        // range happens to match a stale lastQueuedRange (e.g. because
        // queueEarlyPreviewRange already queued the same area), the dedup would
        // skip the call and the WorkManager would never start sampling.
        if (!throttle.needsInitialQueue() && range.equals(lastQueuedRange)) {
            return;
        }
        throttle.clearNeedsInitialQueue();
        lastQueuedRange = range;
        workManager.queueRange(range.min(), range.max());
    }

    /**
     * True when any of a 3×3 grid of probe points across the visible viewport
     * falls in a chunk that has no completed biome sampling.  Probe cost is
     * nine map lookups and is only paid when sampling is idle.
     *
     * <p>The probe always checks the biome flag: the main biome layer is queued
     * for every viewport regardless of the active render mode, and it is the
     * layer whose completion the work units actually mark (noise sections
     * never get completion bits).
     */
    private boolean viewportHasUnsampledArea(ViewportMapping map) {
        final PreviewStorage storage = workManager.previewStorage();
        if (storage == null) {
            return false;
        }
        final int w = map.worldMaxX() - map.worldMinX();
        final int h = map.worldMaxZ() - map.worldMinZ();
        final int yQuart = QuartPos.fromBlock(center().getY());
        for (int ix = 0; ix <= 2; ix++) {
            for (int iz = 0; iz <= 2; iz++) {
                final int qx = QuartPos.fromBlock(map.worldMinX() + (w * ix) / 2);
                final int qz = QuartPos.fromBlock(map.worldMinZ() + (h * iz) / 2);
                if (!storage.isChunkSampled(qx, yQuart, qz, PreviewStorage.FLAG_BIOME)) {
                    return true;
                }
            }
        }
        return false;
    }


    @Override
    public void playDownSound(SoundManager handler) {
        // By default, do nothing
    }

    /**
     * Sets the supplier that provides the list of sibling widgets.
     * When the mouse is over another visible, active widget (e.g. a button),
     * this map yields mouse priority so clicks go to the button, not the map.
     *
     * @param supplier a supplier returning the full widget list (including this widget)
     */
    public void setOccludingWidgetsSupplier(Supplier<List<AbstractWidget>> supplier) {
        this.occludingWidgetsSupplier = supplier;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (!super.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        // Yield to any sibling widget (buttons, lists, edit boxes) that is at
        // the same screen position.  This prevents the map from intercepting
        // clicks meant for buttons that overlap the map area.
        for (AbstractWidget w : occludingWidgetsSupplier.get()) {
            if (w != this && w.active && w.visible && w.isMouseOver(mouseX, mouseY)) {
                return false;
            }
        }
        return true;
    }

    // === Input handling: delegated to MapInteractionController ===

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (interaction.mouseClicked(event, doubleClick)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        interaction.onClick(event, doubleClick);
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        interaction.onDrag(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (interaction.mouseReleased(event)) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        interaction.onRelease(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        return interaction.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (interaction.keyPressed(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    /**
     * Negative values for none
     */
    public void setSelectedBiomeId(short biomeId) {
        selectedBiomeId = biomeId;
        // Invalidate render cache so the next frame re-renders with the new highlight
        throttle.invalidateRenderedContent();
    }

    public void setHighlightCaves(boolean highlightCaves) {
        this.highlightCaves = highlightCaves;
        // Invalidate render cache so the next frame re-renders with the new highlight
        throttle.invalidateRenderedContent();
    }

    /**
     * Exports the current preview image to a PNG file in the game directory.
     * @return the path to the saved file, or null on failure
     */
    public String exportImage() {
        return engine.exportImage();
    }

    /**
     * Resets the generation start timer.  Called when the world/seed changes.
     */
    public void resetGenerationTimer() {
        generationStart = null;
    }

    /**
     * Invalidates the render cache so the next frame performs a full re-render.
     * Call this when the PreviewDisplay is reused on a different screen (e.g.
     * WorldAnalysisScreen) to avoid stale cached state preventing rendering.
     */
    public void invalidateRenderCache() {
        cachedRenderData = null;
        throttle.invalidateAll();
        // Drop the queued-range dedup guard so the next frame re-evaluates the
        // range instead of short-circuiting against a stale pre-change range.
        lastQueuedRange = null;
        dataVisualizer.invalidateCache();
        // Reset mouse interaction state in case a mouse press was interrupted
        // by a screen change (e.g. opening TerrainExportScreen while dragging).
        // Without this, clicked=true can persist and cause the widget to think
        // the mouse is still held down after returning from the sub-screen.
        interaction.resetInteractionState();
        // Invalidate hover cache so the tooltip re-queries on the next frame.
        hoverInspector.invalidateCache();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Nothing to do
    }
}
