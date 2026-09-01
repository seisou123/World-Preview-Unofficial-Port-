package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.backend.analysis.SeedSearchService;
import caeruleusTait.world.preview.backend.analysis.SpawnAdvisor;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import caeruleusTait.world.preview.domain.waypoint.WaypointStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Compares the current seed with the saved seeds by sampling the biome
 * composition in a square around the world origin (radius 512, step 16, y=64).
 * For every seed it reports the biome diversity, water share, most common
 * biome and a spawn score; non-numeric seeds and seeds sampled without a
 * worldgen context are marked unavailable.
 *
 * <p>Sampling runs on a single-threaded daemon executor owned by this screen.
 * The worker publishes an immutable snapshot list per finished seed;
 * {@link #tick()} copies it into a render field on the main thread. The cancel
 * flag is checked at every grid row.</p>
 */
public final class SeedComparisonScreen extends Screen {

    /** Sampling square half-extent around the world origin, in blocks. */
    private static final int SAMPLE_RADIUS = 512;
    /** Sampling grid step, in blocks. */
    private static final int SAMPLE_STEP = 16;
    /** Sampling height, in blocks. */
    private static final int SAMPLE_Y = 64;
    /** Maximum number of compared seeds (current seed first). */
    private static final int MAX_SEEDS = 8;
    /** Maximum characters of a seed string shown in the table. */
    private static final int MAX_SEED_CHARS = 16;

    private static final TagKey<Biome> IS_OCEAN = TagKey.create(Registries.BIOME, Identifier.parse("minecraft:is_ocean"));
    private static final TagKey<Biome> IS_RIVER = TagKey.create(Registries.BIOME, Identifier.parse("minecraft:is_river"));
    private static final TagKey<Biome> IS_DEEP_OCEAN = TagKey.create(Registries.BIOME, Identifier.parse("minecraft:is_deep_ocean"));

    private final Screen parent;
    private final PreviewContainer container;
    /** Worldgen epoch captured at construction; comparison aborts when it changes. */
    private final long epochAtOpen;

    /** Seeds to compare, in display order (current seed first), with a stable row color. */
    private final List<SeedLine> seedLines;
    /**
     * Read-only biome classification map: identifier string → biome entry.
     * Built once in the constructor on the main thread, then only read from the
     * worker thread (safe: never mutated after construction).
     */
    private final Map<String, BiomesList.BiomeEntry> biomeByIdentifier;

    private Button compareButton;
    private Button stopButton;
    private Button backButton;

    /** Single-threaded daemon worker; created lazily, shut down in {@link #onClose()}. */
    @Nullable private ExecutorService executor;
    /** Cancel flag, checked at every grid row and between seeds. */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    /** True while a comparison task is executing. */
    private volatile boolean running = false;
    /** Immutable snapshot list published by the worker (one row per finished seed). */
    private volatile List<ComparisonRow> publishedResults = List.of();
    /** Render-side copy of {@link #publishedResults}, refreshed in {@link #tick()}. */
    private List<ComparisonRow> renderRows = List.of();

    /** One compared seed: its string and the stable table row color. */
    private record SeedLine(String seed, int color) {}

    /** Immutable per-seed result; {@code available == false} marks an unusable seed. */
    private record ComparisonRow(
            SeedLine seed,
            boolean available,
            long diversity,
            double waterPercent,
            String topBiome,
            double topBiomePercent,
            int spawnScore) {

        static ComparisonRow unavailable(SeedLine seed) {
            return new ComparisonRow(seed, false, 0, 0.0, "", 0.0, 0);
        }
    }

    public SeedComparisonScreen(Screen parent, PreviewContainer container) {
        this(parent, container, List.of());
    }

    /**
     * Opens the comparison with a preferred seed list (e.g. search hits carried
     * over with their lineage). Preferred seeds come first, then the current
     * seed, then saved seeds; duplicates collapse and the list is capped.
     */
    public SeedComparisonScreen(Screen parent, PreviewContainer container, List<String> preferredSeeds) {
        super(WorldPreviewComponents.COMPARISON_TITLE);
        this.parent = parent;
        this.container = container;
        this.epochAtOpen = container.workManager().epoch();

        // Search hits first (they carry the context the player just searched),
        // then the current seed, then saved seeds; dedupe, preserve order, cap.
        LinkedHashSet<String> uniqueSeeds = new LinkedHashSet<>();
        if (preferredSeeds != null) {
            for (String hit : preferredSeeds) {
                if (uniqueSeeds.size() >= MAX_SEEDS) {
                    break;
                }
                if (hit != null && !hit.isBlank()) {
                    uniqueSeeds.add(hit.trim());
                }
            }
        }
        String currentSeed = container.dataProvider().seed();
        if (currentSeed != null && !currentSeed.isBlank()) {
            uniqueSeeds.add(currentSeed);
        }
        for (String saved : container.worldPreview().cfg().savedSeeds) {
            if (uniqueSeeds.size() >= MAX_SEEDS) {
                break;
            }
            if (saved != null && !saved.isBlank()) {
                uniqueSeeds.add(saved);
            }
        }
        List<SeedLine> lines = new ArrayList<>(uniqueSeeds.size());
        int index = 0;
        for (String seed : uniqueSeeds) {
            lines.add(new SeedLine(seed, WaypointStore.PALETTE[index % WaypointStore.PALETTE.length]));
            index++;
        }
        this.seedLines = List.copyOf(lines);

        // Classification map for water detection; identifiers not in the map count as land.
        Map<String, BiomesList.BiomeEntry> byIdentifier = new HashMap<>();
        for (BiomesList.BiomeEntry entry : container.allBiomes()) {
            byIdentifier.put(entry.entry().key().identifier().toString(), entry);
        }
        this.biomeByIdentifier = Map.copyOf(byIdentifier);
    }

    // ===== Screen lifecycle =====

    @Override
    protected void init() {
        clearWidgets();
        compareButton = Button.builder(WorldPreviewComponents.COMPARISON_START, ignored -> startComparison())
                .size(70, 20).build();
        stopButton = Button.builder(WorldPreviewComponents.COMPARISON_STOP, ignored -> stopComparison())
                .size(70, 20).build();
        stopButton.active = false;
        backButton = Button.builder(CommonComponents.GUI_BACK, ignored -> onClose())
                .size(90, 20).build();
        addRenderableWidget(compareButton);
        addRenderableWidget(stopButton);
        addRenderableWidget(backButton);
        layoutWidgets();
        updateControlState();
    }

    private void layoutWidgets() {
        int left = 8;
        int top = 24;
        compareButton.setPosition(left, top);
        stopButton.setPosition(left + 74, top);
        // Back button on the same row, right-aligned.
        backButton.setPosition(width - 96, top);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        layoutWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        // Copy the worker's immutable snapshot into the render field.
        renderRows = publishedResults;
        updateControlState();
    }

    @Override
    public void onClose() {
        cancelled.set(true);
        if (executor != null) {
            executor.shutdownNow();
        }
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    // ===== Comparison control =====

    private void startComparison() {
        if (running || seedLines.isEmpty()) {
            return;
        }
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "world_preview-seed-comparison");
                thread.setDaemon(true);
                return thread;
            });
        }
        cancelled.set(false);
        publishedResults = List.of();
        renderRows = List.of();
        running = true;
        updateControlState();
        // Capture immutable inputs for the worker; the classification map is a
        // read-only final field safe to read from another thread.
        final List<SeedLine> lines = seedLines;
        @Nullable final SeedSearchService.SeedContextFactory factory = container.seedSearchFactory();
        executor.execute(() -> runComparison(lines, factory));
    }

    private void stopComparison() {
        cancelled.set(true);
    }

    private void updateControlState() {
        boolean hasSeeds = !seedLines.isEmpty();
        compareButton.active = hasSeeds && !running;
        stopButton.active = running;
    }

    // ===== Worker =====

    private void runComparison(List<SeedLine> lines, @Nullable SeedSearchService.SeedContextFactory factory) {
        try {
            for (SeedLine line : lines) {
                if (cancelled.get()) {
                    return;
                }
                // Lineage guard: the comparison factory was captured for a
                // specific worldgen context; once the context is rebuilt
                // (seed/dimension/generator/compat change) its samplers are
                // stale and must not produce rows for the new world.
                if (container.workManager().epoch() != epochAtOpen) {
                    caeruleusTait.world.preview.WorldPreview.LOGGER.info(
                            "Seed comparison aborted: worldgen context changed since opening");
                    return;
                }
                ComparisonRow row = evaluateSeed(line, factory);
                if (row == null) {
                    return; // cancelled mid-seed
                }
                // Publish an immutable snapshot after every seed (single worker thread).
                List<ComparisonRow> next = new ArrayList<>(publishedResults);
                next.add(row);
                publishedResults = List.copyOf(next);
            }
        } finally {
            running = false;
        }
    }

    /**
     * Samples one seed around the world origin. Returns null when cancelled
     * mid-seed; returns an unavailable row for unusable seeds or sampling errors.
     */
    @Nullable
    private ComparisonRow evaluateSeed(SeedLine line, @Nullable SeedSearchService.SeedContextFactory factory) {
        long seedLong;
        try {
            seedLong = Long.parseLong(line.seed().trim());
        } catch (NumberFormatException e) {
            return ComparisonRow.unavailable(line);
        }
        if (factory == null) {
            return ComparisonRow.unavailable(line);
        }
        try (var sampler = factory.createSampler(seedLong)) {
            Map<Identifier, Long> counts = new HashMap<>();
            long waterCount = 0;
            long total = 0;
            for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx += SAMPLE_STEP) {
                if (cancelled.get()) {
                    return null;
                }
                for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS; dz += SAMPLE_STEP) {
                    Identifier biomeId = sampler.biomeAt(dx, SAMPLE_Y, dz);
                    total++;
                    if (biomeId != null) {
                        counts.merge(biomeId, 1L, Long::sum);
                        if (isWater(biomeId)) {
                            waterCount++;
                        }
                    }
                }
            }
            if (cancelled.get()) {
                return null;
            }

            double waterPercent = total == 0 ? 0.0 : waterCount * 100.0 / total;
            double waterShare = total == 0 ? 0.0 : waterCount / (double) total;
            Map.Entry<Identifier, Long> top = counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
            String topName = "";
            double topPercent = 0.0;
            if (top != null) {
                BiomesList.BiomeEntry entry = biomeByIdentifier.get(top.getKey().toString());
                topName = entry != null ? entry.name() : top.getKey().toString();
                topPercent = total == 0 ? 0.0 : top.getValue() * 100.0 / total;
            }
            // Terrain shape data is unknown at this level; score composition only.
            int spawnScore = SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(waterShare, 0.5, null, 0)).score();
            return new ComparisonRow(line, true, counts.size(), waterPercent, topName, topPercent, spawnScore);
        } catch (Exception e) {
            caeruleusTait.world.preview.WorldPreview.LOGGER.warn(
                    "Seed comparison failed for seed {}", line.seed(), e);
            return ComparisonRow.unavailable(line);
        }
    }

    /** Ocean, deep-ocean or river biomes count as water; unknown identifiers as land. */
    private boolean isWater(Identifier biomeId) {
        BiomesList.BiomeEntry entry = biomeByIdentifier.get(biomeId.toString());
        if (entry == null) {
            return false;
        }
        return entry.entry().is(IS_OCEAN) || entry.entry().is(IS_RIVER) || entry.entry().is(IS_DEEP_OCEAN);
    }

    // ===== Rendering =====

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);

        // Progress line next to the buttons.
        Component progress = Component.translatable("world_preview.comparison.progress",
                renderRows.size(), seedLines.size());
        graphics.drawString(font, progress, 160, 29, 0xFFCCCCCC);

        int left = 8;
        int tableTop = 56;
        // Column headers (score column hugs the right edge).
        graphics.drawString(font, WorldPreviewComponents.COMPARISON_COL_DIVERSITY, left + 122, tableTop, 0xFFAAAAAA);
        graphics.drawString(font, WorldPreviewComponents.COMPARISON_COL_WATER, left + 180, tableTop, 0xFFAAAAAA);
        graphics.drawString(font, WorldPreviewComponents.COMPARISON_COL_TOP, left + 240, tableTop, 0xFFAAAAAA);
        graphics.drawString(font, WorldPreviewComponents.COMPARISON_COL_SCORE, width - 78, tableTop, 0xFFAAAAAA);

        int rowY = tableTop + 16;
        for (ComparisonRow row : renderRows) {
            drawRow(graphics, row, left, rowY);
            rowY += 16;
        }
    }

    private void drawRow(GuiGraphics graphics, ComparisonRow row, int left, int y) {
        // Stable colored square for the seed row.
        graphics.fill(left, y + 1, left + 8, y + 9, row.seed().color());
        String seedText = row.seed().seed();
        if (seedText.length() > MAX_SEED_CHARS) {
            seedText = seedText.substring(0, MAX_SEED_CHARS) + "…";
        }
        graphics.drawString(font, seedText, left + 12, y, 0xFFFFFFFF);

        if (!row.available()) {
            graphics.drawString(font, WorldPreviewComponents.COMPARISON_UNAVAILABLE, left + 122, y, 0xFF888888);
            return;
        }
        graphics.drawString(font, String.valueOf(row.diversity()), left + 122, y, 0xFFE0E4E8);
        graphics.drawString(font, String.format(Locale.ROOT, "%.1f%%", row.waterPercent()), left + 180, y, 0xFFE0E4E8);
        graphics.drawString(font,
                Component.literal(row.topBiome() + " §7" + String.format(Locale.ROOT, "%.1f%%", row.topBiomePercent()) + "§r"),
                left + 240, y, 0xFFE0E4E8);
        graphics.drawString(font, String.valueOf(row.spawnScore()), width - 78, y, scoreColor(row.spawnScore()));
    }

    /** Score coloring shared with the analysis panel: green >= 70, yellow >= 40, red below. */
    private static int scoreColor(int score) {
        if (score >= 70) {
            return 0xFF55FF55;
        }
        if (score >= 40) {
            return 0xFFFFFF55;
        }
        return 0xFFFF5555;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (super.keyPressed(event)) {
            return true;
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return false;
    }
}
