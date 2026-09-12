// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.backend.analysis.AnalysisDataState;
import caeruleusTait.world.preview.backend.analysis.AnalysisProgress;
import caeruleusTait.world.preview.backend.analysis.AnalysisStatus;
import caeruleusTait.world.preview.backend.analysis.RegionMetrics;
import caeruleusTait.world.preview.backend.analysis.SpawnAdvisor;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.PanelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.locale.Language;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Panelized replacement for the old two-branch {@code AnalysisPanel}: the
 * overview section model is rebuilt once per data change and rendered in a
 * single pass (status → metrics grid → spawn score → reasons → nearest
 * structures → error), clipping at the panel bottom instead of maintaining a
 * hardcoded offset table per branch.
 *
 * <p>Display-only widget: it never consumes clicks. The screen pushes data
 * through the {@code setXxx} setters; every setter rebuilds the section list.</p>
 */
public final class AnalysisOverviewPanel extends AbstractWidget {
    /** One renderable section; render() draws at (x, yCursor) and returns the next y. */
    private interface Section {
        int render(GuiGraphics g, Font font, int x, int y, int w, int bottom);
    }

    private static final int COLOR_TEXT = 0xFFE0E4E8;
    private static final int COLOR_HEADER = 0xFFFFFFFF;
    private static final int COLOR_SCORE_GOOD = 0xFF55FF55;
    private static final int COLOR_SCORE_MEDIUM = 0xFFFFFF55;
    private static final int COLOR_SCORE_BAD = 0xFFFF5555;
    private static final int COLOR_NEUTRAL = 0xFF888888;
    private static final int COLOR_BLUE = 0xFF5555FF;
    private static final int COLOR_BAR_SLOT = 0xFF2A2A38;

    private static final int MAX_SPAWN_REASONS = 3;
    private static final int MAX_STRUCTURE_ROWS = 4;

    /** Translation keys of the four spawn-bar segments, in part order. */
    private static final Component[] SPAWN_PART_LABELS = {
            WorldPreviewComponents.ANALYSIS_SPAWN_PART_FLAT,
            WorldPreviewComponents.ANALYSIS_SPAWN_PART_SLOPE,
            WorldPreviewComponents.ANALYSIS_SPAWN_PART_WATER,
            WorldPreviewComponents.ANALYSIS_SPAWN_PART_STRUCTURE,
    };

    private final List<Section> sections = new ArrayList<>();

    @Nullable private RegionMetrics metrics;
    @Nullable private AnalysisProgress progress;
    private int sampleStep = 1;
    private int analysisY;
    @Nullable private Integer spawnScore;
    /** Part scores in order flat/slope/water/structure; null = no spawn data. */
    @Nullable private int[] spawnParts;
    private List<Component> spawnReasons = List.of();
    /** Nearest structure per type (probe order); null = probing unavailable. */
    @Nullable private Map<Identifier, BlockPos> nearestStructures;
    private int structureCenterX;
    private int structureCenterZ;
    @Nullable private Component errorText;

    public AnalysisOverviewPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("world_preview.analysis.metrics"));
    }

    public void setSessionData(@Nullable AnalysisProgress progress, int analysisY, int sampleStep) {
        this.progress = progress;
        this.analysisY = analysisY;
        this.sampleStep = sampleStep;
        rebuildSections();
    }

    public void setMetrics(@Nullable RegionMetrics metrics) {
        this.metrics = metrics;
        rebuildSections();
    }

    /**
     * Update the spawn score section. Passing {@code null} as score clears it.
     *
     * @param score  spawn score 0..100, or null for "no data"
     * @param parts  the four part scores (flat/slope/water/structure) the screen
     *               computed with the same formula and public constants as
     *               {@link SpawnAdvisor}; null to draw the score without the bar
     * @param reasons translated reason lines (capped for display)
     */
    public void setSpawn(@Nullable Integer score, @Nullable int[] parts, List<Component> reasons) {
        this.spawnScore = score;
        this.spawnParts = parts == null ? null : parts.clone();
        this.spawnReasons = reasons == null ? List.of() : List.copyOf(reasons);
        rebuildSections();
    }

    /**
     * Update the nearest-structures section. The region center is needed here
     * because the panel renders Manhattan distances ("name  350m") itself.
     *
     * @param structures id → position of the nearest structure per type in probe
     *                   order; {@code null} means probing is unavailable (section hidden),
     *                   an empty map renders the "none within range" hint
     */
    public void setStructures(@Nullable Map<Identifier, BlockPos> structures, int centerX, int centerZ) {
        this.nearestStructures = structures;
        this.structureCenterX = centerX;
        this.structureCenterZ = centerZ;
        rebuildSections();
    }

    /** Sets the error line (FAILED status / stale context); null clears it. */
    public void setError(@Nullable Component error) {
        this.errorText = error;
        rebuildSections();
    }

    /** Rebuilds the renderable section list in display order; empty data skips its section. */
    private void rebuildSections() {
        sections.clear();
        sections.add(this::renderStatusSection);
        if (metrics != null) {
            sections.add(this::renderMetricsSection);
        }
        if (spawnScore != null) {
            sections.add(this::renderSpawnSection);
        }
        if (!spawnReasons.isEmpty()) {
            sections.add(this::renderReasonsSection);
        }
        if (nearestStructures != null) {
            sections.add(this::renderStructuresSection);
        }
        if (errorText != null) {
            sections.add(this::renderErrorSection);
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        PanelRenderer.panelBackground(graphics, getX(), getY(), width, height);
        Font font = Minecraft.getInstance().font;
        int x = getX() + PanelRenderer.PANEL_PAD;
        int w = width - PanelRenderer.PANEL_PAD * 2;
        int bottom = getY() + height - PanelRenderer.PANEL_PAD;
        int y = getY() + PanelRenderer.PANEL_PAD;
        // Content clipping: sections past the panel bottom are cut off.
        graphics.enableScissor(getX(), getY(), getX() + width, getY() + height);
        for (Section section : sections) {
            if (y >= bottom) {
                break;
            }
            y = section.render(graphics, font, x, y, Math.max(0, w), bottom);
        }
        graphics.disableScissor();
    }

    // ===== Sections =====

    private int renderStatusSection(GuiGraphics g, Font font, int x, int y, int w, int bottom) {
        if (metrics == null) {
            return line(g, font, x, y, Component.translatable("world_preview.analysis.pending"), COLOR_TEXT);
        }
        AnalysisStatus status = progress == null ? AnalysisStatus.QUEUED : progress.status();
        int dot = switch (status) {
            case RUNNING, COMPLETED -> COLOR_SCORE_GOOD;
            case PAUSED -> COLOR_SCORE_MEDIUM;
            case FAILED -> COLOR_SCORE_BAD;
            case QUEUED, CANCELLED -> COLOR_NEUTRAL;
        };
        g.fill(x, y + 2, x + 5, y + 7, dot);
        y = line(g, font, x + 9, y, Component.translatable("world_preview.analysis.status", runStatusLabel(status)), COLOR_TEXT);
        if (progress != null) {
            long total = Math.max(1, progress.totalUnits());
            double pct = Math.min(100.0, progress.completedUnits() * 100.0 / total);
            PanelRenderer.progressBar(g, x, y, w, pct, dot);
            y += 14;
            y = line(g, font, x, y, Component.translatable("world_preview.analysis.progress.count",
                    progress.completedUnits(), progress.totalUnits()), COLOR_TEXT);
            // Sample-point counts live in the metrics grid below only — a
            // second "Points" line here duplicated it verbatim.
        }
        if (metrics.state() == AnalysisDataState.UNAVAILABLE && !metrics.unavailableReason().isBlank()) {
            y = line(g, font, x, y, Component.translatable("world_preview.analysis.unavailable.reason",
                    metrics.unavailableReason()), COLOR_SCORE_BAD);
        }
        return y + 4;
    }

    /** Two-column metric grid; absent height data renders as "—". */
    private int renderMetricsSection(GuiGraphics g, Font font, int x, int y, int w, int bottom) {
        int colW = Math.max(40, (w - 6) / 2);
        int col2X = x + colW + 6;

        int left = line(g, font, x, y, Component.translatable("world_preview.analysis.points",
                metrics.presentSamples(), metrics.expectedSamples()), COLOR_TEXT);
        left = line(g, font, x, left, Component.translatable("world_preview.analysis.biomes",
                metrics.biomeCounts().size()), COLOR_TEXT);
        left = line(g, font, x, left, Component.translatable("world_preview.analysis.height.range",
                optional(metrics.minHeight()), optional(metrics.maxHeight())), COLOR_TEXT);
        left = line(g, font, x, left, Component.translatable("world_preview.analysis.height.mean",
                optional(metrics.meanHeight())), COLOR_TEXT);
        left = line(g, font, x, left, Component.translatable("world_preview.analysis.median_height",
                optional(metrics.medianHeight())), COLOR_TEXT);

        int right = line(g, font, col2X, y, Component.translatable("world_preview.analysis.slope",
                optional(metrics.meanSlope()), optional(metrics.maxSlope())), COLOR_TEXT);
        right = line(g, font, col2X, right, Component.translatable("world_preview.analysis.flat",
                percent(metrics.flatRatio())), COLOR_TEXT);
        right = line(g, font, col2X, right, Component.translatable("world_preview.analysis.stddev",
                optional(metrics.standardDeviation())), COLOR_TEXT);
        right = line(g, font, col2X, right, Component.translatable("world_preview.analysis.water_share",
                percent(metrics.waterShare())), COLOR_TEXT);

        return Math.max(left, right);
    }

    /** Spawn score headline (colored), the 4-segment weighted bar and a part legend. */
    private int renderSpawnSection(GuiGraphics g, Font font, int x, int y, int w, int bottom) {
        y = line(g, font, x, y, WorldPreviewComponents.ANALYSIS_SPAWN_SCORE, COLOR_HEADER);
        y = line(g, font, x, y, Component.translatable("world_preview.analysis.spawn_score.value", spawnScore),
                scoreColor(spawnScore));
        if (spawnParts == null || spawnParts.length < 4) {
            return y + 4;
        }
        // Segment widths are proportional to the spawn-score weights, each
        // segment filled by its part/weight ratio.
        int[] weights = {
                SpawnAdvisor.FLAT_WEIGHT, SpawnAdvisor.SLOPE_WEIGHT,
                SpawnAdvisor.WATER_WEIGHT, SpawnAdvisor.STRUCTURE_WEIGHT};
        int[] colors = {COLOR_SCORE_GOOD, COLOR_SCORE_MEDIUM, COLOR_BLUE, COLOR_NEUTRAL};
        int barW = w;
        int segY = y + 2;
        int segX = x;
        int remaining = barW;
        for (int i = 0; i < 4; i++) {
            int segW = i == 3 ? remaining : (int) Math.round(barW * weights[i] / 100.0);
            segW = Math.max(0, Math.min(segW, remaining));
            remaining -= segW;
            if (segW > 0) {
                g.fill(segX, segY, segX + segW, segY + 6, COLOR_BAR_SLOT);
                double ratio = weights[i] <= 0 ? 0.0
                        : Math.max(0.0, Math.min(1.0, spawnParts[i] / (double) weights[i]));
                int fillW = (int) Math.round(segW * ratio);
                if (fillW > 0) {
                    g.fill(segX, segY, segX + fillW, segY + 6, colors[i]);
                }
            }
            segX += segW;
        }
        y = segY + 8;
        StringBuilder legend = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (legend.length() > 0) {
                legend.append("   ");
            }
            legend.append(SPAWN_PART_LABELS[i].getString()).append(' ')
                    .append(Math.max(0, spawnParts[i])).append('/').append(weights[i]);
        }
        y = line(g, font, x, y, Component.literal(legend.toString()), COLOR_TEXT);
        return y + 4;
    }

    private int renderReasonsSection(GuiGraphics g, Font font, int x, int y, int w, int bottom) {
        for (int i = 0; i < Math.min(MAX_SPAWN_REASONS, spawnReasons.size()); i++) {
            y = line(g, font, x, y, spawnReasons.get(i), COLOR_TEXT);
        }
        return y + 4;
    }

    private int renderStructuresSection(GuiGraphics g, Font font, int x, int y, int w, int bottom) {
        y = line(g, font, x, y, WorldPreviewComponents.ANALYSIS_NEAREST_STRUCTURES, COLOR_HEADER);
        if (nearestStructures.isEmpty()) {
            return line(g, font, x, y, WorldPreviewComponents.ANALYSIS_NEAREST_STRUCTURES_NONE, COLOR_TEXT) + 4;
        }
        int rows = 0;
        for (Map.Entry<Identifier, BlockPos> entry : nearestStructures.entrySet()) {
            if (rows >= MAX_STRUCTURE_ROWS) {
                break;
            }
            int distance = Math.abs(entry.getValue().getX() - structureCenterX)
                    + Math.abs(entry.getValue().getZ() - structureCenterZ);
            y = line(g, font, x, y, Component.translatable("world_preview.analysis.struct_distance",
                    structureDisplayName(entry.getKey()), distance), COLOR_TEXT);
            rows++;
        }
        return y + 4;
    }

    private int renderErrorSection(GuiGraphics g, Font font, int x, int y, int w, int bottom) {
        return line(g, font, x, y, errorText, COLOR_SCORE_BAD);
    }

    // ===== Helpers =====

    /** Structure label: {@code world_preview.structure.<path>} when translated, else the raw path. */
    private static Component structureDisplayName(Identifier id) {
        String key = "world_preview.structure." + id.getPath();
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(id.getPath());
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

    private static String runStatusLabel(AnalysisStatus status) {
        return Component.translatable("world_preview.analysis.run." + status.name().toLowerCase(Locale.ROOT)).getString();
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

    private static int line(GuiGraphics g, Font font, int x, int y, Component text, int color) {
        g.drawString(font, text, x, y, color);
        return y + font.lineHeight + 1;
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
