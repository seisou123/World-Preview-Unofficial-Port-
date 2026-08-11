// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.analysis;

import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import it.unimi.dsi.fastutil.shorts.Short2LongOpenHashMap;

/**
 * Analyzes biome rarity within the visible viewport and computes
 * highlight colors for rare biomes.
 * <p>
 * This system works as a real-time statistical analyzer:
 * <ul>
 *   <li>During each render frame, biome pixel counts are fed into the
 *       analyzer via {@link #recordBiome(short)}.</li>
 *   <li>At the end of the frame, {@link #computeRarityScores()} computes
 *       a rarity score for each biome (0 = most common, 1 = rarest).</li>
 *   <li>The rarity scores are used to modulate the biome's color: rare
 *       biomes receive a luminous glow proportional to their rarity,
 *       while common biomes are slightly dimmed to create contrast.</li>
 *   <li>A "discovery threshold" can be set so that only biomes appearing
 *       below a certain frequency threshold are highlighted, simulating
 *       a "rare biome detector" that draws the explorer's attention.</li>
 * </ul>
 * <p>
 * The highlight effect uses a multi-layered approach:
 * <ol>
 *   <li><b>Luminance boost</b> — rare biomes are brightened using a
 *       perceptual luminance model (not simple RGB multiplication).</li>
 *   <li><b>Halo effect</b> — a subtle warm halo is added to very rare
 *       biomes, creating a "glowing" appearance that's immediately
 *       noticeable even in dense biome maps.</li>
 *   <li><b>Contrast dimming</b> — common biomes are slightly desaturated
 *       to further emphasize rare ones, without making the map unreadable.</li>
 * </ol>
 */
public final class BiomeRarityAnalyzer {

    /** Minimum pixel count for a biome to be considered "present". */
    private static final long MIN_PRESENCE_THRESHOLD = 1;

    /** Rarity score above which a biome gets the halo effect. */
    private static final float HALO_THRESHOLD = 0.7f;

    private final Short2LongOpenHashMap pixelCounts = new Short2LongOpenHashMap();
    private final Short2LongOpenHashMap rarityScores = new Short2LongOpenHashMap();

    private boolean enabled = false;
    private float discoveryThreshold = 0.05f; // biomes below 5% of viewport
    private float highlightIntensity = 0.5f;
    private float dimIntensity = 0.15f;

    private long totalPixels = 0;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setDiscoveryThreshold(float threshold) {
        this.discoveryThreshold = Math.max(0f, Math.min(0.5f, threshold));
    }

    public void setHighlightIntensity(float intensity) {
        this.highlightIntensity = Math.max(0f, Math.min(1f, intensity));
    }

    /**
     * Reset all statistics for a new render frame.
     */
    public void beginFrame() {
        pixelCounts.clear();
        totalPixels = 0;
    }

    /**
     * Record a biome pixel during the render pass.
     */
    public void recordBiome(short biomeId) {
        if (!enabled) return;
        if (biomeId < 0) return;
        pixelCounts.addTo(biomeId, 1);
        totalPixels++;
    }

    /**
     * Compute rarity scores for all recorded biomes.
     * Must be called after {@link #beginFrame()} and all {@link #recordBiome} calls.
     */
    public void computeRarityScores() {
        rarityScores.clear();
        if (totalPixels < MIN_PRESENCE_THRESHOLD) return;

        // Compute frequency (proportion) of each biome
        Short2LongMap frequencies = new Short2LongOpenHashMap();
        long maxCount = 0;
        for (short id : pixelCounts.keySet()) {
            long count = pixelCounts.get(id);
            if (count > maxCount) maxCount = count;
        }

        // Rarity score: 1 - (count / maxCount)
        // Most common biome gets score 0, rarest gets score closest to 1
        for (short id : pixelCounts.keySet()) {
            long count = pixelCounts.get(id);
            float frequency = (float) count / totalPixels;
            float rarity;
            if (maxCount == 0) {
                rarity = 0f;
            } else {
                // Rarity is based on inverse frequency, but using a
                // logarithmic scale so that the difference between
                // 1% and 2% is more significant than between 20% and 21%.
                rarity = 1f - (float) Math.log(1 + count) / (float) Math.log(1 + maxCount);
            }
            rarityScores.put(id, Float.floatToRawIntBits(rarity));
        }
    }

    /**
     * Check if a biome is considered "rare" (below the discovery threshold).
     */
    public boolean isRare(short biomeId) {
        if (!enabled || totalPixels == 0) return false;
        long count = pixelCounts.get(biomeId);
        if (count < MIN_PRESENCE_THRESHOLD) return false;
        float frequency = (float) count / totalPixels;
        return frequency < discoveryThreshold;
    }

    /**
     * Get the rarity score for a biome (0 = most common, 1 = rarest).
     */
    public float rarityScore(short biomeId) {
        if (!enabled || !rarityScores.containsKey(biomeId)) return 0f;
        return Float.intBitsToFloat((int) rarityScores.get(biomeId));
    }

    /**
     * Apply rarity-based highlight to a biome color.
     *
     * @param biomeId  the biome's short ID
     * @param baseColor the biome's base ARGB color
     * @return highlighted ARGB color
     */
    public int applyHighlight(short biomeId, int baseColor) {
        if (!enabled) return baseColor;

        float rarity = rarityScore(biomeId);
        if (rarity <= 0f) {
            // Most common biome — apply slight dimming for contrast
            return dimColor(baseColor, dimIntensity);
        }

        // Apply luminance boost proportional to rarity
        int boosted = luminanceBoost(baseColor, rarity * highlightIntensity);

        // Apply halo effect for very rare biomes
        if (rarity > HALO_THRESHOLD) {
            float haloStrength = (rarity - HALO_THRESHOLD) / (1f - HALO_THRESHOLD);
            boosted = addHalo(boosted, haloStrength * highlightIntensity);
        }

        return boosted;
    }

    /**
     * Get the number of unique biomes in the current viewport.
     */
    public int uniqueBiomeCount() {
        return pixelCounts.size();
    }

    /**
     * Get the total pixel count analyzed.
     */
    public long totalPixels() {
        return totalPixels;
    }

    // ==================================================================
    // Color manipulation
    // ==================================================================

    /**
     * Perceptual luminance boost using the Rec. 601 luminance model.
     * Rare biomes are brightened more in the mid-tones than in already-bright areas.
     */
    private static int luminanceBoost(int argb, float strength) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        // Perceptual luminance (Rec. 601)
        float luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;

        // Boost factor: stronger for mid-tones, gentler for already bright/dark
        float boost = strength * (1f - Math.abs(luminance - 0.5f) * 0.5f);

        r = Math.min(255, Math.round(r + (255 - r) * boost * 0.6f));
        g = Math.min(255, Math.round(g + (255 - g) * boost * 0.6f));
        b = Math.min(255, Math.round(b + (255 - b) * boost * 0.6f));

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Add a warm halo glow to very rare biomes.
     */
    private static int addHalo(int argb, float strength) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        // Warm orange halo
        r = Math.min(255, Math.round(r + (255 - r) * strength * 0.5f));
        g = Math.min(255, Math.round(g + (180 - g) * strength * 0.3f));
        b = Math.max(0, Math.round(b * (1f - strength * 0.2f)));

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Slight dimming and desaturation for common biomes to create contrast.
     */
    private static int dimColor(int argb, float strength) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        int gray = (r + g + b) / 3;

        r = Math.round(r * (1 - strength * 0.3f) + gray * strength * 0.3f);
        g = Math.round(g * (1 - strength * 0.3f) + gray * strength * 0.3f);
        b = Math.round(b * (1 - strength * 0.3f) + gray * strength * 0.3f);

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
}
