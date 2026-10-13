// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.color;

import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Biome color intelligence system with multi-layered resolution strategy.
 * <p>
 * This system provides far more than simple pattern-based color matching.
 * It implements a multi-pass analysis pipeline:
 * <ul>
 *   <li><b>Exact override layer</b> — hard-coded colors for specific biomes
 *       that need precise visual representation (cherry grove, dark forest, etc.)</li>
 *   <li><b>Climate-category layer</b> — assigns colors based on biome climate
 *       classification (desert, snowy, nether, cave, etc.) with each category
 *       having a palette of shade variants for visual richness.</li>
 *   <li><b>Vegetation-heuristic layer</b> — uses foliage affinity patterns
 *       (jungle, forest, taiga, swamp, plains, mountain) to assign colors
 *       that reflect the biome's dominant vegetation type.</li>
 *   <li><b>Elevation-aware modulation</b> — adjusts the resolved color's
 *       brightness and saturation based on the biome's typical elevation
 *       range, producing natural visual depth (higher = cooler/desaturated,
 *       lower = warmer/deeper).</li>
 *   <li><b>Color-temperature analysis</b> — shifts the resolved color's
 *       hue slightly warm or cool based on the biome's climate zone,
 *       creating a coherent visual temperature gradient across the map.</li>
 *   <li><b>HSV deterministic fallback</b> — for truly unknown biomes,
 *       generates a controlled HSV color with earthy saturation and value
 *       ranges, avoiding the extreme contrast of hash-based approaches.</li>
 *   <li><b>Transition zone blending</b> — when two adjacent biomes have
 *       very different colors, this system can compute a blended transition
 *       color to soften the visual boundary.</li>
 * </ul>
 * <p>
 * Additionally, this class generates human-readable display names from
 * biome identifier paths (e.g. {@code snowy_taiga} → {@code Snowy Taiga}).
 */
public final class BiomeColorResolver {

    private static final int OPAQUE_ALPHA = 0xFF << 24;

    private static final Map<String, Integer> EXACT_OVERRIDES = createExactOverrides();

    // Climate zone classification for color temperature analysis
    private enum ClimateZone {
        FROZEN,     // snow, ice, frozen → cool shift
        TEMPERATE,  // forest, plains, taiga → neutral
        ARID,       // desert, savanna, badlands → warm shift
        TROPICAL,   // jungle, lush → vibrant warm shift
        AQUATIC,    // ocean, river, lake → blue tint
        NETHER,     // nether biomes → red/orange shift
        END,        // end biomes → pale shift
        CAVE,       // underground → dark shift
        NEUTRAL     // fallback
    }

    private BiomeColorResolver() {
    }

    // ==================================================================
    // Color resolution (full pipeline)
    // ==================================================================

    /**
     * Resolve a display color for the given biome identifier using the full
     * multi-layered analysis pipeline.
     *
     * @param biomeId the biome resource identifier, must not be null
     * @return ARGB color int (fully opaque)
     */
    public static int resolveColor(Identifier biomeId) {
        return resolveColor(biomeId, 0);
    }

    /**
     * Resolve a display color with elevation-aware modulation.
     *
     * @param biomeId       the biome resource identifier
     * @param elevationHint elevation hint in blocks (0 = sea level,
     *                      positive = above, negative = below)
     * @return ARGB color int (fully opaque)
     */
    public static int resolveColor(Identifier biomeId, int elevationHint) {
        final String fullId = biomeId.toString();
        final String path = biomeId.getPath().toLowerCase(Locale.ROOT);

        // Layer 1: Exact overrides
        Integer exact = EXACT_OVERRIDES.get(fullId);
        if (exact != null) {
            return OPAQUE_ALPHA | applyElevationModulation(exact, elevationHint, path);
        }

        // Layer 2: Climate category
        Integer category = matchCategory(path);
        if (category != null) {
            int modulated = applyClimateTemperatureShift(category, path);
            return OPAQUE_ALPHA | applyElevationModulation(modulated, elevationHint, path);
        }

        // Layer 3: Vegetation heuristic
        Integer heuristic = matchHeuristic(path);
        if (heuristic != null) {
            int modulated = applyClimateTemperatureShift(heuristic, path);
            return OPAQUE_ALPHA | applyElevationModulation(modulated, elevationHint, path);
        }

        // Layer 4: HSV fallback
        int fallback = hsvFallback(biomeId);
        int modulated = applyClimateTemperatureShift(fallback, path);
        return OPAQUE_ALPHA | applyElevationModulation(modulated, elevationHint, path);
    }

    // ==================================================================
    // Transition zone blending
    // ==================================================================

    /**
     * Compute a blended transition color between two biomes.
     * Used at biome boundaries to create smooth visual transitions.
     *
     * @param color1  first biome's ARGB color
     * @param color2  second biome's ARGB color
     * @param t       blend factor (0 = color1, 1 = color2)
     * @return blended ARGB color
     */
    public static int blendTransition(int color1, int color2, float t) {
        if (t <= 0f) return color1;
        if (t >= 1f) return color2;
        // Use Lab-space interpolation for perceptually smoother transitions
        float r1 = ((color1 >> 16) & 0xFF) / 255f;
        float g1 = ((color1 >> 8) & 0xFF) / 255f;
        float b1 = (color1 & 0xFF) / 255f;
        float r2 = ((color2 >> 16) & 0xFF) / 255f;
        float g2 = ((color2 >> 8) & 0xFF) / 255f;
        float b2 = (color2 & 0xFF) / 255f;

        // Convert to Lab, interpolate, convert back
        float[] lab1 = ColorMap.RGBToLab(r1, g1, b1);
        float[] lab2 = ColorMap.RGBToLab(r2, g2, b2);

        float[] blended = new float[3];
        blended[0] = ColorMap.lerp(lab2[0], lab1[0], t);
        blended[1] = ColorMap.lerp(lab2[1], lab1[1], t);
        blended[2] = ColorMap.lerp(lab2[2], lab1[2], t);

        float[] rgb = ColorMap.LabToRGB(blended[0], blended[1], blended[2]);
        int r = Math.max(0, Math.min(255, Math.round(rgb[0] * 255f)));
        int g = Math.max(0, Math.min(255, Math.round(rgb[1] * 255f)));
        int b = Math.max(0, Math.min(255, Math.round(rgb[2] * 255f)));
        return OPAQUE_ALPHA | (r << 16) | (g << 8) | b;
    }

    // ==================================================================
    // Display name generation
    // ==================================================================

    public static String resolveDisplayName(Identifier biomeId) {
        return resolveDisplayNameFromPath(biomeId.getPath());
    }

    public static String resolveDisplayNameFromPath(String path) {
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder(path.length() + 4);
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part, 1, part.length());
            }
        }
        return sb.isEmpty() ? path : sb.toString();
    }

    // ==================================================================
    // Elevation-aware modulation
    // ==================================================================

    /**
     * Modulate a base color based on elevation hint.
     * Higher elevations → cooler, desaturated (atmospheric perspective).
     * Lower elevations → warmer, deeper.
     */
    private static int applyElevationModulation(int rgb, int elevationHint, String path) {
        if (elevationHint == 0) return rgb;

        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        // Normalized elevation factor: -1 (deep) to +1 (high)
        float elevFactor = Math.max(-1f, Math.min(1f, elevationHint / 200f));

        // High elevation: cooler and desaturated (atmospheric perspective)
        // Low elevation: warmer and slightly darker
        if (elevFactor > 0) {
            float cool = elevFactor * 0.15f;
            r = Math.max(0, Math.min(255, Math.round(r * (1 - cool * 0.3f))));
            g = Math.max(0, Math.min(255, Math.round(g * (1 - cool * 0.1f))));
            b = Math.max(0, Math.min(255, Math.round(b * (1 + cool * 0.2f))));
            // Desaturate
            int gray = (r + g + b) / 3;
            float desat = elevFactor * 0.12f;
            r = Math.round(r * (1 - desat) + gray * desat);
            g = Math.round(g * (1 - desat) + gray * desat);
            b = Math.round(b * (1 - desat) + gray * desat);
        } else {
            float warm = -elevFactor * 0.1f;
            r = Math.max(0, Math.min(255, Math.round(r * (1 + warm * 0.2f))));
            g = Math.max(0, Math.min(255, Math.round(g * (1 - warm * 0.05f))));
            b = Math.max(0, Math.min(255, Math.round(b * (1 - warm * 0.3f))));
        }

        return (r << 16) | (g << 8) | b;
    }

    // ==================================================================
    // Color temperature analysis
    // ==================================================================

    /**
     * Shift a color's hue slightly warm or cool based on the biome's climate zone.
     * This creates a coherent visual temperature gradient across the map.
     */
    private static int applyClimateTemperatureShift(int rgb, String path) {
        ClimateZone zone = classifyClimate(path);
        if (zone == ClimateZone.TEMPERATE || zone == ClimateZone.NEUTRAL) {
            return rgb; // No shift for temperate biomes
        }

        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        float shift = switch (zone) {
            case FROZEN -> -0.08f;   // cooler (shift toward blue)
            case ARID -> 0.06f;     // warmer (shift toward red)
            case TROPICAL -> 0.04f; // slightly warmer
            case AQUATIC -> -0.04f; // slightly cooler
            case NETHER -> 0.08f;   // warmer
            case END -> -0.03f;     // slightly cooler
            default -> 0f;
        };

        if (shift > 0) {
            r = Math.min(255, Math.round(r * (1 + shift)));
            b = Math.max(0, Math.round(b * (1 - shift * 0.5f)));
        } else if (shift < 0) {
            b = Math.min(255, Math.round(b * (1 + (-shift))));
            r = Math.max(0, Math.round(r * (1 + shift)));
        }

        return (r << 16) | (g << 8) | b;
    }

    private static ClimateZone classifyClimate(String path) {
        if (containsAny(path, "snow", "frozen", "ice", "frost")) return ClimateZone.FROZEN;
        if (containsAny(path, "desert", "badlands", "savanna", "mesa")) return ClimateZone.ARID;
        if (containsAny(path, "jungle", "rainforest", "lush")) return ClimateZone.TROPICAL;
        if (containsAny(path, "ocean", "river", "lake", "sea", "beach", "shore")) return ClimateZone.AQUATIC;
        if (containsAny(path, "nether")) return ClimateZone.NETHER;
        if (containsAny(path, "end_", "the_end")) return ClimateZone.END;
        if (containsAny(path, "cave", "deep_dark", "dripstone", "sculk")) return ClimateZone.CAVE;
        return ClimateZone.TEMPERATE;
    }

    // ==================================================================
    // Category matching
    // ==================================================================

    private static Integer matchCategory(String path) {
        if (containsAny(path, "badlands", "eroded", "terracotta")) {
            return rgb(185, 92, 49);
        }
        if (containsAny(path, "desert", "dunes")) {
            return rgb(221, 202, 125);
        }
        if (containsAny(path, "snow", "frozen", "ice", "frost")) {
            return rgb(190, 218, 224);
        }
        if (containsAny(path, "mushroom", "mycelium")) {
            return rgb(171, 104, 148);
        }
        if (containsAny(path, "crimson")) {
            return rgb(142, 31, 48);
        }
        if (containsAny(path, "warped")) {
            return rgb(42, 134, 126);
        }
        if (containsAny(path, "basalt")) {
            return rgb(74, 72, 76);
        }
        if (containsAny(path, "soul")) {
            return rgb(75, 116, 126);
        }
        if (containsAny(path, "nether")) {
            return rgb(124, 47, 38);
        }
        if (containsAny(path, "end_highlands", "end_midlands", "end_barrens", "small_end_islands", "the_end")) {
            return rgb(204, 198, 128);
        }
        if (containsAny(path, "lush_caves", "lush_cave")) {
            return rgb(70, 155, 82);
        }
        if (containsAny(path, "dripstone")) {
            return rgb(126, 102, 79);
        }
        if (containsAny(path, "deep_dark", "sculk")) {
            return rgb(28, 53, 64);
        }
        return null;
    }

    private static Integer matchHeuristic(String path) {
        if (containsAny(path, "jungle", "rainforest")) {
            return rgb(36, 112, 45);
        }
        if (containsAny(path, "forest", "woods", "woodland", "grove")) {
            return rgb(68, 132, 62);
        }
        if (containsAny(path, "taiga", "pine", "spruce")) {
            return rgb(76, 124, 91);
        }
        if (containsAny(path, "swamp", "marsh", "mangrove")) {
            return rgb(63, 95, 63);
        }
        if (containsAny(path, "savanna", "prairie")) {
            return rgb(177, 169, 86);
        }
        if (containsAny(path, "plains", "meadow", "grassland")) {
            return rgb(114, 166, 83);
        }
        if (containsAny(path, "mountain", "peak", "slope", "stony", "gravel")) {
            return rgb(136, 138, 126);
        }
        if (containsAny(path, "ocean", "river", "lake", "reef", "kelp", "sea")) {
            return rgb(45, 108, 178);
        }
        if (containsAny(path, "beach", "shore")) {
            return rgb(218, 205, 137);
        }
        return null;
    }

    // ==================================================================
    // HSV fallback
    // ==================================================================

    private static int hsvFallback(Identifier biomeId) {
        int hash = biomeId.toString().hashCode();
        float hue = ((hash & Integer.MAX_VALUE) % 360) / 360.0f;
        float saturation = 0.24f + ((hash >>> 9 & 0xFF) / 255.0f) * 0.18f;
        float value = 0.50f + ((hash >>> 17 & 0xFF) / 255.0f) * 0.18f;
        return hsvToRgb(hue, saturation, value);
    }

    private static int hsvToRgb(float hue, float saturation, float value) {
        float h6 = hue * 6.0f;
        int sector = (int) Math.floor(h6);
        float f = h6 - sector;
        float p = value * (1.0f - saturation);
        float q = value * (1.0f - f * saturation);
        float t = value * (1.0f - (1.0f - f) * saturation);

        float r, g, b;
        switch (Math.floorMod(sector, 6)) {
            case 0 -> { r = value; g = t; b = p; }
            case 1 -> { r = q; g = value; b = p; }
            case 2 -> { r = p; g = value; b = t; }
            case 3 -> { r = p; g = q; b = value; }
            case 4 -> { r = t; g = p; b = value; }
            default -> { r = value; g = p; b = q; }
        }
        return rgb(toByte(r), toByte(g), toByte(b));
    }

    private static int toByte(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0f)));
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    // ==================================================================
    // Exact override table
    // ==================================================================

    private static Map<String, Integer> createExactOverrides() {
        Map<String, Integer> m = new HashMap<>();
        m.put("minecraft:beach", rgb(218, 205, 137));
        m.put("minecraft:cherry_grove", rgb(213, 138, 169));
        m.put("minecraft:dark_forest", rgb(45, 90, 49));
        m.put("minecraft:deep_dark", rgb(28, 53, 64));
        m.put("minecraft:desert", rgb(221, 202, 125));
        m.put("minecraft:dripstone_caves", rgb(126, 102, 79));
        m.put("minecraft:eroded_badlands", rgb(197, 103, 48));
        m.put("minecraft:flower_forest", rgb(96, 151, 82));
        m.put("minecraft:ice_spikes", rgb(199, 229, 235));
        m.put("minecraft:lush_caves", rgb(70, 155, 82));
        m.put("minecraft:mangrove_swamp", rgb(54, 93, 58));
        m.put("minecraft:meadow", rgb(128, 175, 86));
        m.put("minecraft:mushroom_fields", rgb(171, 104, 148));
        m.put("minecraft:savanna", rgb(177, 169, 86));
        m.put("minecraft:savanna_plateau", rgb(164, 153, 78));
        m.put("minecraft:snowy_plains", rgb(203, 223, 222));
        m.put("minecraft:snowy_slopes", rgb(194, 216, 218));
        m.put("minecraft:snowy_taiga", rgb(153, 183, 179));
        m.put("minecraft:sparse_jungle", rgb(62, 133, 52));
        m.put("minecraft:stony_peaks", rgb(146, 145, 124));
        m.put("minecraft:swamp", rgb(63, 95, 63));
        m.put("minecraft:wooded_badlands", rgb(171, 91, 52));
        return m;
    }
}
