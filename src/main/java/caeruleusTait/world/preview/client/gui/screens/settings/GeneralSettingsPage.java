package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.client.gui.screens.PreviewContainer;
import caeruleusTait.world.preview.domain.ui.ConfigBinding;
import caeruleusTait.world.preview.domain.ui.PageCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

import java.util.List;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.*;

public class GeneralSettingsPage extends AbstractSettingsPage {

    private final WorldPreviewConfig cfg;
    private final PreviewContainer previewContainer;

    public GeneralSettingsPage(WorldPreviewConfig cfg, PreviewContainer previewContainer) {
        super("general", PageCategory.GENERAL);
        this.cfg = cfg;
        this.previewContainer = previewContainer;
    }

    @Override
    public void build(ScreenRectangle area, PreviewContainer previewContainer) {
        widgets.clear();

        int x = area.left() + SettingsTheme.CONTENT_PADDING;
        int y = area.top() + 4;
        int rowH = SettingsTheme.ROW_HEIGHT + SettingsTheme.ROW_VSPACE;
        Minecraft mc = Minecraft.getInstance();

        // Title
        widgets.add(new StringWidget(x, y, contentWidth(area, x), 12,
                SETTINGS_GENERAL_HEAD, mc.font));
        y += 20;

        // ===== Section 1: Sampling =====
        widgets.add(new StringWidget(x, y, contentWidth(area, x), 12,
                SETTINGS_GENERAL_SECTION_SAMPLING, mc.font));
        y += 16;

        // Threads slider
        int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        int currentThreads = Math.max(1, Math.min(maxThreads, cfg.numThreads()));
        Component threadsLabel = Component.translatable("world_preview.settings.general.threads", currentThreads);
        y = addSliderRow(x, y, area, threadsLabel,
                currentThreads, 1, maxThreads,
                SETTINGS_GENERAL_THREADS_TOOLTIP,
                val -> cfg.setNumThreads(val));
        y += rowH;

        // Sampling toggles
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_FC, cfg.buildFullVertChunk,
                SETTINGS_GENERAL_FC_TOOLTIP,
                val -> cfg.buildFullVertChunk = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_BG, cfg.backgroundSampleVertChunk,
                SETTINGS_GENERAL_BG_TOOLTIP,
                val -> cfg.backgroundSampleVertChunk = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_STRUCT, cfg.sampleStructures,
                SETTINGS_GENERAL_STRUCT_TOOLTIP,
                val -> cfg.sampleStructures = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_HEIGHTMAP, cfg.sampleHeightmap,
                SETTINGS_GENERAL_HEIGHTMAP_TOOLTIP,
                val -> cfg.sampleHeightmap = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_INTERSECT, cfg.sampleIntersections,
                SETTINGS_GENERAL_INTERSECT_TOOLTIP,
                val -> cfg.sampleIntersections = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_NOISE, cfg.storeNoiseSamples,
                SETTINGS_GENERAL_NOISE_TOOLTIP,
                val -> cfg.storeNoiseSamples = val);
        y += rowH;

        // ===== Section 2: Display =====
        y += 8;
        widgets.add(new StringWidget(x, y, contentWidth(area, x), 12,
                SETTINGS_GENERAL_SECTION_DISPLAY, mc.font));
        y += 16;

        y = addCheckboxRow(x, y, SETTINGS_GENERAL_BIOME_COUNTS, cfg.showBiomeCounts,
                SETTINGS_GENERAL_BIOME_COUNTS_TOOLTIP, val -> cfg.showBiomeCounts = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_ANALYSIS_BUTTON, cfg.showAnalysisButton,
                SETTINGS_GENERAL_ANALYSIS_BUTTON_TOOLTIP, val -> cfg.showAnalysisButton = val);
        y += rowH;
        y = addSliderRow(x, y, area, SETTINGS_GENERAL_SEARCH_MIN_AREA,
                cfg.searchMinAreaPercent, 0, 100, SETTINGS_GENERAL_SEARCH_MIN_AREA_TOOLTIP,
                val -> cfg.searchMinAreaPercent = val);
        y += rowH;
        y = addSliderRow(x, y, area, SETTINGS_GENERAL_SEARCH_MAX_DISTANCE,
                cfg.searchMaxDistance, 0, 10000, SETTINGS_GENERAL_SEARCH_MAX_DISTANCE_TOOLTIP,
                val -> cfg.searchMaxDistance = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_CONTROLS, cfg.showControls,
                SETTINGS_GENERAL_CONTROLS_TOOLTIP,
                val -> cfg.showControls = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_FRAMETIME, cfg.showFrameTime,
                SETTINGS_GENERAL_FRAMETIME_TOOLTIP,
                val -> cfg.showFrameTime = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_MINIMAP, cfg.showMinimap,
                SETTINGS_GENERAL_MINIMAP_TOOLTIP,
                val -> cfg.showMinimap = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_STATISTICS, cfg.showStatistics,
                SETTINGS_GENERAL_STATISTICS_TOOLTIP,
                val -> cfg.showStatistics = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_COORDINATES, cfg.showCoordinates,
                SETTINGS_GENERAL_COORDINATES_TOOLTIP,
                val -> cfg.showCoordinates = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_ZOOM, cfg.scrollWheelZooms,
                SETTINGS_GENERAL_ZOOM_TOOLTIP,
                val -> cfg.scrollWheelZooms = val);
        y += rowH;

        // ===== Section 3: Other =====
        y += 8;
        widgets.add(new StringWidget(x, y, contentWidth(area, x), 12,
                SETTINGS_GENERAL_SECTION_OTHER, mc.font));
        y += 16;

        // Preload
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_PRELOAD, cfg.enablePreload,
                SETTINGS_GENERAL_PRELOAD_TOOLTIP,
                val -> cfg.enablePreload = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_PRELOAD_IDLE,
                cfg.preloadOnlyWhenIdle, SETTINGS_GENERAL_PRELOAD_IDLE_TOOLTIP,
                val -> cfg.preloadOnlyWhenIdle = val);
        y += rowH;
        y = addSliderRow(x, y, area, SETTINGS_GENERAL_PRELOAD_RADIUS,
                cfg.preloadRadius, 0, 512, SETTINGS_GENERAL_PRELOAD_RADIUS_TOOLTIP,
                val -> cfg.preloadRadius = val);
        y += rowH;

        // Menu toggles
        y += 4;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_SHOW_IN_MENU, cfg.showInPauseMenu,
                SETTINGS_GENERAL_SHOW_IN_MENU_TOOLTIP,
                val -> cfg.showInPauseMenu = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_GENERAL_SHOW_PLAYER, cfg.showPlayer,
                SETTINGS_GENERAL_SHOW_PLAYER_TOOLTIP,
                val -> cfg.showPlayer = val);
        y += rowH;

        // ===== Section 4: Terrain Enhancement =====
        y += 8;
        widgets.add(new StringWidget(x, y, contentWidth(area, x), 12,
                Component.translatable("world_preview.settings.general.section.terrain"), mc.font));
        y += 16;

        y = addCheckboxRow(x, y, SETTINGS_NOISE_GRADIENTS, cfg.usePerNoiseTypeGradients,
                SETTINGS_NOISE_GRADIENTS_TOOLTIP,
                val -> cfg.usePerNoiseTypeGradients = val);
        y += rowH;

        y = addCheckboxRow(x, y, SETTINGS_HILLSHADE, cfg.enableHillshade,
                SETTINGS_HILLSHADE_TOOLTIP,
                val -> cfg.enableHillshade = val);
        y += rowH;
        y = addSliderRow(x, y, area,
                Component.translatable("world_preview.settings.general.hillshade_azimuth", (int) cfg.hillshadeAzimuth),
                (int) cfg.hillshadeAzimuth, 0, 360, null,
                val -> cfg.hillshadeAzimuth = val);
        y += rowH;
        y = addSliderRow(x, y, area,
                Component.translatable("world_preview.settings.general.hillshade_altitude", (int) cfg.hillshadeAltitude),
                (int) cfg.hillshadeAltitude, 0, 90, null,
                val -> cfg.hillshadeAltitude = val);
        y += rowH;
        y = addSliderRow(x, y, area,
                Component.translatable("world_preview.settings.general.hillshade_ambient", cfg.hillshadeAmbient),
                (int) (cfg.hillshadeAmbient * 100), 0, 100, null,
                val -> cfg.hillshadeAmbient = val / 100f);
        y += rowH;
        y = addSliderRow(x, y, area,
                Component.translatable("world_preview.settings.general.hillshade_exaggeration", cfg.hillshadeExaggeration),
                (int) (cfg.hillshadeExaggeration * 10), 1, 50, null,
                val -> cfg.hillshadeExaggeration = val / 10f);
        y += rowH;

        y = addCheckboxRow(x, y, SETTINGS_CONTOURS, cfg.enableContours,
                SETTINGS_CONTOURS_TOOLTIP,
                val -> cfg.enableContours = val);
        y += rowH;
        y = addSliderRow(x, y, area,
                Component.translatable("world_preview.settings.general.contour_interval", cfg.contourInterval),
                cfg.contourInterval, 5, 64, SETTINGS_CONTOUR_INTERVAL_TOOLTIP,
                val -> cfg.contourInterval = val);
        y += rowH;
        y = addCheckboxRow(x, y, SETTINGS_CONTOUR_MINOR, cfg.contourMinorLines,
                SETTINGS_CONTOUR_MINOR_TOOLTIP,
                val -> cfg.contourMinorLines = val);
        y += rowH;

        // Export button
        y += 8;
        Button exportBtn = Button.builder(SETTINGS_GENERAL_EXPORT, b -> {
            String path = previewContainer.previewDisplay().exportImage();
            Component resultMsg = path != null
                    ? Component.translatable("world_preview.preview.export_image.success", path)
                    : Component.translatable("world_preview.preview.export_image.failed");
            b.setMessage(resultMsg);
            if (path != null) {
                WorldPreview.LOGGER.info("Preview image exported to: {}", path);
            } else {
                WorldPreview.LOGGER.warn("Failed to export preview image");
            }
            if (Minecraft.getInstance().player != null) {
Minecraft.getInstance().player.sendSystemMessage(resultMsg);
            }
            new java.util.Timer(true).schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    Minecraft.getInstance().execute(() -> b.setMessage(SETTINGS_GENERAL_EXPORT));
                }
            }, 3000);
        }).bounds(x, y, controlWidth(area, x), 20).build();
        exportBtn.setTooltip(Tooltip.create(SETTINGS_GENERAL_EXPORT_TOOLTIP));
        widgets.add(exportBtn);
        y += rowH;

        y += 4;
        Button terrainExportBtn = Button.builder(TERRAIN_EXPORT_OPEN, b -> {
            previewContainer.openTerrainExportScreen();
        }).bounds(x, y, controlWidth(area, x), 20).build();
        terrainExportBtn.setTooltip(Tooltip.create(TERRAIN_EXPORT_OPEN_TOOLTIP));
        widgets.add(terrainExportBtn);
    }

    @Override
    public void reset() {
        cfg.setNumThreads(Math.max(Runtime.getRuntime().availableProcessors() - 1, 1));
        cfg.buildFullVertChunk = false;
        cfg.backgroundSampleVertChunk = false;
        cfg.sampleStructures = false;
        cfg.sampleHeightmap = false;
        cfg.sampleIntersections = false;
        cfg.storeNoiseSamples = false;
        cfg.showControls = true;
        cfg.showFrameTime = false;
        cfg.showMinimap = false;
        cfg.showStatistics = false;
        cfg.showCoordinates = false;
        cfg.scrollWheelZooms = false;
        cfg.enablePreload = true;
        cfg.preloadOnlyWhenIdle = true;
        cfg.preloadRadius = 128;
        cfg.showInPauseMenu = true;
        cfg.showPlayer = true;
    }
}
