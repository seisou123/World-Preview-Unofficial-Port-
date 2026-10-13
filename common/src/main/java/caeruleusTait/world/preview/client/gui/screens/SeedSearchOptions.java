// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreviewConfig;

/**
 * Session-level advanced seed search options shared by the seed search screens.
 * Held by {@link PreviewContainer} so the values survive screen round-trips
 * (options sub-page and main seed screen); they are deliberately not written
 * back to the config, mirroring the former in-screen sliders.
 */
public final class SeedSearchOptions {

    /** Point the distance-limited criteria are measured from. */
    public enum Anchor { CENTER, ORIGIN }

    public Anchor anchor = Anchor.CENTER;
    /** Minimum relative area (%) a biome must cover around a sample point. */
    public int minAreaPercent;
    /** Maximum distance (blocks) to the nearest biome match; 0 = unlimited. */
    public int biomeMaxDistance;
    /** Maximum distance (blocks) between the anchor and the structure criterion. */
    public int structureDistance = 512;
    /** Maximum number of random seeds tried per search. */
    public int attempts = 100;
    /** Number of ranked hits returned per search. */
    public int hits = 1;

    private SeedSearchOptions() {
    }

    /**
     * Initial values mirroring the former slider defaults: the two criteria
     * thresholds are seeded from the config, the rest keep the sliders'
     * built-in defaults.
     */
    public static SeedSearchOptions fromConfig(WorldPreviewConfig cfg) {
        SeedSearchOptions options = new SeedSearchOptions();
        if (cfg != null) {
            options.minAreaPercent = Math.max(0, Math.min(100, cfg.searchMinAreaPercent));
            // Clamped to the former biome distance slider's range so the search
            // uses exactly the value the UI shows.
            options.biomeMaxDistance = Math.max(0, Math.min(4096, cfg.searchMaxDistance));
        }
        return options;
    }
}
