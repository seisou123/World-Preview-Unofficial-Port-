package caeruleusTait.world.preview.backend.export;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;

/**
 * Terrain classifier based on vanilla biome tags.
 * <p>
 * Classifies terrain by priority-matching multiple biome tags, unlike TFC's simple binary water/land approach.
 * Classification strategy:
 * <ol>
 *   <li>Deep ocean: {@code is_ocean} and biome ID starts with "deep"</li>
 *   <li>Ocean: {@code is_ocean}</li>
 *   <li>River: {@code is_river}</li>
 *   <li>Beach: {@code is_beach}</li>
 *   <li>Peak: {@code is_mountain} and biome ID contains "peak"</li>
 *   <li>Mountain: {@code is_mountain}</li>
 *   <li>Hills: {@code is_hill}</li>
 *   <li>Forest: {@code is_forest}</li>
 *   <li>Plains: default land when no tag matches</li>
 *   <li>Unclassified: not in the overworld biome registry</li>
 * </ol>
 */
public final class TerrainClassifier {

    private static final TagKey<Biome> IS_OCEAN = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "is_ocean"));
    private static final TagKey<Biome> IS_RIVER = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "is_river"));
    private static final TagKey<Biome> IS_BEACH = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "is_beach"));
    private static final TagKey<Biome> IS_MOUNTAIN = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "is_mountain"));
    private static final TagKey<Biome> IS_HILL = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "is_hill"));
    private static final TagKey<Biome> IS_FOREST = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "is_forest"));
    private static final TagKey<Biome> IS_OVERWORLD = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "is_overworld"));

    private TerrainClassifier() {}

    /**
     * Classify a biome Holder into a terrain category.
     *
     * @param biomeHolder Biome Holder
     * @return The corresponding {@link TerrainCategory}
     */
    public static TerrainCategory classify(Holder<Biome> biomeHolder) {
        if (biomeHolder == null) {
            return TerrainCategory.UNKNOWN;
        }

        Optional<Identifier> idOpt = biomeHolder.unwrapKey()
                .map(key -> key.identifier());

        String path = idOpt.map(Identifier::getPath).orElse("").toLowerCase();
        String namespace = idOpt.map(Identifier::getNamespace).orElse("").toLowerCase();

        // Check water biomes first
        if (biomeHolder.is(IS_OCEAN)) {
            return path.contains("deep") ? TerrainCategory.DEEP_OCEAN : TerrainCategory.OCEAN;
        }
        if (biomeHolder.is(IS_RIVER)) {
            return TerrainCategory.RIVER;
        }
        if (biomeHolder.is(IS_BEACH)) {
            return TerrainCategory.BEACH;
        }

        // Mountain biomes
        if (biomeHolder.is(IS_MOUNTAIN)) {
            return path.contains("peak") || path.contains("snowy_peaks")
                    ? TerrainCategory.PEAK : TerrainCategory.MOUNTAIN;
        }

        // Hills
        if (biomeHolder.is(IS_HILL)) {
            return TerrainCategory.HILLS;
        }

        // Forest
        if (biomeHolder.is(IS_FOREST)) {
            return TerrainCategory.FOREST;
        }

        // Default land classification
        if (biomeHolder.is(IS_OVERWORLD)) {
            return TerrainCategory.PLAINS;
        }

        // ===== Fallback classification for modded biomes =====
        // When a biome is not in vanilla tags (e.g. Terralith, BOP mod biomes),
        // classify heuristically by biome ID keywords

        // Water keywords
        if (path.contains("ocean") || path.contains("sea") || path.contains("marine")) {
            return path.contains("deep") || path.contains("abyss") ? TerrainCategory.DEEP_OCEAN : TerrainCategory.OCEAN;
        }
        if (path.contains("river") || path.contains("stream") || path.contains("creek")) {
            return TerrainCategory.RIVER;
        }
        if (path.contains("beach") || path.contains("shore") || path.contains("coast") || path.contains("dunes")) {
            return TerrainCategory.BEACH;
        }

        // Mountain keywords
        if (path.contains("peak") || path.contains("summit") || path.contains("pinnacle")) {
            return TerrainCategory.PEAK;
        }
        if (path.contains("mountain") || path.contains("alpine") || path.contains("highland")
                || path.contains("cliff") || path.contains("ridge") || path.contains("volcano")) {
            return TerrainCategory.MOUNTAIN;
        }

        // Hill keywords
        if (path.contains("hill") || path.contains("foothill") || path.contains("rolling")) {
            return TerrainCategory.HILLS;
        }

        // Forest keywords
        if (path.contains("forest") || path.contains("woods") || path.contains("woodland")
                || path.contains("jungle") || path.contains("taiga") || path.contains("grove")
                || path.contains("dark_oak") || path.contains("birch")) {
            return TerrainCategory.FOREST;
        }

        // Plains keywords
        if (path.contains("plain") || path.contains("grass") || path.contains("meadow")
                || path.contains("savanna") || path.contains("prairie") || path.contains("steppe")
                || path.contains("field") || path.contains("valley")) {
            return TerrainCategory.PLAINS;
        }

        // Special environments
        if (path.contains("desert") || path.contains("wasteland") || path.contains("badlands")) {
            return TerrainCategory.PLAINS;
        }
        if (path.contains("swamp") || path.contains("marsh") || path.contains("wetland")
                || path.contains("bog") || path.contains("fen")) {
            return TerrainCategory.FOREST;
        }

        // Default to plains for overworld namespace biomes
        if (namespace.equals("minecraft") || namespace.contains("terralith")
                || namespace.contains("biomesoplenty") || namespace.contains("oh_the_biomes")
                || namespace.contains("natures") || namespace.contains("byg")) {
            return TerrainCategory.PLAINS;
        }

        return TerrainCategory.UNKNOWN;
    }
}
