package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

/**
 * One condition that a candidate seed must satisfy during a seed search.
 * <p>
 * A {@link SeedSearchRequest} carries a list of criteria and a seed only
 * counts as a hit when <b>every</b> criterion passes (logical AND).
 * </p>
 */
public sealed interface SearchCriterion {

    /** Hard upper bound for structure search distance (blocks from the anchor). */
    int MAX_STRUCTURE_DISTANCE = 8192;

    /**
     * Requires the target biome to cover at least {@code minAreaPercent} of the
     * sampled viewport, with at least one matching point within
     * {@code maxDistance} blocks of the viewport center (0 = unlimited).
     */
    record Biome(
            @NotNull Identifier biome,
            int minAreaPercent,
            int maxDistance
    ) implements SearchCriterion {
        public Biome {
            if (biome == null) throw new IllegalArgumentException("biome must not be null");
            if (minAreaPercent < 0 || minAreaPercent > 100) {
                throw new IllegalArgumentException("minAreaPercent must be 0-100");
            }
            if (maxDistance < 0) throw new IllegalArgumentException("maxDistance must be >= 0");
        }
    }

    /**
     * Requires a structure of the given type to generate within
     * {@code maxDistanceBlocks} of the search anchor point.
     * <p>
     * Structures using concentric-ring placement (strongholds) are not
     * supported by the lightweight probe and always fail this criterion.
     * </p>
     */
    record Structure(
            @NotNull Identifier structure,
            int maxDistanceBlocks
    ) implements SearchCriterion {
        public Structure {
            if (structure == null) throw new IllegalArgumentException("structure must not be null");
            if (maxDistanceBlocks <= 0 || maxDistanceBlocks > MAX_STRUCTURE_DISTANCE) {
                throw new IllegalArgumentException(
                        "maxDistanceBlocks must be 1-" + MAX_STRUCTURE_DISTANCE);
            }
        }
    }
}
