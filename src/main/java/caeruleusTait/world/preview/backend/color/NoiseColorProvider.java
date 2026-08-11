// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.color;

import caeruleusTait.world.preview.RenderSettings.RenderMode;

import java.util.List;
import java.util.Map;

/**
 * Provides per-noise-type color gradients for the noise render modes.
 * <p>
 * Each noise parameter (temperature, humidity, continentalness, etc.) gets its
 * own dedicated color gradient so that switching between noise views produces
 * visually distinct maps rather than the same colormap recolored.
 * <p>
 * Interpolation smoothness is configurable per noise type:
 * <ul>
 *   <li>{@code labBlend} — 0.0 = pure sRGB lerp (direct, hard transitions);
 *       1.0 = pure Lab lerp (perceptually smooth). Values in between mix the
 *       two for a controlled balance.</li>
 *   <li>{@code quantizeLevels} — when > 0, snaps the continuous gradient to
 *       N discrete color bands, producing a topographic-map appearance.</li>
 * </ul>
 * <p>
 * The intent is that temperature and humidity (which have large semantic
 * ranges) get fewer, more distinct bands; depth, continentalness and erosion
 * get moderate banding; while weirdness and peaks/valleys remain nearly
 * smooth for fine-grained visualization.
 */
public final class NoiseColorProvider {

    /** Number of discrete color steps in the baked lookup table. */
    public static final int TABLE_SIZE = 1024;

    private final Map<RenderMode, int[]> bakedTables;

    public NoiseColorProvider() {
        this.bakedTables = bakeAll();
    }

    /**
     * Returns the baked ARGB color table for the given noise render mode.
     * The table maps normalized short values (index 512 = 0.0) to colors.
     *
     * @param mode one of the {@code NOISE_*} render modes
     * @return 1024-entry ARGB color table
     */
    public int[] tableFor(RenderMode mode) {
        return bakedTables.getOrDefault(mode, fallbackTable());
    }

    // ------------------------------------------------------------------
    // Gradient baking with per-type smoothness control
    // ------------------------------------------------------------------

    /**
     * Bake a color table with controlled interpolation and optional quantization.
     *
     * @param gradient       the source color map
     * @param labBlend       0 = pure RGB lerp, 1 = pure Lab lerp, between = mix
     * @param quantizeLevels 0 = continuous, >0 = snap to N discrete bands
     * @return 1024-entry ARGB color table
     */
    private static int[] bakeWithSmoothness(ColorMap gradient, float labBlend, int quantizeLevels) {
        int[] table = new int[TABLE_SIZE];
        for (int i = 0; i < TABLE_SIZE; i++) {
            float pos = (float) i / TABLE_SIZE;
            // Apply quantization: snap to N discrete levels
            if (quantizeLevels > 0) {
                pos = (float) Math.round(pos * quantizeLevels) / quantizeLevels;
                pos = Math.min(1.0f, pos);
            }
            table[i] = gradient.getARGBBlended(pos, labBlend);
        }
        return table;
    }

    private static ColorMap gradient(String name, float[]... stops) {
        List<List<Float>> data = new java.util.ArrayList<>(stops.length);
        for (float[] rgb : stops) {
            data.add(List.of(rgb[0], rgb[1], rgb[2]));
        }
        return new ColorMap(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("world_preview", "noise_" + name),
                new ColorMap.RawColorMap(name, data)
        );
    }

    private static Map<RenderMode, int[]> bakeAll() {
        Map<RenderMode, int[]> result = new java.util.EnumMap<>(RenderMode.class);

        // Temperature: cold blue → green → yellow → hot red
        // Large adjustment: mostly RGB lerp (direct), 8 distinct bands
        result.put(RenderMode.NOISE_TEMPERATURE, bakeWithSmoothness(
                gradient("temperature",
                        new float[]{0.15f, 0.20f, 0.70f},
                        new float[]{0.20f, 0.55f, 0.75f},
                        new float[]{0.30f, 0.70f, 0.40f},
                        new float[]{0.90f, 0.85f, 0.25f},
                        new float[]{0.80f, 0.25f, 0.10f}
                ),
                0.15f,  // mostly RGB lerp — hard transitions
                8       // 8 distinct color bands — topographic style
        ));

        // Humidity / Vegetation: dry brown → green → dark lush green
        // Large adjustment: mostly RGB lerp, 8 bands
        result.put(RenderMode.NOISE_HUMIDITY, bakeWithSmoothness(
                gradient("humidity",
                        new float[]{0.60f, 0.48f, 0.28f},
                        new float[]{0.45f, 0.58f, 0.30f},
                        new float[]{0.25f, 0.55f, 0.20f},
                        new float[]{0.10f, 0.32f, 0.12f}
                ),
                0.15f,
                8
        ));

        // Continentalness: deep ocean → shallow → coast → land → highland
        // Medium adjustment: mixed lerp, 16 bands
        result.put(RenderMode.NOISE_CONTINENTALNESS, bakeWithSmoothness(
                gradient("continentalness",
                        new float[]{0.08f, 0.15f, 0.40f},
                        new float[]{0.20f, 0.40f, 0.65f},
                        new float[]{0.35f, 0.60f, 0.55f},
                        new float[]{0.40f, 0.65f, 0.30f},
                        new float[]{0.55f, 0.45f, 0.30f}
                ),
                0.40f,  // balanced RGB/Lab mix
                16      // moderate banding
        ));

        // Erosion: low (stable teal) → medium (lavender) → high (pink)
        // Medium adjustment: mixed lerp, 16 bands
        result.put(RenderMode.NOISE_EROSION, bakeWithSmoothness(
                gradient("erosion",
                        new float[]{0.20f, 0.58f, 0.58f},
                        new float[]{0.40f, 0.45f, 0.60f},
                        new float[]{0.70f, 0.50f, 0.65f},
                        new float[]{0.90f, 0.65f, 0.72f}
                ),
                0.40f,
                16
        ));

        // Depth: below sea (dark blue) → surface (green-brown) → above (tan)
        // Medium adjustment: mixed lerp, 16 bands
        result.put(RenderMode.NOISE_DEPTH, bakeWithSmoothness(
                gradient("depth",
                        new float[]{0.15f, 0.20f, 0.45f},
                        new float[]{0.25f, 0.40f, 0.55f},
                        new float[]{0.40f, 0.55f, 0.35f},
                        new float[]{0.65f, 0.55f, 0.30f}
                ),
                0.40f,
                16
        ));

        // Weirdness: low (warm orange) → mid (muted blue) → high (violet)
        // Slight adjustment: mostly Lab lerp, no quantization
        result.put(RenderMode.NOISE_WEIRDNESS, bakeWithSmoothness(
                gradient("weirdness",
                        new float[]{0.92f, 0.62f, 0.20f},
                        new float[]{0.55f, 0.50f, 0.45f},
                        new float[]{0.35f, 0.30f, 0.60f},
                        new float[]{0.65f, 0.30f, 0.78f}
                ),
                0.85f,  // mostly Lab — perceptually smooth
                0       // no quantization — continuous gradient
        ));

        // Peaks and Valleys: valley (green) → flat (grey) → peak (warm)
        // Slight adjustment: mostly Lab lerp, no quantization
        result.put(RenderMode.NOISE_PEAKS_AND_VALLEYS, bakeWithSmoothness(
                gradient("peaks_and_valleys",
                        new float[]{0.25f, 0.50f, 0.30f},
                        new float[]{0.45f, 0.48f, 0.40f},
                        new float[]{0.60f, 0.55f, 0.50f},
                        new float[]{0.80f, 0.65f, 0.35f}
                ),
                0.85f,
                0
        ));

        return java.util.Map.copyOf(result);
    }

    private static int[] fallbackTable() {
        int[] table = new int[TABLE_SIZE];
        java.util.Arrays.fill(table, 0xFF808080);
        return table;
    }
}
