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
 * All gradients are continuous and interpolated predominantly in L*ab space
 * ({@code labBlend}), so noise maps read as perceptually even heatmaps. The
 * noise field is point-sampled per quart, which carries high-frequency
 * wiggles; quantizing the gradient into coarse bands (an earlier design)
 * turned every one of those wiggles into a hard ragged contour between two
 * saturated fills, which is why banding was dropped in favor of smooth ramps.
 * <p>
 * Temperature, humidity and continentalness stops are anchored to the vanilla
 * overworld biome climate bands (temperature frozen/cool/temperate/warm/hot,
 * humidity dry/&hellip;/wet, continentalness deep-ocean/&hellip;/far-inland), so the
 * color under a region tells you which climate band — and therefore biome
 * family — it belongs to.
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
    // Gradient baking
    // ------------------------------------------------------------------

    private static int[] bake(ColorMap gradient, float labBlend) {
        int[] table = new int[TABLE_SIZE];
        for (int i = 0; i < TABLE_SIZE; i++) {
            table[i] = gradient.getARGBBlended((float) i / TABLE_SIZE, labBlend);
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

        // Temperature: cold blue -> cool teal -> temperate green -> warm yellow
        // -> hot orange-red. Stop positions follow the vanilla biome climate
        // bands, with pos = (value + 1) / 2:
        //   frozen [-1.0,-0.45] -> pos [0, 0.28]   blues
        //   cool   [-0.45,-0.15] -> pos [0.28, 0.43] teal
        //   temperate [-0.15, 0.2] -> pos [0.43, 0.60] green
        //   warm   [0.2, 0.55] -> pos [0.60, 0.78] yellow
        //   hot    [0.55, 1.0] -> pos [0.78, 1.0]  orange-red
        result.put(RenderMode.NOISE_TEMPERATURE, bake(
                gradient("temperature",
                        new float[]{0.11f, 0.24f, 0.60f},
                        new float[]{0.17f, 0.38f, 0.74f},
                        new float[]{0.28f, 0.53f, 0.78f},
                        new float[]{0.36f, 0.65f, 0.64f},
                        new float[]{0.45f, 0.72f, 0.42f},
                        new float[]{0.64f, 0.76f, 0.30f},
                        new float[]{0.86f, 0.76f, 0.28f},
                        new float[]{0.90f, 0.52f, 0.20f},
                        new float[]{0.76f, 0.24f, 0.14f}
                ),
                0.8f   // mostly Lab lerp — perceptually smooth heatmap
        ));

        // Humidity / Vegetation: dry sand -> steppe -> grass -> forest -> lush.
        // Vanilla humidity bands: dry [-1,-0.35] -> pos [0, 0.33], mid [-0.35, 0.1]
        // -> pos [0.33, 0.55], humid [0.1, 0.3] -> pos [0.55, 0.65], wet [0.3, 1]
        // -> pos [0.65, 1].
        result.put(RenderMode.NOISE_HUMIDITY, bake(
                gradient("humidity",
                        new float[]{0.74f, 0.64f, 0.38f},
                        new float[]{0.66f, 0.62f, 0.32f},
                        new float[]{0.52f, 0.64f, 0.30f},
                        new float[]{0.36f, 0.64f, 0.28f},
                        new float[]{0.22f, 0.56f, 0.26f},
                        new float[]{0.08f, 0.38f, 0.24f}
                ),
                0.8f
        ));

        // Continentalness: deep ocean -> ocean -> coast -> lowland -> highland
        // -> far inland. Vanilla bands: deep ocean [-1,-0.455] -> pos [0, 0.27],
        // ocean [-0.455,-0.19] -> pos [0.27, 0.41], coast [-0.19,-0.11] ->
        // pos [0.41, 0.45], mid inland [-0.11, 0.03] -> pos [0.45, 0.52],
        // high inland [0.03, 0.3] -> pos [0.52, 0.65], far inland [0.3, 1] ->
        // pos [0.65, 1].
        result.put(RenderMode.NOISE_CONTINENTALNESS, bake(
                gradient("continentalness",
                        new float[]{0.09f, 0.17f, 0.40f},
                        new float[]{0.13f, 0.26f, 0.52f},
                        new float[]{0.20f, 0.40f, 0.64f},
                        new float[]{0.34f, 0.58f, 0.62f},
                        new float[]{0.44f, 0.68f, 0.46f},
                        new float[]{0.48f, 0.68f, 0.34f},
                        new float[]{0.64f, 0.64f, 0.36f},
                        new float[]{0.76f, 0.70f, 0.50f}
                ),
                0.7f
        ));

        // Erosion: low (stable teal) -> medium (lavender) -> high (pink)
        result.put(RenderMode.NOISE_EROSION, bake(
                gradient("erosion",
                        new float[]{0.20f, 0.58f, 0.58f},
                        new float[]{0.40f, 0.45f, 0.60f},
                        new float[]{0.70f, 0.50f, 0.65f},
                        new float[]{0.90f, 0.65f, 0.72f}
                ),
                0.7f
        ));

        // Depth: below sea (dark blue) -> surface (green-brown) -> above (tan)
        result.put(RenderMode.NOISE_DEPTH, bake(
                gradient("depth",
                        new float[]{0.15f, 0.20f, 0.45f},
                        new float[]{0.25f, 0.40f, 0.55f},
                        new float[]{0.40f, 0.55f, 0.35f},
                        new float[]{0.65f, 0.55f, 0.30f}
                ),
                0.7f
        ));

        // Weirdness: low (warm orange) → mid (muted blue) → high (violet)
        result.put(RenderMode.NOISE_WEIRDNESS, bake(
                gradient("weirdness",
                        new float[]{0.92f, 0.62f, 0.20f},
                        new float[]{0.55f, 0.50f, 0.45f},
                        new float[]{0.35f, 0.30f, 0.60f},
                        new float[]{0.65f, 0.30f, 0.78f}
                ),
                0.85f   // mostly Lab — perceptually smooth
        ));

        // Peaks and Valleys: valley (green) → flat (grey) → peak (warm)
        result.put(RenderMode.NOISE_PEAKS_AND_VALLEYS, bake(
                gradient("peaks_and_valleys",
                        new float[]{0.25f, 0.50f, 0.30f},
                        new float[]{0.45f, 0.48f, 0.40f},
                        new float[]{0.60f, 0.55f, 0.50f},
                        new float[]{0.80f, 0.65f, 0.35f}
                ),
                0.85f
        ));

        return java.util.Map.copyOf(result);
    }

    private static int[] fallbackTable() {
        int[] table = new int[TABLE_SIZE];
        java.util.Arrays.fill(table, 0xFF808080);
        return table;
    }
}
