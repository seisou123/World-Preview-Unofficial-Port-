package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.backend.analysis.AnalysisDataState;
import caeruleusTait.world.preview.backend.analysis.AnalysisProgress;
import caeruleusTait.world.preview.backend.analysis.AnalysisStatus;
import caeruleusTait.world.preview.backend.analysis.BiomeRarity;
import caeruleusTait.world.preview.backend.analysis.RegionMetrics;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class AnalysisPanel extends AbstractWidget {
    private static final int MAX_SPAWN_REASONS = 3;
    private static final int MAX_TOP_BIOMES = 5;

    private static final int COLOR_TEXT = 0xFFE0E4E8;
    private static final int COLOR_SCORE_GOOD = 0xFF55FF55;
    private static final int COLOR_SCORE_MEDIUM = 0xFFFFFF55;
    private static final int COLOR_SCORE_BAD = 0xFFFF5555;

    private RegionMetrics metrics;
    private AnalysisProgress progress;
    @Nullable private Integer spawnScore;
    private List<Component> spawnReasons = List.of();
    private List<BiomeRarity.RarityRow> topBiomes = List.of();

    public AnalysisPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("world_preview.analysis.metrics"));
    }

    public void setMetrics(RegionMetrics metrics) {
        this.metrics = metrics;
    }

    public void setProgress(AnalysisProgress progress) {
        this.progress = progress;
    }

    /**
     * Update the spawn score section. Passing {@code null} clears the section.
     *
     * @param score   spawn score 0..100, or null for "no data"
     * @param reasons translated reason lines (capped for display)
     */
    public void setSpawnAnalysis(@Nullable Integer score, List<Component> reasons) {
        this.spawnScore = score;
        this.spawnReasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** Update the top-biomes section; the panel shows at most {@value #MAX_TOP_BIOMES} rows. */
    public void setTopBiomes(List<BiomeRarity.RarityRow> rows) {
        this.topBiomes = rows == null ? List.of() : List.copyOf(rows);
    }

    public RegionMetrics metrics() {
        return metrics;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int textX = getX() + 6;
        int textY = getY() + 6;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xAA101418);
        graphics.text(Minecraft.getInstance().font, Component.translatable("world_preview.analysis.metrics"), textX, textY, 0xFFFFFFFF);
        if (metrics == null) {
            drawLine(graphics, textX, textY + 18, Component.translatable("world_preview.analysis.pending"));
            return;
        }
        AnalysisStatus runStatus = progress == null ? AnalysisStatus.QUEUED : progress.status();
        drawLine(graphics, textX, textY + 18, Component.translatable("world_preview.analysis.status", runStatusLabel(runStatus)));
        drawLine(graphics, textX, textY + 32, Component.translatable("world_preview.analysis.sample_state", sampleStatus(metrics.state())));
        drawLine(graphics, textX, textY + 46, Component.translatable("world_preview.analysis.coverage", percent(metrics.coverage())));
        if (progress != null) {
            drawLine(graphics, textX, textY + 60, Component.translatable("world_preview.analysis.progress.count",
                    progress.completedUnits(), progress.totalUnits()));
            drawLine(graphics, textX, textY + 74, Component.translatable("world_preview.analysis.biomes", metrics.biomeCounts().size()));
            drawLine(graphics, textX, textY + 88, Component.translatable("world_preview.analysis.height.range", optional(metrics.minHeight()), optional(metrics.maxHeight())));
            drawLine(graphics, textX, textY + 102, Component.translatable("world_preview.analysis.height.mean", optional(metrics.meanHeight())));
            drawLine(graphics, textX, textY + 116, Component.translatable("world_preview.analysis.slope", optional(metrics.meanSlope()), optional(metrics.maxSlope())));
            drawLine(graphics, textX, textY + 130, Component.translatable("world_preview.analysis.flat", percent(metrics.flatRatio())));
            if (metrics.state() == AnalysisDataState.UNAVAILABLE && !metrics.unavailableReason().isBlank()) {
                drawLine(graphics, textX, textY + 144, Component.translatable("world_preview.analysis.unavailable.reason", metrics.unavailableReason()));
            }
            // Spawn score + top biomes sections (appended below the metric rows).
            int sectionY = drawSpawnSection(graphics, textX, textY + 158);
            drawTopBiomesSection(graphics, textX, sectionY);
        } else {
            drawLine(graphics, textX, textY + 60, Component.translatable("world_preview.analysis.biomes", metrics.biomeCounts().size()));
            drawLine(graphics, textX, textY + 74, Component.translatable("world_preview.analysis.height.range", optional(metrics.minHeight()), optional(metrics.maxHeight())));
            drawLine(graphics, textX, textY + 88, Component.translatable("world_preview.analysis.height.mean", optional(metrics.meanHeight())));
            drawLine(graphics, textX, textY + 102, Component.translatable("world_preview.analysis.slope", optional(metrics.meanSlope()), optional(metrics.maxSlope())));
            drawLine(graphics, textX, textY + 116, Component.translatable("world_preview.analysis.flat", percent(metrics.flatRatio())));
            if (metrics.state() == AnalysisDataState.UNAVAILABLE && !metrics.unavailableReason().isBlank()) {
                drawLine(graphics, textX, textY + 130, Component.translatable("world_preview.analysis.unavailable.reason", metrics.unavailableReason()));
            }
            // Spawn score + top biomes sections (appended below the metric rows).
            int sectionY = drawSpawnSection(graphics, textX, textY + 144);
            drawTopBiomesSection(graphics, textX, sectionY);
        }
    }

    /** Draws the spawn score section; returns the next free y (== y when skipped). */
    private int drawSpawnSection(GuiGraphicsExtractor graphics, int textX, int y) {
        if (spawnScore == null && spawnReasons.isEmpty()) {
            return y;
        }
        graphics.text(Minecraft.getInstance().font, WorldPreviewComponents.ANALYSIS_SPAWN_SCORE, textX, y, 0xFFFFFFFF);
        int line = y + 12;
        if (spawnScore != null) {
            drawLine(graphics, textX + 6, line,
                    WorldPreviewComponents.ANALYSIS_SPAWN_SCORE_VALUE.copy().append(spawnScore.toString()), scoreColor(spawnScore));
        }
        for (int i = 0; i < Math.min(MAX_SPAWN_REASONS, spawnReasons.size()); i++) {
            line += 11;
            drawLine(graphics, textX + 6, line, spawnReasons.get(i), COLOR_TEXT);
        }
        return line + 14;
    }

    /** Draws the top-biomes section; returns the next free y (== y when skipped). */
    private int drawTopBiomesSection(GuiGraphicsExtractor graphics, int textX, int y) {
        if (topBiomes.isEmpty()) {
            return y;
        }
        graphics.text(Minecraft.getInstance().font, WorldPreviewComponents.ANALYSIS_TOP_BIOMES, textX, y, 0xFFFFFFFF);
        int line = y + 12;
        for (int i = 0; i < Math.min(MAX_TOP_BIOMES, topBiomes.size()); i++) {
            BiomeRarity.RarityRow row = topBiomes.get(i);
            String stars = row.stars() > 0 ? " " + "★".repeat(row.stars()) : "";
            String text = row.name() + " §7" + String.format(Locale.ROOT, "%.1f%%", row.sharePercent()) + "§r" + stars;
            drawLine(graphics, textX + 6, line, Component.literal(text), COLOR_TEXT);
            line += 11;
        }
        return line + 14;
    }

    /** Score coloring: green >= 70, yellow >= 40, red below. */
    private static int scoreColor(int score) {
        if (score >= 70) {
            return COLOR_SCORE_GOOD;
        }
        if (score >= 40) {
            return COLOR_SCORE_MEDIUM;
        }
        return COLOR_SCORE_BAD;
    }

    private void drawLine(GuiGraphicsExtractor graphics, int x, int y, Component text) {
        drawLine(graphics, x, y, text, COLOR_TEXT);
    }

    private void drawLine(GuiGraphicsExtractor graphics, int x, int y, Component text, int color) {
        graphics.text(Minecraft.getInstance().font, text, x, y, color);
    }

    private static String runStatusLabel(AnalysisStatus status) {
        return Component.translatable("world_preview.analysis.run." + status.name().toLowerCase(Locale.ROOT)).getString();
    }

    private static String sampleStatus(AnalysisDataState state) {
        return Component.translatable("world_preview.analysis.state." + state.name().toLowerCase(Locale.ROOT)).getString();
    }

    private static String optional(java.util.OptionalInt value) {
        return value.isPresent() ? Integer.toString(value.getAsInt()) : "—";
    }

    private static String optional(java.util.OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, "%.2f", value.getAsDouble()) : "—";
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // Display-only: never consume clicks that should reach buttons underneath/nearby.
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
