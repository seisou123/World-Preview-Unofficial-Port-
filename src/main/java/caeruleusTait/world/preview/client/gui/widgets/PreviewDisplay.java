// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.client.WorldPreviewClient;
import caeruleusTait.world.preview.client.gui.PreviewDisplayDataProvider;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import caeruleusTait.world.preview.domain.preview.accuracy.QueueAabb;
import caeruleusTait.world.preview.domain.preview.accuracy.ViewportMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import it.unimi.dsi.fastutil.shorts.Short2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import caeruleusTait.world.preview.backend.terrain.HillshadeRenderer;
import caeruleusTait.world.preview.backend.terrain.ContourRenderer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

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

    private byte[] heightFieldBuffer;
    private int[] heightFieldColors;
    private int heightFieldWidth;
    private int heightFieldHeight;
    private Short2LongMap visibleBiomes;
    private Short2LongMap visibleStructures;
    private NativeImage previewImg;
    private DynamicTexture previewTexture;
    private NativeImage minimapImg;
    private DynamicTexture minimapTexture;
    private long[] workingVisibleBiomes;
    private long[] workingVisibleStructures;
    private int[] colorMap;
    private int[] colorMapGrayScale;
    private int[] heightColorMap;
    private int[] noiseColorMap;
    private int[] noiseColorMapTemperature;
    private int[] noiseColorMapHumidity;
    private int[] noiseColorMapContinentalness;
    private int[] noiseColorMapErosion;
    private int[] noiseColorMapDepth;
    private int[] noiseColorMapWeirdness;
    private int[] noiseColorMapPeaksAndValleys;
    private boolean[] cavesMap;
    private IconData[] structureIcons;
    private IconData playerIcon;
    private IconData spawnIcon;
    private ItemStack[] structureItems;
    private PreviewDisplayDataProvider.StructureRenderInfo[] structureRenderInfoMap;
    private final NativeImage dummyIcon;

    private Component coordinatesCopiedMsg = null;
    private long coordinatesCopiedNanos = 0;
    private Component transientHudMsg = null;
    private long transientHudNanos = 0;
    private static final long TRANSIENT_HUD_NANOS = 1_500_000_000L;

    private int texWidth = 100;
    private int texHeight = 100;

    private short selectedBiomeId;
    private boolean highlightCaves;

    private double totalDragX = 0;
    private double totalDragZ = 0;

        private double scaleBlockPos = 1;

    // === Spawn Pin ===
    private boolean spawnPinMode = false;
    @Nullable private BlockPos spawnPinPos;
    @Nullable private java.util.function.Consumer<BlockPos> spawnPinCallback;

    public void setSpawnPinMode(boolean enabled) { this.spawnPinMode = enabled; }
    public boolean isSpawnPinMode() { return spawnPinMode; }
    @Nullable public BlockPos spawnPinPos() { return spawnPinPos; }
    public void setSpawnPinPos(@Nullable BlockPos pos) { this.spawnPinPos = pos; }
    public void setSpawnPinCallback(@Nullable java.util.function.Consumer<BlockPos> callback) { this.spawnPinCallback = callback; }

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

    private StructHoverHelperCell[] hoverHelperGrid;
    private final int hoverHelperGridCellSize = 64;
    private int hoverHelperGridWidth;
    private int hoverHelperGridHeight;

    private Instant generationStart = null;

    private boolean clicked = false;
    // === Diagnostic logging for input debugging ===
    private boolean mouseEventLogged = false;
    private int renderFrameCount = 0;
    private double clickMouseX = 0;
    private double clickMouseY = 0;

    // --- Lightweight frame-time tracking (no boxing, no allocation) ---
    // Uses System.nanoTime() and an exponential moving average (EMA)
    // to track render cost without creating any objects.
    private long lastFrameNanos = 0;
    private double frameTimeEmaMs = 0; // EMA of frame time in milliseconds

    // --- Adaptive render throttling ---
    // When the EMA frame time exceeds thresholds, we skip re-renders
    // on some frames to maintain UI responsiveness.
    private static final double ADAPTIVE_THRESHOLD_MS = 35.0;  // ~28 fps
    private static final double ADAPTIVE_CRITICAL_MS = 60.0;   // ~16 fps
    private static final long DRAG_RENDER_INTERVAL_NANOS = 50_000_000L;
    /** While dragging, re-queue sampling at most every 50ms (same cadence as drag re-render). */
    private static final long DRAG_QUEUE_INTERVAL_NANOS = 50_000_000L;
    private int adaptiveSkipCounter = 0;
    private int adaptiveSkipEveryN = 1; // dynamically adjusted
    private long lastDragRenderNanos = 0;
    private long lastDragQueueNanos = 0;

    private GenerationRange lastQueuedRange = null;

    // --- Minimap cache handled by PreviewDataVisualizer ---

    // --- Mouse / map-center cache for tooltip skip ---
    // Hover world coords depend on both the cursor and the map center (including
    // in-progress drag offsets). Cache must invalidate when either changes.
    private double lastMouseX = -1;
    private double lastMouseZ = -1;
    private int lastHoverCenterX = Integer.MIN_VALUE;
    private int lastHoverCenterY = Integer.MIN_VALUE;
    private int lastHoverCenterZ = Integer.MIN_VALUE;
    // Cached hover info �?only re-query storage when mouse or center changes
    private HoverInfo cachedHoverInfo = null;
    private List<StructHoverHelperEntry> cachedStructInfos = null;

    // --- Center coordinate string cache ---
    private String cachedCenterStr = null;
    private int cachedCenterX = Integer.MIN_VALUE;
    private int cachedCenterY = Integer.MIN_VALUE;
    private int cachedCenterZ = Integer.MIN_VALUE;

    // --- Render-skip optimization ---
    // Tracks whether the preview data has changed since the last render.
    // When the user is not dragging and no new biome data has arrived,
    // we can skip the expensive generateRenderData + updateTexture + upload
    // cycle entirely and just re-render the existing texture.
    private BlockPos lastRenderedCenter = null;
    private long lastWriteCounter = -1;
    private List<RenderHelper> cachedRenderData = null;

    // --- Texture upload tracking ---
    // Set to true whenever resizeImage() creates a new GPU texture that has
    // not yet been uploaded.  The render-skip optimization must NOT skip the
    // upload step when this is true, otherwise the user sees a black/undefined
    // texture until the next data change forces a re-render.
    // This is the root-cause fix for the "black screen until drag" bug:
    // resizeImage() created a new DynamicTexture but never uploaded it, and
    // the render-skip path rendered the unuploaded texture (which appears
    // black on most GPU drivers).
    private boolean textureNeedsUpload = true;

    // --- Initial queue tracking ---
    // Ensures queueGeneration() performs at least one real queueRange() call
    // after the display is first set up, even if the computed range happens
    // to match a stale lastQueuedRange from a previous world configuration.
    private boolean needsInitialQueue = true;

    // --- Preload oscillation guard ---
    // Prevents infinite abort/restart loop in WorkManager when preload
    // produces a different range than the initial queue.
    private boolean initialDataReceived = false;

    // Set to true during updateTexture() whenever any workingVisibleBiomes
    // entry changes.  This lets biomesChanged() skip the O(N) array scan
    // on frames where nothing changed.
    private boolean biomeCountsDirty = false;

    private record IconData(@NotNull NativeImage img, @NotNull DynamicTexture texture) {
        public void close() {
            // Unregister before close so TextureManager does not retain the id.
            WorldPreviewClient.unregisterTexture(texture);
            texture.close();
            // NativeImage owned by PreviewContainer for structure icons
        }
    }

    public PreviewDisplay(Minecraft minecraft, PreviewDisplayDataProvider dataProvider, Component component) {
        super(0, 0, 100, 100, component);
        this.minecraft = minecraft;
        this.workManager = WorldPreview.get().workManager();
        this.dataProvider = dataProvider;
        this.visibleBiomes = new Short2LongOpenHashMap();
        this.visibleStructures = new Short2LongOpenHashMap();
        this.renderSettings = WorldPreview.get().renderSettings();
        this.config = WorldPreview.get().cfg();
        this.dummyIcon = new NativeImage(16, 16, true);
        this.structureIcons = new IconData[0];
        this.dataVisualizer = new PreviewDataVisualizer(minecraft, dataProvider, workManager);
        resizeImage();
    }

    public void resizeImage() {
        closeDisplayTextures();
        // Create NativeImage first, then wrap in DynamicTexture
        // This ensures we have full control over the image data
        previewImg = new NativeImage(NativeImage.Format.RGBA, texWidth, texHeight, true);
        previewTexture = new DynamicTexture(() -> "preview_display", previewImg);
        // Immediately fill with opaque black and upload so the GPU texture is
        // never in an undefined state.  Without this, the render-skip path can
        // render an unuploaded texture, which appears as a black screen on
        // most GPU drivers �?the root cause of the "black screen until drag" bug.
        previewImg.fillRect(0, 0, texWidth, texHeight, 0xFF000000);
        previewTexture.upload();
        minimapImg = new NativeImage(NativeImage.Format.RGBA, 80, 80, true);
        minimapTexture = new DynamicTexture(() -> "minimap_display", minimapImg);
        minimapImg.fillRect(0, 0, 80, 80, 0xFF000000);
        minimapTexture.upload();
        scaleBlockPos = renderSettings.toScaleSpec().blockScale();
        dataVisualizer.updateRenderContext(minimapImg, minimapTexture, colorMap, texWidth, texHeight, scaleBlockPos);
        hoverHelperGridWidth = (texWidth / hoverHelperGridCellSize) + 1;
        hoverHelperGridHeight = (texHeight / hoverHelperGridCellSize) + 1;
        hoverHelperGrid = new StructHoverHelperCell[hoverHelperGridWidth * hoverHelperGridHeight];
        for (int i = 0; i < hoverHelperGrid.length; ++i) {
            hoverHelperGrid[i] = new StructHoverHelperCell(new ArrayList<>());
        }
        // A new texture was created and uploaded with black, but the actual
        // biome data has not been rendered into it yet.  Mark it so the next
        // render frame performs a full generateRenderData + updateTexture cycle.
        lastRenderedCenter = null;
        cachedRenderData = null;
        // MUST be true: even though we uploaded black pixels above, the actual
        // biome/structure data has NOT been rendered into this texture yet.
        // Setting this to false was the root cause of the "black screen until
        // drag" bug: the render-skip optimization saw textureNeedsUpload=false
        // and lastRenderedCenter=null, did one render pass that produced an
        // all-black texture (because worker threads hadn't written data yet),
        // then skipped all subsequent frames because writeCounter hadn't
        // changed.  With textureNeedsUpload=true, the render-skip logic takes
        // a different branch that forces a re-render whenever storage is
        // available, ensuring data is picked up as soon as workers write it.
        textureNeedsUpload = true;
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
        // Cleanup previous
        closeIconTextures();

        // Invalidate the render cache so the next frame does a full re-render
        lastRenderedCenter = null;
        cachedRenderData = null;
        // A new world configuration means the previously queued range is no
        // longer valid (different seed/dimension/scale).  Drop the dedup guard
        // so the next render frame re-queues sampling for the current center.
        lastQueuedRange = null;
        // Force a fresh queue + render cycle for the new world data.
        needsInitialQueue = true;
        textureNeedsUpload = true;
        initialDataReceived = false;
        // Reset the write-counter sentinel so the first render after reload
        // always detects a change (currentWriteCounter != -1), ensuring
        // subsequent data writes by worker threads are picked up even if
        // the first render happens before any data is available.
        lastWriteCounter = -1;

        // Reset the cached visible-biome / visible-structure maps so that the
        // next render frame does not mistake stale data from a previous world
        // configuration for the current one.  Without this reset,
        // biomesChanged() would see tempBiomesSet (empty, because the storage
        // was just recreated) != visibleBiomes (non-empty, left over from the
        // previous world) and call onVisibleBiomesChanged(empty), which
        // clears the biome list in the GUI.
        visibleBiomes = new Short2LongOpenHashMap();
        visibleStructures = new Short2LongOpenHashMap();

        PreviewData.BiomeData[] rawBiomeMap = dataProvider.previewData().biomeId2BiomeData();
        structureRenderInfoMap = dataProvider.renderStructureMap();
        structureItems = dataProvider.structureItems();
        structureIcons = Arrays.stream(dataProvider.structureIcons()).map(x -> new IconData(x, new DynamicTexture(() -> "struct_icon", x))).toArray(IconData[]::new);
        playerIcon = new IconData(dataProvider.playerIcon(), new DynamicTexture(() -> "player_icon", dataProvider.playerIcon()));
        spawnIcon = new IconData(dataProvider.spawnIcon(), new DynamicTexture(() -> "spawn_icon", dataProvider.spawnIcon()));
        playerIcon.texture.upload();
        spawnIcon.texture.upload();
        Arrays.stream(structureIcons).map(IconData::texture).forEach(DynamicTexture::upload);
        try {
            heightColorMap = dataProvider.heightColorMap();
            noiseColorMap = dataProvider.noiseColorMap();
            // Per-noise-type gradients: each noise parameter gets its own
            // dedicated color gradient for better visual differentiation.
            noiseColorMapTemperature = dataProvider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_TEMPERATURE);
            noiseColorMapHumidity = dataProvider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_HUMIDITY);
            noiseColorMapContinentalness = dataProvider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_CONTINENTALNESS);
            noiseColorMapErosion = dataProvider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_EROSION);
            noiseColorMapDepth = dataProvider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_DEPTH);
            noiseColorMapWeirdness = dataProvider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_WEIRDNESS);
            noiseColorMapPeaksAndValleys = dataProvider.noiseColorMapFor(RenderSettings.RenderMode.NOISE_PEAKS_AND_VALLEYS);
            // When per-noise-type gradients are disabled in config, fall back
            // to the shared colormap for all noise render modes.
            if (!config.usePerNoiseTypeGradients) {
                noiseColorMapTemperature = noiseColorMap;
                noiseColorMapHumidity = noiseColorMap;
                noiseColorMapContinentalness = noiseColorMap;
                noiseColorMapErosion = noiseColorMap;
                noiseColorMapDepth = noiseColorMap;
                noiseColorMapWeirdness = noiseColorMap;
                noiseColorMapPeaksAndValleys = noiseColorMap;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        workingVisibleBiomes = new long[rawBiomeMap.length];
        workingVisibleStructures = new long[structureIcons.length];
        colorMap = new int[rawBiomeMap.length];
        colorMapGrayScale = new int[rawBiomeMap.length];
        cavesMap = new boolean[rawBiomeMap.length];
        for (short i = 0; i < rawBiomeMap.length; ++i) {
            colorMap[i] = textureColor(rawBiomeMap[i].color());
            colorMapGrayScale[i] = grayScale(colorMap[i]);
            cavesMap[i] = rawBiomeMap[i].isCave();
        }
        dataVisualizer.updateRenderContext(minimapImg, minimapTexture, colorMap, texWidth, texHeight, scaleBlockPos);
    }

    private void closeIconTextures() {
        if (structureIcons != null) {
            Arrays.stream(structureIcons).forEach(IconData::close);
            structureIcons = new IconData[0];
        }
        if (playerIcon != null) {
            WorldPreviewClient.unregisterTexture(playerIcon.texture);
            playerIcon.texture.close();
            playerIcon = null;
        }
        if (spawnIcon != null) {
            WorldPreviewClient.unregisterTexture(spawnIcon.texture);
            spawnIcon.texture.close();
            spawnIcon = null;
        }
    }

    private void closeDisplayTextures() {
        if (previewTexture != null) {
            WorldPreviewClient.unregisterTexture(previewTexture);
            previewTexture.close();
            previewTexture = null;
        }
        if (previewImg != null) {
            previewImg.close();
            previewImg = null;
        }
        if (minimapTexture != null) {
            WorldPreviewClient.unregisterTexture(minimapTexture);
            minimapTexture.close();
            minimapTexture = null;
        }
        if (minimapImg != null) {
            minimapImg.close();
            minimapImg = null;
        }
    }

    public void close() {
        closeIconTextures();
        closeDisplayTextures();
        // Display-owned placeholder; structure NativeImages are freed by PreviewContainer
        if (dummyIcon != null) {
            dummyIcon.close();
        }
    }

    public BlockPos center() {
        if (totalDragX == 0 && totalDragZ == 0) {
            return renderSettings.center();
        }
        return new BlockPos(
                (int) (renderSettings.center().getX() + totalDragX),
                renderSettings.center().getY(),
                (int) (renderSettings.center().getZ() + totalDragZ)
        );
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int x, int y, float f) {
        renderFrameCount++;
        if (renderFrameCount <= 5 || renderFrameCount % 300 == 0) {
            boolean inChildren = minecraft.screen != null && minecraft.screen.children().contains(this);
        }
        // === FIX: GLFW mouse button state polling ===
        if (clicked) {
            long window = GLFW.glfwGetCurrentContext();
            boolean leftPressed = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            boolean rightPressed = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            if (!leftPressed && !rightPressed) {
                clicked = false;
                totalDragX = 0;
                totalDragZ = 0;
            }
        }
        final int colorBorder = 0xFF666666;

        final int xMin = getX();
        final int yMin = getY();
        final int xMax = xMin + width;
        final int yMax = yMin + height;

        // --- Lightweight frame timing (no Instant/Duration allocation) ---
        final long frameStartNanos = System.nanoTime();
        if (lastFrameNanos != 0) {
            final long deltaNanos = frameStartNanos - lastFrameNanos;
            final double deltaMs = deltaNanos / 1_000_000.0;
            // EMA with alpha=0.1 (smooth over ~10 frames)
            frameTimeEmaMs = frameTimeEmaMs == 0 ? deltaMs : (frameTimeEmaMs * 0.9 + deltaMs * 0.1);
        }
        lastFrameNanos = frameStartNanos;

        // --- Adaptive render throttle ---
        if (frameTimeEmaMs > ADAPTIVE_CRITICAL_MS) {
            adaptiveSkipEveryN = 3;
        } else if (frameTimeEmaMs > ADAPTIVE_THRESHOLD_MS) {
            adaptiveSkipEveryN = 2;
        } else {
            adaptiveSkipEveryN = 1;
        }

        queueGeneration();
        synchronized (dataProvider) {
            if (dataProvider.setupFailed()) {
                previewImg.fillRect(0, 0, texWidth, texHeight, 0xFF000000);
                previewTexture.upload();
                WorldPreviewClient.renderTexture(guiGraphics, previewTexture, xMin, yMin, xMax, yMax);

                final List<MutableComponent> lines = MSG_ERROR_SETUP_FAILED.getString().lines().map(Component::literal).toList();

                final int centerX = getX() + (width / 2);
                final int centerY = getY() + (height / 2) - ((lines.size() / 2) * (minecraft.font.lineHeight + 4));

                for (int i = 0; i < lines.size(); ++i) {
                    final Component line = lines.get(i);
                    final int offsetY = i * (minecraft.font.lineHeight + 4);
                    guiGraphics.drawCenteredString(minecraft.font, line, centerX, centerY + offsetY, 0xFFFFFFFF);
                }
            } else if (dataProvider.isUpdating()) {
                previewImg.fillRect(0, 0, texWidth, texHeight, 0xFF000000);
                previewTexture.upload();
                WorldPreviewClient.renderTexture(guiGraphics, previewTexture, xMin, yMin, xMax, yMax);

                final int centerX = getX() + (width / 2);
                final int centerY = getY() + (height / 2);
                guiGraphics.drawCenteredString(minecraft.font, MSG_PREVIEW_SETUP_LOADING, centerX, centerY, 0xFFFFFFFF);
            } else {
                // --- Render-skip optimization ---
                // Check whether the preview data has changed since the last frame.
                // During drag we throttle expensive re-uploads, but we must NOT
                // early-return from renderWidget: that used to skip tooltips,
                // borders, coordinates and minimap on alternate frames (flicker).
                final boolean isDragging = clicked && (totalDragX != 0 || totalDragZ != 0);
                boolean dragThrottleSkipHeavy = false;
                if (isDragging) {
                    final long now = System.nanoTime();
                    if (now - lastDragRenderNanos < DRAG_RENDER_INTERVAL_NANOS) {
                        dragThrottleSkipHeavy = true;
                    } else {
                        lastDragRenderNanos = now;
                    }
                }
                // If the center position is the same and no worker thread has
                // written new data, we can reuse the cached render data and
                // skip the expensive generateRenderData + updateTexture + upload
                // cycle entirely.  This eliminates ~100% of the per-frame render
                // cost when the user is idle (not dragging, not scrolling).
                final BlockPos currentCenter = center();
                final PreviewStorage storage = workManager.previewStorage();
                final long currentWriteCounter = storage != null ? storage.writeCounter() : 0;
                if (currentWriteCounter > 0) {
                    initialDataReceived = true;
                }
                // --- CRITICAL: always detect data changes ---
                // The writeCounter check MUST happen on every frame, even during
                // adaptive throttling.  Previously, the adaptive throttle could
                // skip the writeCounter check on some frames, and if the first
                // render happened to capture writeCounter=0 (no data yet),
                // subsequent frames with data (writeCounter>0) were skipped
                // because the throttle missed the change window.
                final boolean writeCounterChanged = currentWriteCounter != lastWriteCounter;

                final boolean needRerender;
                if (textureNeedsUpload) {
                    // A new texture was created (e.g. by resizeImage) but has not
                    // yet had biome data rendered into it.  Force a full render
                    // cycle so the user never sees a stale/empty texture.
                    needRerender = storage != null && !dragThrottleSkipHeavy;
                } else if (dragThrottleSkipHeavy) {
                    // Keep previous GPU texture this frame; still draw overlays below.
                    needRerender = false;
                } else if (storage == null) {
                    needRerender = false;
                } else if (writeCounterChanged) {
                    // Data changed: ALWAYS re-render, bypassing adaptive throttle.
                    // This is the key fix for the "black screen until drag" bug:
                    // worker threads write data asynchronously, and the render
                    // thread must pick it up immediately, not on the Nth frame.
                    needRerender = true;
                } else if (adaptiveSkipEveryN > 1 && lastRenderedCenter != null && currentCenter.equals(lastRenderedCenter)) {
                    // Adaptive throttling active: only re-render on every Nth frame
                    // (writeCounterChanged was already checked above)
                    adaptiveSkipCounter++;
                    if (adaptiveSkipCounter >= adaptiveSkipEveryN) {
                        adaptiveSkipCounter = 0;
                        needRerender = cachedRenderData == null;
                    } else {
                        needRerender = false;
                    }
                } else {
                    // Fast machine, or center changed (always re-render on center change)
                    needRerender = lastRenderedCenter == null
                            || !currentCenter.equals(lastRenderedCenter)
                            || cachedRenderData == null;
                }

                if (!needRerender) {
                    // Reuse cached render data �?just re-render the existing texture
                    // and structures without regenerating or re-uploading.
                    WorldPreviewClient.renderTexture(guiGraphics, previewTexture, xMin, yMin, xMax, yMax);

                    guiGraphics.enableScissor(xMin, yMin, xMax, yMax);
                    renderStructures(cachedRenderData, guiGraphics);
                    renderPlayerAndSpawn(guiGraphics);
                    renderSpawnPin(guiGraphics);
                    guiGraphics.disableScissor();
                } else {
                    lastRenderedCenter = currentCenter;
                    lastWriteCounter = currentWriteCounter;

                    Arrays.fill(workingVisibleBiomes, (short) 0);
                    Arrays.fill(workingVisibleStructures, (short) 0);
                    for (int i = 0; i < hoverHelperGrid.length; i++) {
                        hoverHelperGrid[i].entries.clear();
                    }
                    biomeCountsDirty = false;
                    // Structure hover grid was rebuilt; force tooltip re-query.
                    lastMouseX = -1;
                    lastMouseZ = -1;
                    lastHoverCenterX = Integer.MIN_VALUE;
                    cachedHoverInfo = null;
                    cachedStructInfos = null;
                    final List<RenderHelper> renderData = generateRenderData();
                    cachedRenderData = renderData;
                    updateTexture(renderData);

                    // Upload the modified NativeImage data to the GPU texture.
                    previewTexture.upload();
                    textureNeedsUpload = false;

                    // Render the main texture
                    WorldPreviewClient.renderTexture(guiGraphics, previewTexture, xMin, yMin, xMax, yMax);

                    // Overlay structure icons �?clip them to the preview area.
                    guiGraphics.enableScissor(xMin, yMin, xMax, yMax);
                    renderStructures(renderData, guiGraphics);
                    renderPlayerAndSpawn(guiGraphics);
                    renderSpawnPin(guiGraphics);
                    guiGraphics.disableScissor();

                    // Sidebar biome list updates are noisy while dragging; defer.
                    if (!isDragging) {
                        biomesChanged();
                    }
                }

                // Tooltip must be scheduled every frame (setComponentTooltipForNextFrame
                // is single-frame). Always update while the map is shown �?including
                // during drag �?so the hover data bar does not blink on/off.
                double mouseX = (minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()) / minecraft.getWindow()
                        .getScreenWidth();
                double mouseZ = (minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()) / minecraft.getWindow()
                        .getScreenHeight();
                updateTooltip(guiGraphics, mouseX, mouseZ);
            }
        }

        // Create a border
        guiGraphics.fill(xMin-1, yMin-1, xMax+1, yMin, colorBorder); // Right
        guiGraphics.fill(xMax, yMin, xMax+1, yMax, colorBorder); // Down
        guiGraphics.fill(xMin-1, yMax, xMax+1, yMax+1, colorBorder); // Left
        guiGraphics.fill(xMin-1, yMin, xMin, yMax, colorBorder); // Up

        // Render copied message
        if (coordinatesCopiedMsg != null) {
            guiGraphics.fill(xMin, yMax - 38, xMax, yMax - 19, 0xAA000000);
            guiGraphics.drawCenteredString(minecraft.font, coordinatesCopiedMsg, xMin + ((xMax - xMin) / 2), yMax - 32, 0xFFFFFFFF);
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
            guiGraphics.fill(hudX - 4, hudY - 2, hudX + textW + 4, hudY + minecraft.font.lineHeight + 2, 0xAA000000);
            guiGraphics.drawString(minecraft.font, transientHudMsg, hudX, hudY, 0xFFFFFFFF);
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
            if (adaptiveSkipEveryN > 1) {
                frameInfo += " (throttled x" + adaptiveSkipEveryN + ")";
            }
            guiGraphics.drawString(minecraft.font, frameInfo, 5, 5, 0xFFFFFFFF);
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
            guiGraphics.drawString(minecraft.font, cachedCenterStr, xMin + 5, yMax - minecraft.font.lineHeight - 4, 0xFFFFFFFF);
        }

        // === Minimap ===
        // Shows the full sampled area with a white box indicating the current viewport.
        if (config.showMinimap) {
            dataVisualizer.renderMinimap(guiGraphics, xMin, yMin, xMax, yMax, centerPos);
        }

        // === Generation statistics ===
        // Shows sampling progress, biome/structure counts, thread info.
        if (config.showStatistics) {
            dataVisualizer.renderStatistics(guiGraphics, xMin, yMin, xMax, yMax, visibleBiomes, visibleStructures);
        }
    }

    private record TextureCoordinate(int x, int z) {}

    private record GenerationRange(BlockPos min, BlockPos max) {}

    private ViewportMapping currentMapping() {
        BlockPos c = center();
        return new ViewportMapping(
                c.getX(),
                c.getY(),
                c.getZ(),
                texWidth,
                texHeight,
                renderSettings.toScaleSpec(),
                minecraft.getWindow().getGuiScale()
        );
    }

    private TextureCoordinate blockToTexture(BlockPos blockPos) {
        ViewportMapping map = currentMapping();
        return new TextureCoordinate(
                map.worldToTextureX(blockPos.getX()),
                map.worldToTextureZ(blockPos.getZ())
        );
    }

    private void putHoverStructEntry(TextureCoordinate pos, StructHoverHelperEntry entry) {
        int cellX = Math.max(0, Math.min(hoverHelperGridWidth - 1, pos.x / hoverHelperGridCellSize));
        int cellZ = Math.max(0, Math.min(hoverHelperGridHeight - 1, pos.z / hoverHelperGridCellSize));
        hoverHelperGrid[(cellX * hoverHelperGridHeight) + cellZ].entries.add(entry);
    }

    private void queueGeneration() {
        // Live drag center so newly revealed areas start sampling while the user
        // still holds the mouse.  Throttle during drag (50ms) so we do not cancel
        // worker batches every pixel; WorkManager still collapses rapid range
        // updates into a single pending viewport when a queue pass is in flight.
        final boolean isDragging = clicked && (totalDragX != 0 || totalDragZ != 0);
        if (isDragging) {
            final long now = System.nanoTime();
            if (now - lastDragQueueNanos < DRAG_QUEUE_INTERVAL_NANOS) {
                return;
            }
            lastDragQueueNanos = now;
        }

        int preload = 0;
        if (config.enablePreload && !needsInitialQueue && initialDataReceived) {
            // Resource-aware: skip preloading when workers are busy.
            // NOTE: when needsInitialQueue is true, we MUST use preload=0 so
            // that the computed range matches the range already queued by
            // queueEarlyPreviewRange() (which also uses no preload).  If we
            // used preload>0 here, the range would differ from the early queue,
            // causing workManager.queueRange() to NOT dedup, which cancels
            // the early queue's in-flight work and restarts sampling from
            // scratch �?a major cause of the "black screen until drag" bug.
            if (config.preloadOnlyWhenIdle && workManager.isSetup() && workManager.activeBatchCount() > 0) {
                preload = 0;
            } else {
                preload = config.preloadRadius;
            }
        }
        ViewportMapping map = currentMapping();
        QueueAabb aabb = QueueAabb.fromViewport(map, preload);
        final GenerationRange range = new GenerationRange(
                new BlockPos(aabb.minX(), aabb.y(), aabb.minZ()),
                new BlockPos(aabb.maxX(), aabb.y(), aabb.maxZ())
        );
        // The needsInitialQueue flag guarantees at least one queueRange() call
        // after setup, bypassing the dedup check.  Without this, if the computed
        // range happens to match a stale lastQueuedRange (e.g. because
        // queueEarlyPreviewRange already queued the same area), the dedup would
        // skip the call and the WorkManager would never start sampling.
        if (!needsInitialQueue && range.equals(lastQueuedRange)) {
            return;
        }
        needsInitialQueue = false;
        lastQueuedRange = range;
        workManager.queueRange(range.min(), range.max());
    }

    private record RenderHelper(
            PreviewSection dataSection,
            PreviewSection structureSection,
            PreviewSection.AccessData accessData,
            int sectionStartTexX,
            int sectionStartTexZ
    ) {
    }

    private List<RenderHelper> generateRenderData() {
        final BlockPos center = center();
        final int xMin = center.getX() - (int)(texWidth * scaleBlockPos / 2.0) - 1;
        final int zMin = center.getZ() - (int)(texHeight * scaleBlockPos / 2.0) - 1;

        final int quartExpand = renderSettings.quartExpand();
        final int quartStride = renderSettings.quartStride();

        final int quartsInWidth = (texWidth / quartExpand) * quartStride;
        final int quartsInHeight = (texHeight / quartExpand) * quartStride;

        final int minQuartX = QuartPos.fromBlock(xMin);
        final int minQuartZ = QuartPos.fromBlock(zMin);

        final int maxQuartX = minQuartX + quartsInWidth;
        final int maxQuartZ = minQuartZ + quartsInHeight;

        int quartX = minQuartX;
        int quartY = QuartPos.fromBlock(center.getY());
        int quartZ = minQuartZ;

        int sectionStartTexX = 0;
        int sectionStartTexZ = 0;

        final List<RenderHelper> res = new ArrayList<>(((quartsInWidth / PreviewSection.SIZE) + 2) * ((quartsInHeight / PreviewSection.SIZE) + 2));

        PreviewStorage storage = workManager.previewStorage();
        if (storage == null) {
            return res;
        }

        // Load sections �?no outer synchronized(storage) needed because
        // section4() already synchronizes on the correct y-layer monitor
        // (blocks[indexY]).  The outer lock was redundant and caused
        // unnecessary contention with worker threads that lock on the
        // same y-layer arrays.
        {
            while (true) {
                long flag = renderSettings.mode.flag;
                int useY = renderSettings.mode.useY ? quartY : 0;
                PreviewSection dataSection = storage.section4(quartX, useY, quartZ, flag);
                PreviewSection structureSection = storage.section4(quartX, 0, quartZ, PreviewStorage.FLAG_STRUCT_START);
                PreviewSection.AccessData accessData = dataSection.calcQuartOffsetData(quartX, quartZ, maxQuartX, maxQuartZ);

                res.add(new RenderHelper(dataSection, structureSection, accessData, sectionStartTexX, sectionStartTexZ));

                // Can we fit more stuff in the X direction?
                if (accessData.continueX()) {
                    int quartDiffX = accessData.maxX() - accessData.minX();
                    quartX += quartDiffX;
                    sectionStartTexX += (quartDiffX * quartExpand) / quartStride;
                    continue;
                }

                // We are at the end in the X direction, can we continue in the Z direction?
                if (accessData.continueZ()) {
                    int quartDiffZ = accessData.maxZ() - accessData.minZ();
                    quartX = minQuartX;
                    quartZ += quartDiffZ;
                    sectionStartTexZ += (quartDiffZ * quartExpand) / quartStride;
                    sectionStartTexX = 0;
                    continue;
                }

                // We are done drawing now
                break;
            }
        }

        return res;
    }

    private void updateTexture(List<RenderHelper> renderData) {
        int texX = 0;

        final int quartExpand = renderSettings.quartExpand();
        final int quartStride = renderSettings.quartStride();

        if (renderData.isEmpty()) {
            return;
        }

        boolean needHeightField = renderSettings.mode == RenderSettings.RenderMode.HEIGHTMAP
                && (config.enableHillshade || config.enableContours);
        if (needHeightField) {
            heightFieldWidth = texWidth / Math.max(1, quartExpand);
            heightFieldHeight = texHeight / Math.max(1, quartExpand);
            int bufSize = heightFieldWidth * heightFieldHeight;
            if (heightFieldBuffer == null || heightFieldBuffer.length < bufSize) {
                heightFieldBuffer = new byte[bufSize];
                heightFieldColors = new int[bufSize];
            }
            java.util.Arrays.fill(heightFieldBuffer, (byte) 0);
            java.util.Arrays.fill(heightFieldColors, 0x00000000);
        } else {
            heightFieldBuffer = null;
            heightFieldColors = null;
        }

        // Render the biomes / heightmap
        //
        // Batch optimization: instead of calling fillRect once per quart sample
        // (which is one JNI call per pixel when quartExpand==1), we detect runs
        // of consecutive same-color pixels along the Z axis and merge them into
        // a single fillRect call.  This reduces JNI calls by 3-10x on typical
        // biome maps where large regions share the same biome.
        for (RenderHelper r : renderData) {
            texX = r.sectionStartTexX;

            for(int x = r.accessData.minX(); x < r.accessData.maxX(); x += quartStride) {
                int texZ = r.sectionStartTexZ;

                // Batch state for consecutive same-color pixels along Z
                int batchStartZ = texZ;
                int batchEndZ = texZ;
                int batchColor = 0xFF000000;
                boolean batchActive = false;

                for (int z = r.accessData.minZ(); z < r.accessData.maxZ(); z += quartStride) {
                    // Read the biome data
                    short rawData = r.dataSection.get(x, z);
                    int color = 0xFF000000;
                    switch (renderSettings.mode) {
                        case BIOMES -> {
                            if (rawData >= 0 && rawData < colorMap.length) {
                                color = selectedBiomeId >= 0 || highlightCaves ? colorMapGrayScale[rawData] : colorMap[rawData];
                                if (selectedBiomeId == rawData || (highlightCaves && cavesMap[rawData])) {
                                    color = colorMap[rawData];
                                }
                                workingVisibleBiomes[rawData] += 1;
                                biomeCountsDirty = true;
                            }
                        }
                        case HEIGHTMAP -> {
                            if (rawData > Short.MIN_VALUE) {
                                int idx = rawData - dataProvider.yMin();
                                if (idx >= 0 && idx < heightColorMap.length) {
                                    color = heightColorMap[idx];
                                }
                                if (heightFieldBuffer != null) {
                                    int hfX = texX / Math.max(1, quartExpand);
                                    int hfZ = texZ / Math.max(1, quartExpand);
                                    int hfIdx = hfZ * heightFieldWidth + hfX;
                                    if (hfIdx >= 0 && hfIdx < heightFieldBuffer.length) {
                                        int clampedH = Math.max(0, Math.min(255, rawData - dataProvider.yMin() + 64));
                                        heightFieldBuffer[hfIdx] = (byte) clampedH;
                                        heightFieldColors[hfIdx] = color;
                                    }
                                }
                            }
                        }
                        case INTERSECTIONS -> {
                            if (rawData >= 0) {
                                // Main y-intersection
                                color = MapColor.byId(rawData).col;
                                color = textureColor(color == 0 ? 0xFFFFFF : color);
                            } else if(rawData > Short.MIN_VALUE) {
                                // See through one layer of air
                                color = MapColor.byId(-rawData).col;
                                color = highlightColor(textureColor(color == 0 ? 0xFFFFFF : color));
                            }
                        }
                        case NOISE_TEMPERATURE -> {
                            if (rawData > Short.MIN_VALUE) {
                                final float data = ((float) rawData) / ((float) Short.MAX_VALUE);
                                final int idx = Math.min(1023, Math.max(0, 512 + (int) (data * 512)));
                                color = noiseColorMapTemperature[idx];
                            }
                        }
                        case NOISE_HUMIDITY -> {
                            if (rawData > Short.MIN_VALUE) {
                                final float data = ((float) rawData) / ((float) Short.MAX_VALUE);
                                final int idx = Math.min(1023, Math.max(0, 512 + (int) (data * 512)));
                                color = noiseColorMapHumidity[idx];
                            }
                        }
                        case NOISE_CONTINENTALNESS -> {
                            if (rawData > Short.MIN_VALUE) {
                                final float data = ((float) rawData) / ((float) Short.MAX_VALUE);
                                final int idx = Math.min(1023, Math.max(0, 512 + (int) (data * 512)));
                                color = noiseColorMapContinentalness[idx];
                            }
                        }
                        case NOISE_EROSION -> {
                            if (rawData > Short.MIN_VALUE) {
                                final float data = ((float) rawData) / ((float) Short.MAX_VALUE);
                                final int idx = Math.min(1023, Math.max(0, 512 + (int) (data * 512)));
                                color = noiseColorMapErosion[idx];
                            }
                        }
                        case NOISE_DEPTH -> {
                            if (rawData > Short.MIN_VALUE) {
                                final float data = ((float) rawData) / ((float) Short.MAX_VALUE);
                                final int idx = Math.min(1023, Math.max(0, 512 + (int) (data * 512)));
                                color = noiseColorMapDepth[idx];
                            }
                        }
                        case NOISE_WEIRDNESS -> {
                            if (rawData > Short.MIN_VALUE) {
                                final float data = ((float) rawData) / ((float) Short.MAX_VALUE);
                                final int idx = Math.min(1023, Math.max(0, 512 + (int) (data * 512)));
                                color = noiseColorMapWeirdness[idx];
                            }
                        }
                        case NOISE_PEAKS_AND_VALLEYS -> {
                            if (rawData > Short.MIN_VALUE) {
                                final float data = ((float) rawData) / 0.75f / ((float) Short.MAX_VALUE);
                                final float pvData = NoiseRouterData.peaksAndValleys(Math.min(1.0f, Math.max(-1.0f, data)));
                                final int idx = Math.min(1023, Math.max(0, 512 + (int) (pvData * 512)));
                                color = noiseColorMapPeaksAndValleys[idx];
                            }
                        }
                    }

                    int w = Math.min(texWidth - texX, quartExpand);
                    int h = Math.min(texHeight - texZ, quartExpand);

                    // Extend current batch if same color and same pixel size
                    if (batchActive && color == batchColor && w == quartExpand && h == quartExpand) {
                        batchEndZ += quartExpand;
                    } else {
                        // Flush previous batch
                        if (batchActive && batchEndZ > batchStartZ) {
                            int batchH = Math.min(texHeight - batchStartZ, batchEndZ - batchStartZ);
                            previewImg.fillRect(texX, batchStartZ, quartExpand, batchH, batchColor);
                        }
                        // Start new batch
                        batchStartZ = texZ;
                        batchColor = color;
                        batchEndZ = texZ + h;
                        batchActive = true;
                    }

                    texZ += quartExpand;
                }
                // Flush remaining batch
                if (batchActive && batchEndZ > batchStartZ) {
                    int batchH = Math.min(texHeight - batchStartZ, batchEndZ - batchStartZ);
                    previewImg.fillRect(texX, batchStartZ, quartExpand, batchH, batchColor);
                }

                texX += quartExpand;
            }
        }
        if (heightFieldBuffer != null && heightFieldWidth > 0 && heightFieldHeight > 0) {
            applyHeightmapPostProcessing(quartExpand);
        }

    }

    private void applyHeightmapPostProcessing(int quartExpand) {
        int fw = heightFieldWidth;
        int fh = heightFieldHeight;
        if (fw < 2 || fh < 2) return;

        byte[] shadeBuffer = null;
        if (config.enableHillshade) {
            HillshadeRenderer renderer = new HillshadeRenderer(
                    config.hillshadeAzimuth, config.hillshadeAltitude,
                    config.hillshadeAmbient, config.hillshadeExaggeration, 0.5f);
            shadeBuffer = renderer.render(heightFieldBuffer, fw, fh, (float) quartExpand);
        }

        if (shadeBuffer != null) {
            for (int fy = 0; fy < fh; fy++) {
                for (int fx = 0; fx < fw; fx++) {
                    int idx = fy * fw + fx;
                    int baseColor = heightFieldColors[idx];
                    if (baseColor == 0x00000000) continue;
                    int shade = shadeBuffer[idx] & 0xFF;
                    heightFieldColors[idx] = HillshadeRenderer.applyShade(baseColor, (byte) shade);
                }
            }
        }

        if (config.enableContours) {
            ContourRenderer contourRenderer = new ContourRenderer(
                    config.contourInterval, config.contourMinorLines,
                    0xC08B4513, 0x608B6914);
            contourRenderer.render(heightFieldBuffer, heightFieldColors, fw, fh);
        }

        for (int fy = 0; fy < fh; fy++) {
            for (int fx = 0; fx < fw; fx++) {
                int color = heightFieldColors[fy * fw + fx];
                if (color == 0x00000000) continue;
                int px = fx * quartExpand;
                int py = fy * quartExpand;
                int w = Math.min(quartExpand, texWidth - px);
                int h = Math.min(quartExpand, texHeight - py);
                if (w > 0 && h > 0) {
                    previewImg.fillRect(px, py, w, h, color);
                }
            }
        }
    }

    private void renderStructures(List<RenderHelper> renderData, GuiGraphics guiGraphics) {
        if (!config.sampleStructures) {
            return;
        }

        // Draw structures
        //  - Do this in a separate RenderHelper loop to ensure that the biome data is overwritten
        for (RenderHelper r : renderData) {
            for (PreviewSection.PreviewStruct structure : r.structureSection.structures()) {
                short id = structure.structureId();
                // Guard against invalid structure IDs from stale storage data
                if (id < 0 || id >= structureIcons.length || id >= structureItems.length || id >= structureRenderInfoMap.length) {
                    continue;
                }
                TextureCoordinate texCenter = blockToTexture(structure.center());
                IconData iconData = structureIcons[id];
                NativeImage icon = iconData.img;
                DynamicTexture iconTexture = iconData.texture;
                ItemStack item = structureItems[id];
                if (icon == null && item == null) {
                    continue;
                }
                if (icon == null) {
                    icon = dummyIcon;
                }

                // Check if visible
                final int xMin = -(icon.getWidth() / 2);
                final int xMax = (icon.getWidth() / 2) + 1 + texWidth;
                final int zMin = -(icon.getHeight() / 2);
                final int zMax = (icon.getHeight() / 2) + 1 + texHeight;
                if (texCenter.x() < xMin || texCenter.z() < zMin || texCenter.x() > xMax || texCenter.z() > zMax) {
                    continue;
                }

                if (id >= 0 && id < workingVisibleStructures.length) {
                    workingVisibleStructures[id] += 1;
                }

                // Do not render hidden structures, but still count them
                if (!structureRenderInfoMap[id].show() || renderSettings.hideAllStructures) {
                    continue;
                }

                // Render icon / item
                // texCenter/texStart are in texture-pixel space; convert via ViewportMapping
                final int texStartX = texCenter.x() - (icon.getWidth() / 2);
                final int texStartZ = texCenter.z() - (icon.getHeight() / 2);

                ViewportMapping map = currentMapping();
                final int rXMin = map.textureToScreenX(texStartX, getX());
                final int rZMin = map.textureToScreenZ(texStartZ, getY());
                final int rXMax = map.textureToScreenXCeil(texStartX + icon.getWidth(), getX());
                final int rZMax = map.textureToScreenZCeil(texStartZ + icon.getHeight(), getY());

                if (item != null) {
                    // Items are always 16 GUI pixels; center on texCenter in GUI space
                    final int itemX = map.textureToScreenXRound(texCenter.x(), getX()) - 8;
                    final int itemZ = map.textureToScreenZRound(texCenter.z(), getY()) - 8;
                    guiGraphics.renderItem(item, itemX, itemZ);
                } else if (iconTexture != null) {
                    WorldPreviewClient.renderTexture(guiGraphics, iconTexture, rXMin, rZMin, rXMax, rZMax);
                }

                putHoverStructEntry(
                        texCenter,
                        new StructHoverHelperEntry(
                                new BoundingBox(texStartX, 0, texStartZ, texStartX + icon.getWidth(), 0, texStartZ + icon.getHeight()),
                                structure
                        )
                );
            }
        }
    }

    private void renderSpawnPin(GuiGraphics guiGraphics) {
        if (spawnPinPos == null) return;
        renderStickyIcon(guiGraphics, playerIcon, spawnPinPos);
    }

    private void renderPlayerAndSpawn(GuiGraphics guiGraphics) {
        if (!config.showPlayer) {
            return;
        }

        PreviewDisplayDataProvider.PlayerData playerData = dataProvider.getPlayerData(minecraft.getUser().getProfileId());
        if (playerData.currentPos() != null) {
            renderStickyIcon(guiGraphics, playerIcon, playerData.currentPos());
        }
        if (playerData.spawnPos() != null) {
            renderStickyIcon(guiGraphics, spawnIcon, playerData.spawnPos());
        }
    }

    /**
     * Render the player and spawn icons in double the size
     */
    private void renderStickyIcon(GuiGraphics guiGraphics, IconData iconData, BlockPos pos) {
        final double guiScale = minecraft.getWindow().getGuiScale();
        final NativeImage icon = iconData.img;

        TextureCoordinate texCenter = blockToTexture(pos);
        texCenter = new TextureCoordinate(
                Math.max(0, Math.min(texWidth, texCenter.x())),
                Math.max(0, Math.min(texHeight, texCenter.z()))
        );

        // Render icon at double size �?tex coords are texture-pixel space; convert to GUI
        final int texStartX = texCenter.x() - icon.getWidth();
        final int texStartZ = texCenter.z() - icon.getHeight();

        final int rXMin = getX() + (int) Math.floor(texStartX / guiScale);
        final int rZMin = getY() + (int) Math.floor(texStartZ / guiScale);
        final int rXMax = getX() + (int) Math.ceil((texStartX + icon.getWidth() * 2) / guiScale);
        final int rZMax = getY() + (int) Math.ceil((texStartZ + icon.getHeight() * 2) / guiScale);

        WorldPreviewClient.renderTexture(guiGraphics, iconData.texture, rXMin, rZMin, rXMax, rZMax);
    }

    private void biomesChanged() {
        // Fast path: if updateTexture didn't modify any biome counts this
        // frame, skip the O(N) array scan entirely.
        if (!biomeCountsDirty) {
            return;
        }

        // Quick check: if nothing changed since last frame, skip the expensive
        // map creation and comparison entirely.  We compare the raw arrays
        // directly because they are always the same length and a simple
        // reference-equality / content check is far cheaper than building
        // new Short2LongOpenHashMap objects every frame.
        boolean biomesChanged = false;
        boolean structuresChanged = false;

        // Only build new maps if the working arrays actually differ from what
        // we last reported.  This avoids creating Short2LongOpenHashMap
        // objects (and their internal hash tables) every single frame when the
        // user is not scrolling.
        if (visibleBiomes.size() == 0) {
            // Need to check if any biomes are now visible
            for (short i = 0; i < workingVisibleBiomes.length; ++i) {
                if (workingVisibleBiomes[i] > 0) { biomesChanged = true; break; }
            }
        } else {
            // Quick size check first, then content check
            int visibleCount = 0;
            for (short i = 0; i < workingVisibleBiomes.length; ++i) {
                if (workingVisibleBiomes[i] > 0) visibleCount++;
            }
            biomesChanged = (visibleCount != visibleBiomes.size());
            if (!biomesChanged) {
                // Same count �?verify values match
                for (short i = 0; i < workingVisibleBiomes.length; ++i) {
                    if (workingVisibleBiomes[i] > 0) {
                        long prev = visibleBiomes.get(i);
                        if (prev != workingVisibleBiomes[i]) {
                            biomesChanged = true;
                            break;
                        }
                    }
                }
            }
        }

        if (visibleStructures.size() == 0) {
            for (short i = 0; i < workingVisibleStructures.length; ++i) {
                if (workingVisibleStructures[i] > 0) { structuresChanged = true; break; }
            }
        } else {
            int visibleCount = 0;
            for (short i = 0; i < workingVisibleStructures.length; ++i) {
                if (workingVisibleStructures[i] > 0) visibleCount++;
            }
            structuresChanged = (visibleCount != visibleStructures.size());
            if (!structuresChanged) {
                for (short i = 0; i < workingVisibleStructures.length; ++i) {
                    if (workingVisibleStructures[i] > 0) {
                        long prev = visibleStructures.get(i);
                        if (prev != workingVisibleStructures[i]) {
                            structuresChanged = true;
                            break;
                        }
                    }
                }
            }
        }

        if (biomesChanged) {
            Short2LongMap tempBiomesSet = new Short2LongOpenHashMap(workingVisibleBiomes.length);
            for (short i = 0; i < workingVisibleBiomes.length; ++i) {
                if (workingVisibleBiomes[i] > 0) {
                    tempBiomesSet.put(i, workingVisibleBiomes[i]);
                }
            }
            dataProvider.onVisibleBiomesChanged(tempBiomesSet);
            visibleBiomes = tempBiomesSet;
        }

        if (structuresChanged) {
            Short2LongMap tempStructuresSet = new Short2LongOpenHashMap(workingVisibleStructures.length);
            for (short i = 0; i < workingVisibleStructures.length; ++i) {
                if (workingVisibleStructures[i] > 0) {
                    tempStructuresSet.put(i, workingVisibleStructures[i]);
                }
            }
            dataProvider.onVisibleStructuresChanged(tempStructuresSet);
            visibleStructures = tempStructuresSet;
        }
    }

    /**
     * Hover queries must work while the user is dragging. AbstractWidget's
     * {@code isHovered} can flicker false during mouse-drag frames, which made
     * the biome/coords data bar vanish every other frame (whole-bar blink).
     */
    private boolean shouldShowHoverData(double mouseX, double mouseY) {
        return workManager.previewStorage() != null
                && (clicked || isHovered || isMouseOver(mouseX, mouseY));
    }

    private HoverInfo hoveredBiome(double mouseX, double mouseY) {
        if (!shouldShowHoverData(mouseX, mouseY)) {
            return null;
        }
        int guiScale = (int) minecraft.getWindow().getGuiScale();

        final BlockPos center = center();
        final int xMin = center.getX() - (int)(texWidth * scaleBlockPos / 2.0) - 1;
        final int zMin = center.getZ() - (int)(texHeight * scaleBlockPos / 2.0) - 1;

        final int xPos = (int) ((mouseX - getX()) * guiScale * scaleBlockPos);
        final int zPos = (int) ((mouseY - getY()) * guiScale * scaleBlockPos);

        int quartX = QuartPos.fromBlock(xMin + xPos);
        int quartY = QuartPos.fromBlock(center.getY());
        int quartZ = QuartPos.fromBlock(zMin + zPos);

        // Batch query: fetch biome, height, and all 6 noise channels in a
        // single call to reduce synchronized lock overhead from 8 acquisitions
        // down to 2 (one for the biome/noise y-layer, one for the height layer).
        final short[] batch = workManager.previewStorage().getBatchRawData4(quartX, quartY, quartZ);
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
        final var biomeEntry = biome < dataProvider.previewData().biomeId2BiomeData().length
                ? dataProvider.biome4Id(biome) : null;

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

        int guiScale = (int) minecraft.getWindow().getGuiScale();
        final int xTexPos = (int) (mouseX - getX()) * guiScale;
        final int zTexPos = (int) (mouseY - getY()) * guiScale;

        final int xGridPos = xTexPos / hoverHelperGridCellSize;
        final int zGridPos = zTexPos / hoverHelperGridCellSize;

        final List<StructHoverHelperEntry> res = new ArrayList<>();
        for (int x = xGridPos - 1; x <= xGridPos + 1; ++x) {
            for (int z = zGridPos - 1; z <= zGridPos + 1; ++z) {
                if (x < 0 || x >= hoverHelperGridWidth || z < 0 || z >= hoverHelperGridHeight) {
                    continue;
                }
                StructHoverHelperCell cell = hoverHelperGrid[(x * hoverHelperGridHeight) + z];
                for (var entry : cell.entries) {
                    if (entry.boundingBox.isInside(xTexPos, 0, zTexPos)) {
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

    private void updateTooltip(GuiGraphics guiGraphics, double mouseX, double mouseY) {
        // Cache hover info �?re-query when the mouse moves OR the map center
        // changes (including in-progress drag offsets via center()). Hover
        // world coordinates depend on both; mouse-only cache caused stale or
        // blinking tooltips while panning with a still cursor.
        final BlockPos hoverCenter = center();
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

        if (!structuresInfos.isEmpty()) {
            var structure = structuresInfos.get(0).structure;
            var structEntry = dataProvider.structure4Id(structure.structureId());
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
        final float textScale = 0.8F;
        final int panelPadding = 6;
        final int mapLeft = getX() + 4;
        final int mapTop = getY() + 4;
        final int mapRight = getX() + width - 4;
        final int mapBottom = getY() + height - 4;
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

        guiGraphics.enableScissor(getX(), getY(), getX() + width, getY() + height);
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

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!mouseEventLogged) {
            mouseEventLogged = true;
        }
        if (spawnPinMode && event.button() == 1 && isMouseOver(event.x(), event.y())) {
            spawnPinPos = null;
            if (spawnPinCallback != null) {
                spawnPinCallback.accept(null);
            }
            playDownSound(minecraft.getSoundManager());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        // Fix: set focus so Screen dispatches onDrag to this widget
        if (minecraft.screen != null) {
            minecraft.screen.setFocused(this);
        }

                // Spawn pin placement
        if (spawnPinMode && isMouseOver(event.x(), event.y())) {
            if (event.button() == 0) {
                BlockPos pos = screenToBlock(event.x(), event.y());
                if (pos != null) {
                    spawnPinPos = pos;
                    if (spawnPinCallback != null) { spawnPinCallback.accept(pos); }
                    playDownSound(minecraft.getSoundManager());
                }
                return;
            } else if (event.button() == 1) {
                spawnPinPos = null;
                if (spawnPinCallback != null) { spawnPinCallback.accept(null); }
                playDownSound(minecraft.getSoundManager());
                return;
            }
        }

        clicked = true;
        lastDragRenderNanos = System.nanoTime();
        clickMouseX = event.x();
        clickMouseY = event.y();

        // Copy coordinates on right click:
        // - Ctrl+Right click (or always if height unavailable): plain "x y z" / "x ~ z"
        // - Right click: /tp @s x y z  (game-ready teleport)
        if (event.button() == 1 && isMouseOver(event.x(), event.y())) {
            this.playDownSound(minecraft.getSoundManager());

            final HoverInfo hoverInfo = hoveredBiome(event.x(), event.y());
            if (hoverInfo != null) {
                final boolean plain = event.hasControlDown()
                        || hoverInfo.height == Short.MIN_VALUE;
                final String yPart = hoverInfo.height == Short.MIN_VALUE
                        ? "~"
                        : Integer.toString(hoverInfo.height);
                final String coordinates = plain
                        ? String.format("%s %s %s", hoverInfo.blockX, yPart, hoverInfo.blockZ)
                        : String.format("/tp @s %s %s %s", hoverInfo.blockX, yPart, hoverInfo.blockZ);

                minecraft.keyboardHandler.setClipboard(coordinates);
                coordinatesCopiedNanos = System.nanoTime();
                coordinatesCopiedMsg = Component.translatable(
                        "world_preview.preview-display.coordinates.copied",
                        coordinates
                );
            }
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        final double guiScale = minecraft.getWindow().getGuiScale();
        totalDragX -= (dragX * guiScale) * scaleBlockPos;
        totalDragZ -= (dragY * guiScale) * scaleBlockPos;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (clicked) {
            onRelease(event);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        // If we did not click into the canvas at the start, then we ignore this release
        if(clicked == false) {
            return;
        }
        clicked = false;

        double mouseX = event.x();
        double mouseY = event.y();

        // Check if dragged was minimal
        if (Math.abs(totalDragX) <= 4 && Math.abs(totalDragZ) <= 4) {
            HoverInfo hoverInfo = hoveredBiome(mouseX, mouseY);
            if (hoverInfo == null || hoverInfo.entry == null) {
                return;
            }

            super.playDownSound(minecraft.getSoundManager());
            if (selectedBiomeId == hoverInfo.entry.id()) {
                dataProvider.onBiomeVisuallySelected(null);
            } else {
                dataProvider.onBiomeVisuallySelected(hoverInfo.entry);
            }
        }

        // Finalize drag: commit the live center, then force a confirmatory
        // sample queue for the final viewport.  During drag, queueGeneration()
        // already samples on a 50ms cadence from the drag center; release still
        // re-queues so the last throttle window (and any pending range) is not lost.
        final boolean didDrag = Math.abs(totalDragX) > 4 || Math.abs(totalDragZ) > 4;
        renderSettings.setCenter(center());
        lastRenderedCenter = null;
        lastDragRenderNanos = 0;
        lastDragQueueNanos = 0;

        totalDragX = 0;
        totalDragZ = 0;

        if (didDrag) {
            lastQueuedRange = null;
            queueGeneration();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        synchronized (dataProvider) {
            if (dataProvider.isUpdating()) {
                return true;
            }
            double delta = deltaX + deltaY;
            if (delta == 0.0) {
                return true;
            }

            // Ctrl+scroll always zooms; otherwise honor scrollWheelZooms setting.
            // Alt+scroll always adjusts Y (even when zoom mode is on).
            var window = minecraft.getWindow();
            boolean ctrl = InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                    || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL);
            boolean alt = InputConstants.isKeyDown(window, InputConstants.KEY_LALT)
                    || InputConstants.isKeyDown(window, InputConstants.KEY_RALT);
            boolean zoomMode = config.scrollWheelZooms;
            if (alt) {
                zoomMode = false;
            } else if (ctrl) {
                zoomMode = true;
            }

            if (zoomMode) {
                int before = renderSettings.pixelsPerChunk();
                // Anchor-based zoom: remember the world position under the cursor
                // so we can keep it stationary after zooming (inspired by
                // seedviewer's MapCamera.zoomAt, but adapted for discrete
                // zoom levels instead of continuous).
                final int guiScale = (int) minecraft.getWindow().getGuiScale();
                final int anchorWorldX = center().getX()
                        - (int)(texWidth * scaleBlockPos / 2.0)
                        + (int)((mouseX - getX()) * guiScale * scaleBlockPos);
                final int anchorWorldZ = center().getZ()
                        - (int)(texHeight * scaleBlockPos / 2.0)
                        + (int)((mouseY - getY()) * guiScale * scaleBlockPos);

                if (delta > 0.0) {
                    renderSettings.zoomIn();
                } else {
                    renderSettings.zoomOut();
                }
                if (before != renderSettings.pixelsPerChunk()) {
                    scaleBlockPos = renderSettings.toScaleSpec().blockScale();

                    // Adjust center so the same world position is under the cursor
                    final int newAnchorWorldX = center().getX()
                            - (int)(texWidth * scaleBlockPos / 2.0)
                            + (int)((mouseX - getX()) * guiScale * scaleBlockPos);
                    final int newAnchorWorldZ = center().getZ()
                            - (int)(texHeight * scaleBlockPos / 2.0)
                            + (int)((mouseY - getY()) * guiScale * scaleBlockPos);
                    renderSettings.setCenter(new BlockPos(
                            center().getX() + (anchorWorldX - newAnchorWorldX),
                            center().getY(),
                            center().getZ() + (anchorWorldZ - newAnchorWorldZ)
                    ));

                    dataVisualizer.updateRenderContext(
                            minimapImg, minimapTexture, colorMap, texWidth, texHeight, scaleBlockPos);
                    invalidateRenderCache();
                    lastQueuedRange = null;
                    queueGeneration();
                    transientHudNanos = System.nanoTime();
                    transientHudMsg = Component.translatable(
                            "world_preview.preview-display.hud.zoom",
                            renderSettings.pixelsPerChunk()
                    );
                }
            } else {
                if (delta > 0.0) {
                    renderSettings.decrementY();
                } else {
                    renderSettings.incrementY();
                }
                invalidateRenderCache();
                lastQueuedRange = null;
                queueGeneration();
                transientHudNanos = System.nanoTime();
                transientHudMsg = Component.translatable(
                        "world_preview.preview-display.hud.y",
                        center().getY()
                );
            }
            return true;
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (dataProvider.isUpdating()) {
            return super.keyPressed(event);
        }
        // Arrow keys pan the map (16 blocks * scale) when the preview is focused.
        int step = Math.max(16, (int)(16 * scaleBlockPos));
        boolean handled = false;
        if (event.isLeft()) {
            renderSettings.setCenter(center().offset(-step, 0, 0));
            handled = true;
        } else if (event.isRight()) {
            renderSettings.setCenter(center().offset(step, 0, 0));
            handled = true;
        } else if (event.isUp()) {
            renderSettings.setCenter(center().offset(0, 0, -step));
            handled = true;
        } else if (event.isDown()) {
            renderSettings.setCenter(center().offset(0, 0, step));
            handled = true;
        } else if (event.key() == InputConstants.KEY_HOME) {
            renderSettings.resetCenter();
            handled = true;
        }
        if (handled) {
            totalDragX = 0;
            totalDragZ = 0;
            invalidateRenderCache();
            lastQueuedRange = null;
            queueGeneration();
            return true;
        }
        return super.keyPressed(event);
    }

    private static int textureColor(int orig) {
        // MC 1.21.6+: NativeImage stores pixels in ARGB format (not ABGR as in 1.21).
        // Only add full alpha; do NOT swap R and B channels.
        return orig | (0xFF << 24);
    }

    private static int highlightColor(int orig) {
        int R = (orig >> 0) & 0xFF;
        int G = (orig >> 8) & 0xFF;
        int B = (orig >> 16) & 0xFF;

        final int diff = ((R + G + B) / 3) > 200 ? -100 : 100;

        R += diff;
        G += diff;
        B += diff;
        R = Math.max(Math.min(R, 255), 0);
        G = Math.max(Math.min(G, 255), 0);
        B = Math.max(Math.min(B, 255), 0);
        return (0xFF << 24) | (B << 16) | (G << 8) | R;
    }

    private static int grayScale(int orig) {
        int R = (orig >> 0) & 0xFF;
        int G = (orig >> 8) & 0xFF;
        int B = (orig >> 16) & 0xFF;

        final int gray = Math.max(32, Math.min(256 - 32, (R + G + B) / 3));
        return (0xFF << 24) | (gray << 16) | (gray << 8) | gray;
    }

    /**
     * Negative values for none
     */
    public void setSelectedBiomeId(short biomeId) {
        selectedBiomeId = biomeId;
        // Invalidate render cache so the next frame re-renders with the new highlight
        lastRenderedCenter = null;
    }

    public void setHighlightCaves(boolean highlightCaves) {
        this.highlightCaves = highlightCaves;
        // Invalidate render cache so the next frame re-renders with the new highlight
        lastRenderedCenter = null;
    }

    /**
     * Exports the current preview image to a PNG file in the game directory.
     * @return the path to the saved file, or null on failure
     */
    public String exportImage() {
        try {
            if (previewImg == null) return null;
            final java.io.File dir = new java.io.File(Minecraft.getInstance().gameDirectory, "world_preview_exports");
            dir.mkdirs();
            final String timestamp = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd_HH-mm-ss")
                    .format(java.time.LocalDateTime.now());
            final java.io.File outFile = new java.io.File(dir, "preview_" + timestamp + ".png");
            previewImg.writeToFile(outFile);
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            WorldPreview.LOGGER.error("Failed to export preview image", e);
            return null;
        }
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
        lastRenderedCenter = null;
        cachedRenderData = null;
        lastWriteCounter = -1;
        lastDragRenderNanos = 0;
        lastDragQueueNanos = 0;
        // Drop the queued-range dedup guard so the next frame re-evaluates the
        // range instead of short-circuiting against a stale pre-change range.
        lastQueuedRange = null;
        // Force at least one queueRange() call and one full render cycle
        // so the display is never stuck showing stale or empty content.
        needsInitialQueue = true;
        textureNeedsUpload = true;
        initialDataReceived = false;
        dataVisualizer.invalidateCache();
        // Reset mouse interaction state in case a mouse press was interrupted
        // by a screen change (e.g. opening TerrainExportScreen while dragging).
        // Without this, clicked=true can persist and cause the widget to think
        // the mouse is still held down after returning from the sub-screen.
        clicked = false;
        totalDragX = 0;
        totalDragZ = 0;
        // Invalidate hover cache so the tooltip re-queries on the next frame.
        lastMouseX = -1;
        lastMouseZ = -1;
        lastHoverCenterX = Integer.MIN_VALUE;
        lastHoverCenterY = Integer.MIN_VALUE;
        lastHoverCenterZ = Integer.MIN_VALUE;
        cachedHoverInfo = null;
        cachedStructInfos = null;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Nothing to do
    }

    private record HoverInfo(
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

    private record StructHoverHelperCell(List<StructHoverHelperEntry> entries) {
    }

    private record StructHoverHelperEntry(BoundingBox boundingBox, PreviewSection.PreviewStruct structure) {
    }
}
