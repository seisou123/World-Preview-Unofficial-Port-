package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.backend.analysis.AnalysisDataState;
import caeruleusTait.world.preview.backend.analysis.AnalysisProgress;
import caeruleusTait.world.preview.backend.analysis.AnalysisSession;
import caeruleusTait.world.preview.backend.analysis.AnalysisStatus;
import caeruleusTait.world.preview.backend.analysis.ProfileRequest;
import caeruleusTait.world.preview.backend.analysis.ProfileResult;
import caeruleusTait.world.preview.backend.analysis.Region;
import caeruleusTait.world.preview.backend.analysis.RegionMetrics;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.widgets.AnalysisPanel;
import caeruleusTait.world.preview.client.gui.widgets.ProfileChart;
import caeruleusTait.world.preview.client.gui.widgets.RegionSelector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;

import java.util.ArrayList;
import java.util.List;

public final class WorldAnalysisScreen extends Screen {
    private final Screen parent;
    private final AnalysisSession session;
    private final PreviewContainer previewContainer;
    private final RegionSelector regionSelector;
    private final AnalysisPanel analysisPanel;
    private final ProfileChart profileChart;
    private final List<AbstractWidget> selectorFields;
    private final Button startButton;
    private final Button cancelButton;
    private Button closeButton;
    private Region region;
    private boolean closed;
    private int profileRefreshCooldown;
    private boolean lastRunning;

    public WorldAnalysisScreen(Screen parent, AnalysisSession session,
                               PreviewContainer previewContainer, Region initialRegion) {
        super(WorldPreviewComponents.ANALYSIS_TITLE);
        this.parent = parent;
        this.session = session;
        this.previewContainer = previewContainer;
        this.region = initialRegion;
        this.regionSelector = new RegionSelector(Minecraft.getInstance().font, 0, 0, 300, 45,
                initialRegion, this::setRegion);
        this.analysisPanel = new AnalysisPanel(0, 0, 260, 140);
        this.profileChart = new ProfileChart(0, 0, 360, 170);
        this.selectorFields = new ArrayList<>(regionSelector.fields());
        this.startButton = Button.builder(WorldPreviewComponents.ANALYSIS_START, ignored -> startAnalysis())
                .width(90).build();
        this.cancelButton = Button.builder(WorldPreviewComponents.ANALYSIS_CANCEL, ignored -> cancelAnalysis())
                .width(90).build();
        this.cancelButton.active = false;
    }

    public AnalysisSession session() {
        return session;
    }

    public RegionSelector regionSelector() {
        return regionSelector;
    }

    public AnalysisPanel analysisPanel() {
        return analysisPanel;
    }

    public ProfileChart profileChart() {
        return profileChart;
    }

    private void setRegion(Region region) {
        this.region = region;
        profileChart.setResult(null);
    }

    private void startAnalysis() {
        session.start();
        ProfileRequest request = new ProfileRequest(region.minX(), region.minZ(), region.maxX(), region.maxZ(),
                session.request().y(), session.request().y(), Math.max(1, session.request().sampleStep()), false);
        profileChart.setResult(session.profile(request));
        profileRefreshCooldown = 0;
        updateControlState();
    }

    private void cancelAnalysis() {
        session.cancel();
        updateControlState();
    }

    private void updateControlState() {
        boolean running = session.isRunning();
        startButton.active = !running;
        cancelButton.active = running;
        if (closeButton != null) {
            // Closing must always remain possible.
            closeButton.active = true;
        }
        lastRunning = running;
    }

    @Override
    protected void init() {
        // Clear and rebuild so buttons are always at the end of the click order.
        clearWidgets();
        addRenderableWidget(regionSelector);
        selectorFields.forEach(this::addRenderableWidget);
        addRenderableWidget(analysisPanel);
        addRenderableWidget(profileChart);
        addRenderableWidget(previewContainer.previewDisplay());
        addRenderableWidget(startButton);
        addRenderableWidget(cancelButton);
        closeButton = Button.builder(CommonComponents.GUI_BACK, ignored -> onClose())
                .width(90).build();
        addRenderableWidget(closeButton);
        layoutWidgets();
        updateControlState();
        // Force the preview display to re-render on this screen instead of
        // reusing stale cached render data from the previous screen.
        previewContainer.previewDisplay().invalidateRenderCache();
    }

    private void layoutWidgets() {
        int left = 8;
        int top = 24;
        // Reserve a footer strip so the back button is never covered by charts/panels.
        int footerTop = height - 32;
        int contentBottom = Math.max(top + 80, footerTop - 6);
        int previewWidth = Math.max(180, width / 2 - 18);
        int rightX = width / 2 + 4;
        int rightWidth = Math.max(180, width / 2 - 12);
        int rightHeight = Math.max(1, contentBottom - (top + 24));
        int previewHeight = Math.max(1, rightHeight / 2 - 4);
        int profileHeight = Math.max(1, rightHeight - previewHeight - 4);

        regionSelector.layout(new ScreenRectangle(left, top, previewWidth, 42));
        int fieldX = regionSelector.getX();
        for (int i = 0; i < selectorFields.size(); i++) {
            AbstractWidget field = selectorFields.get(i);
            field.setX(fieldX + i * Math.max(1, (previewWidth - 8) / 4));
            field.setY(top + 18);
        }
        startButton.setX(left);
        startButton.setY(top + 46);
        cancelButton.setX(left + 96);
        cancelButton.setY(top + 46);

        analysisPanel.setX(left);
        analysisPanel.setY(top + 74);
        analysisPanel.setWidth(previewWidth);
        analysisPanel.setHeight(Math.max(80, contentBottom - (top + 74)));

        previewContainer.previewDisplay().setPosition(rightX, top + 24);
        previewContainer.previewDisplay().setSize(rightWidth, previewHeight);
        profileChart.setX(rightX);
        profileChart.setY(top + 24 + previewHeight + 4);
        profileChart.setWidth(rightWidth);
        profileChart.setHeight(profileHeight);

        if (closeButton != null) {
            closeButton.setX(width - 96);
            closeButton.setY(footerTop);
            closeButton.setWidth(90);
            closeButton.setHeight(20);
        }
    }

    public void resize(int width, int height) {
        super.resize(width, height);
        layoutWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        // Lightweight control-state update every tick.
        boolean running = session.isRunning();
        if (running != lastRunning) {
            updateControlState();
        } else {
            startButton.active = !running;
            cancelButton.active = running;
        }

        // Expensive metrics/profile updates only while the analysis is active, and throttled.
        if (!running) {
            // Still refresh final metrics once after a transition to terminal.
            if (lastRunning || profileRefreshCooldown == 0) {
                AnalysisProgress progress = session.progress();
                RegionMetrics metrics = session.result();
                analysisPanel.setMetrics(metrics);
                analysisPanel.setProgress(progress);
                profileRefreshCooldown = 20;
            }
            return;
        }

        if (profileRefreshCooldown > 0) {
            profileRefreshCooldown--;
            return;
        }
        profileRefreshCooldown = 10; // ~0.5s at 20 TPS

        AnalysisProgress progress = session.progress();
        RegionMetrics metrics = session.result();
        analysisPanel.setMetrics(metrics);
        analysisPanel.setProgress(progress);
        if (progress.status() == AnalysisStatus.RUNNING
                || progress.status() == AnalysisStatus.QUEUED
                || metrics.state() == AnalysisDataState.PENDING) {
            ProfileResult result = profileChart.result();
            if (result != null) {
                profileChart.setResult(session.profile(result.request()));
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Draw directly to avoid triggering the screen blur more than once per frame.
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.centeredText(font, WorldPreviewComponents.ANALYSIS_TITLE, width / 2, 8, 0xFFFFFFFF);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // Prefer the back button even if a large display widget still overlaps it.
        if (closeButton != null && closeButton.visible && closeButton.active
                && closeButton.isMouseOver(event.x(), event.y())) {
            return closeButton.mouseClicked(event, doubleClick);
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (super.keyPressed(event)) {
            return true;
        }
        // ESC / inventory key should always leave this screen.
        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        if (closed) return;
        closed = true;
        // Always leave the screen first so a slow cleanup cannot freeze navigation.
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
        try {
            session.close();
        } catch (Throwable ignored) {
            // Ignore close errors so the screen still transitions back.
        }
    }
}
