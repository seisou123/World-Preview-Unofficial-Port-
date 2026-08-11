package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.backend.export.TerrainCategory;
import caeruleusTait.world.preview.backend.export.TerrainExportController;
import caeruleusTait.world.preview.backend.export.TerrainExportSpec;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.screens.settings.SettingsTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.time.Duration;

/**
 * Terrain map export screen (sub-screen switch mode).
 * Adapted for MC 26.2 GuiGraphics API.
 */
public final class TerrainExportScreen extends Screen {

    private enum Tab { EXPORT, SETTINGS }

    private final Screen parent;
    private final PreviewContainer previewContainer;

    private int coverageRadius = TerrainExportSpec.DEFAULT_COVERAGE_RADIUS;
    private int blocksPerPixel = TerrainExportSpec.DEFAULT_BLOCKS_PER_PIXEL;
    private boolean exportContours = true;
    private int contourInterval = 10;

    private Tab currentTab = Tab.EXPORT;

    private AbstractSliderButton radiusSlider;
    private AbstractSliderButton resolutionSlider;
    private AbstractSliderButton contourSlider;
    private Checkbox contourCheckbox;
    private Button exportButton;
    private Button cancelButton;
    private Button tabExportBtn;
    private Button tabSettingsBtn;
    private StringWidget imageSizeLabel;

    private static final int MX = 8;
    private static final int TY = 28;
    private static final int SH = 20;
    private static final int LH = 12;
    private static final int GAP = 4;

    public TerrainExportScreen(Screen parent, PreviewContainer previewContainer) {
        super(WorldPreviewComponents.TERRAIN_EXPORT_TITLE);
        this.parent = parent;
        this.previewContainer = previewContainer;
    }

    @Override
    protected void init() {
        clearWidgets();

        int tabW = (width - MX * 2 - GAP) / 2;
        tabExportBtn = Button.builder(
                Component.translatable("world_preview.terrain_export.tab_export"),
                b -> switchTab(Tab.EXPORT))
                .bounds(MX, TY, tabW, SH).build();
        tabSettingsBtn = Button.builder(
                Component.translatable("world_preview.terrain_export.tab_settings"),
                b -> switchTab(Tab.SETTINGS))
                .bounds(MX + tabW + GAP, TY, tabW, SH).build();
        addRenderableWidget(tabExportBtn);
        addRenderableWidget(tabSettingsBtn);

        int contentY = TY + SH + GAP * 2;
        int contentW = Math.min(340, width - MX * 2);

        if (currentTab == Tab.EXPORT) {
            initExportTab(contentY, contentW);
        } else {
            initSettingsTab(contentY, contentW);
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(width - 90, height - 28, 80, SH).build());

        updateTabStyles();
        updateButtonStates();
    }

    private void switchTab(Tab tab) {
        currentTab = tab;
        init();
    }

    private void updateTabStyles() {
        tabExportBtn.active = currentTab != Tab.EXPORT;
        tabSettingsBtn.active = currentTab != Tab.SETTINGS;
    }

    private void initExportTab(int y, int w) {
        int x = MX;
        exportButton = Button.builder(WorldPreviewComponents.TERRAIN_EXPORT_START,
                b -> doExport()).bounds(x, y, w / 2 - 2, SH).build();
        cancelButton = Button.builder(WorldPreviewComponents.TERRAIN_EXPORT_CANCEL,
                b -> previewContainer.cancelTerrainExport()).bounds(x + w / 2 + 2, y, w / 2 - 2, SH).build();
        addRenderableWidget(exportButton);
        addRenderableWidget(cancelButton);
    }

    private void initSettingsTab(int y, int w) {
        int x = MX;

        addRenderableWidget(new StringWidget(x, y, w, LH,
                WorldPreviewComponents.TERRAIN_EXPORT_RADIUS, Minecraft.getInstance().font));
        y += LH + 2;
        radiusSlider = createRadiusSlider(x, y, w);
        addRenderableWidget(radiusSlider);
        y += SH + GAP + 4;

        addRenderableWidget(new StringWidget(x, y, w, LH,
                WorldPreviewComponents.TERRAIN_EXPORT_RESOLUTION, Minecraft.getInstance().font));
        y += LH + 2;
        resolutionSlider = createResolutionSlider(x, y, w);
        addRenderableWidget(resolutionSlider);
        y += SH + GAP + 4;

        int imgW = computeImageSize();
        imageSizeLabel = new StringWidget(x, y, w, LH,
                Component.translatable("world_preview.terrain_export.image_size", imgW, imgW),
                Minecraft.getInstance().font);
        addRenderableWidget(imageSizeLabel);
        y += LH + GAP + 4;

        var center = previewContainer.previewDisplay().center();
        addRenderableWidget(new StringWidget(x, y, w, LH,
                Component.translatable("world_preview.terrain_export.center",
                        center.getX(), center.getZ()), Minecraft.getInstance().font));
        y += LH + GAP + 8;

        contourCheckbox = Checkbox.builder(
                Component.translatable("world_preview.terrain_export.contours"),
                Minecraft.getInstance().font)
                .selected(exportContours)
                .onValueChange((box, val) -> {
                    exportContours = val;
                    if (contourSlider != null) contourSlider.active = val;
                })
                .build();
        contourCheckbox.setPosition(x, y);
        addRenderableWidget(contourCheckbox);
        y += SH + GAP;

        contourSlider = createContourSlider(x, y, w);
        contourSlider.active = exportContours;
        addRenderableWidget(contourSlider);
    }

    private int computeImageSize() {
        return (coverageRadius * 2) / blocksPerPixel;
    }

    private void refreshImageSizeLabel() {
        if (imageSizeLabel != null) {
            int imgW = computeImageSize();
            imageSizeLabel.setMessage(Component.translatable("world_preview.terrain_export.image_size", imgW, imgW));
        }
    }

    private AbstractSliderButton createRadiusSlider(int x, int y, int w) {
        int min = TerrainExportSpec.MIN_COVERAGE_RADIUS;
        int max = TerrainExportSpec.MAX_COVERAGE_RADIUS;
        int range = max - min;
        double initial = range == 0 ? 0.0 : (double) (coverageRadius - min) / range;
        return new AbstractSliderButton(x, y, w, SH,
                Component.translatable("world_preview.terrain_export.radius_value", coverageRadius), initial) {
            @Override protected void updateMessage() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                setMessage(Component.translatable("world_preview.terrain_export.radius_value", v));
            }
            @Override protected void applyValue() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                coverageRadius = Math.max(min, Math.min(max, v));
                refreshImageSizeLabel();
            }
        };
    }

    private AbstractSliderButton createResolutionSlider(int x, int y, int w) {
        int min = TerrainExportSpec.MIN_BLOCKS_PER_PIXEL;
        int max = TerrainExportSpec.MAX_BLOCKS_PER_PIXEL;
        int range = max - min;
        double initial = range == 0 ? 0.0 : (double) (blocksPerPixel - min) / range;
        return new AbstractSliderButton(x, y, w, SH,
                Component.translatable("world_preview.terrain_export.resolution_value", blocksPerPixel), initial) {
            @Override protected void updateMessage() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                setMessage(Component.translatable("world_preview.terrain_export.resolution_value", v));
            }
            @Override protected void applyValue() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                blocksPerPixel = Math.max(min, Math.min(max, v));
                refreshImageSizeLabel();
            }
        };
    }

    private AbstractSliderButton createContourSlider(int x, int y, int w) {
        int min = 5, max = 64, range = max - min;
        double initial = range == 0 ? 0.0 : (double) (contourInterval - min) / range;
        return new AbstractSliderButton(x, y, w, SH,
                Component.translatable("world_preview.terrain_export.contour_interval", contourInterval), initial) {
            @Override protected void updateMessage() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                setMessage(Component.translatable("world_preview.terrain_export.contour_interval", v));
            }
            @Override protected void applyValue() {
                int v = range == 0 ? min : (int) Math.round(this.value * range + min);
                contourInterval = Math.max(min, Math.min(max, v));
            }
        };
    }

    private void doExport() {
        var center = previewContainer.previewDisplay().center();
        var spec = new TerrainExportSpec(coverageRadius, blocksPerPixel, center.getX(), center.getZ(),
                exportContours, contourInterval);
        previewContainer.startTerrainExport(spec);
        if (currentTab != Tab.EXPORT) switchTab(Tab.EXPORT);
    }

    private void updateButtonStates() {
        if (exportButton == null || cancelButton == null) return;
        TerrainExportController.Status status = previewContainer.terrainExportStatus();
        boolean running = status.state() == TerrainExportController.State.RUNNING;
        exportButton.active = !running;
        cancelButton.active = running;
        if (radiusSlider != null) radiusSlider.active = !running;
        if (resolutionSlider != null) resolutionSlider.active = !running;
        if (contourCheckbox != null) contourCheckbox.active = !running;
        if (contourSlider != null) contourSlider.active = !running && exportContours;
    }

    @Override
    public void tick() {
        super.tick();
        updateButtonStates();
        updateTabStyles();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, SettingsTheme.BACKGROUND);
        graphics.drawCenteredString(font, WorldPreviewComponents.TERRAIN_EXPORT_TITLE, width / 2, 8, SettingsTheme.TEXT);
        super.render(graphics, mouseX, mouseY, partialTick);

        if (currentTab == Tab.EXPORT) {
            renderProgressBar(graphics);
            renderStatus(graphics);
            renderLegendColors(graphics);
        }
    }

    private int exportContentStartY() {
        return TY + SH + GAP * 2;
    }

    private void renderProgressBar(GuiGraphics graphics) {
        int x = MX;
        int y = exportContentStartY() + SH + GAP + 4;
        int w = Math.min(340, width - MX * 2);

        TerrainExportController.Status status = previewContainer.terrainExportStatus();
        double pct = status.percentage();

        graphics.fill(x, y, x + w, y + 12, 0xFF333344);
        int fillW = (int) (w * pct / 100.0);
        if (fillW > 0) {
            int color = status.state() == TerrainExportController.State.RUNNING
                    ? SettingsTheme.PRIMARY : SettingsTheme.SUCCESS;
            graphics.fill(x, y, x + fillW, y + 12, color);
        }
    }

    private void renderStatus(GuiGraphics graphics) {
        int x = MX;
        int y = exportContentStartY() + SH + GAP + 4 + 16;

        TerrainExportController.Status status = previewContainer.terrainExportStatus();

        Component statusLine = switch (status.state()) {
            case IDLE -> WorldPreviewComponents.TERRAIN_EXPORT_IDLE;
            case RUNNING -> Component.translatable("world_preview.terrain_export.exporting",
                    String.format("%.1f", status.percentage()));
            case COMPLETED -> WorldPreviewComponents.TERRAIN_EXPORT_COMPLETE;
            case CANCELLED -> WorldPreviewComponents.TERRAIN_EXPORT_CANCELLED;
            case FAILED -> Component.translatable("world_preview.terrain_export.failed",
                    status.errorMessage() != null ? status.errorMessage() : "");
        };
        graphics.drawString(font, statusLine, x, y, SettingsTheme.TEXT);
        y += 14;

        if (status.state() != TerrainExportController.State.IDLE) {
            String elapsed = formatDuration(status.elapsedNanos());
            String eta = status.estimatedRemainingNanos() < 0L
                    ? "--:--" : formatDuration(status.estimatedRemainingNanos());
            graphics.drawString(font,
                    Component.translatable("world_preview.terrain_export.timing", elapsed, eta),
                    x, y, SettingsTheme.TEXT_DIM);
            y += 14;
        }

        if (status.outputPath() != null) {
            String path = status.outputPath().toAbsolutePath().normalize().toString();
            int screenMaxW = (width * 3) / 4;
            int drawX = x;
            int drawY = y;
            StringBuilder line = new StringBuilder();
            int lineW = 0;

            for (int i = 0; i < path.length(); i++) {
                char ch = path.charAt(i);
                int charW = font.width(String.valueOf(ch));
                if (drawX + lineW + charW > x + screenMaxW && line.length() > 0) {
                    graphics.drawString(font, Component.literal(line.toString()), drawX, drawY, SettingsTheme.TEXT_DIM);
                    drawY += 12;
                    line.setLength(0);
                    lineW = 0;
                }
                line.append(ch);
                lineW += charW;
            }
            if (line.length() > 0) {
                graphics.drawString(font, Component.literal(line.toString()), drawX, drawY, SettingsTheme.TEXT_DIM);
            }
        }
    }

    private void renderLegendColors(GuiGraphics graphics) {
        int x = MX;
        int y = exportContentStartY() + SH + GAP + 4 + 16 + 14 * 4 + 8;

        Component legendTitle = WorldPreviewComponents.TERRAIN_EXPORT_LEGEND;
        graphics.drawString(font, legendTitle, x, y, SettingsTheme.TEXT);
        int titleW = font.width(legendTitle);

        int itemStartX = x + titleW + 8;
        int colorSize = 10;
        int colorTextGap = 3;
        int itemGap = 8;
        int itemY = y;
        int colorY = itemY - 1;

        TerrainCategory[] cats = TerrainCategory.values();
        int curX = itemStartX;
        int maxRight = width - MX;

        for (int i = 0; i < cats.length; i++) {
            Component catLabel = Component.translatable(
                    "world_preview.terrain_export.category." + cats[i].name().toLowerCase());
            int textW = font.width(catLabel);
            int itemW = colorSize + colorTextGap + textW;

            if (curX + itemW > maxRight) {
                curX = itemStartX;
                itemY += 14;
                colorY = itemY - 1;
            }

            int rgb = cats[i].pixelColor();
            graphics.fill(curX, colorY, curX + colorSize, colorY + colorSize, rgb);
            graphics.fill(curX, colorY, curX + colorSize, colorY + 1, 0xFF000000);
            graphics.fill(curX, colorY + colorSize - 1, curX + colorSize, colorY + colorSize, 0xFF000000);
            graphics.fill(curX, colorY, curX + 1, colorY + colorSize, 0xFF000000);
            graphics.fill(curX + colorSize - 1, colorY, curX + colorSize, colorY + colorSize, 0xFF000000);

            graphics.drawString(font, catLabel, curX + colorSize + colorTextGap, itemY, SettingsTheme.TEXT);

            curX += itemW + itemGap;
        }
    }

    private static String formatDuration(long nanos) {
        long seconds = Math.max(0L, Duration.ofNanos(nanos).toSeconds());
        long hours = seconds / 3600L;
        long minutes = seconds % 3600L / 60L;
        long remainingSeconds = seconds % 60L;
        return hours > 0L
                ? String.format("%d:%02d:%02d", hours, minutes, remainingSeconds)
                : String.format("%02d:%02d", minutes, remainingSeconds);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (super.keyPressed(event)) return true;
        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
