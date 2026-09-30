package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Shared fixtures for the {@code SeedSearch*} test family: the biome
 * identifiers, the two canonical viewport centers and the request factories
 * that used to be duplicated verbatim in the individual test classes.
 *
 * <p>Every factory reproduces the original literal argument list exactly
 * (dimension {@code minecraft:overworld}, y level 64, sample step 16,
 * fingerprint {@code "test"}, view minimum 0), so replacing a local factory
 * with one of these does not change the search scope. The overloads keep the
 * per-file viewport shapes apart instead of silently sharing one.</p>
 */
final class SeedSearchFixtures {

    /** Viewport center of the origin-based tests. */
    static final BlockPos CENTER = new BlockPos(0, 64, 0);

    /** Viewport center of the offset tests. */
    static final BlockPos CENTER_16 = new BlockPos(16, 64, 16);

    static final Identifier PLAINS = Identifier.parse("minecraft:plains");
    static final Identifier DESERT = Identifier.parse("minecraft:desert");
    static final Identifier JUNGLE = Identifier.parse("minecraft:jungle");
    static final Identifier FOREST = Identifier.parse("minecraft:forest");
    static final Identifier VILLAGE = Identifier.parse("minecraft:village_plains");
    static final Identifier RARE = Identifier.parse("minecraft:rare_biome");

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String FINGERPRINT = "test";
    private static final int Y_LEVEL = 64;
    private static final int SAMPLE_STEP = 16;
    /** Viewport bound of the 0..16 grids. */
    private static final int VIEW_SMALL = 16;
    /** Viewport bound of the 0..32 grids. */
    private static final int VIEW_LARGE = 32;
    /** Attempt budget of the varargs factories (the original literal 60). */
    private static final int ATTEMPTS_STANDARD = 60;

    private SeedSearchFixtures() {
    }

    /** Core factory: center + 0..{@code viewMax} square viewport, all other scope literals fixed. */
    private static SeedSearchRequest request(BlockPos center, int viewMax, int maxAttempts,
                                             List<SearchCriterion> criteria, int maxHits) {
        return new SeedSearchRequest(
                OVERWORLD, center, Y_LEVEL,
                0, viewMax, 0, viewMax, SAMPLE_STEP, FINGERPRINT, maxAttempts,
                criteria, maxHits
        );
    }

    /**
     * Center (0,64,0), 0..16 grid, single biome criterion (minAreaPercent 0,
     * maxDistance 0) — the shape of the parallel test's local factory.
     */
    static SeedSearchRequest biomeRequest(Identifier biome, int maxAttempts, int maxHits) {
        return request(CENTER, VIEW_SMALL, maxAttempts,
                List.of(new SearchCriterion.Biome(biome, 0, 0)), maxHits);
    }

    /** Center (16,64,16), 0..32 grid, explicit attempt budget and criteria list. */
    static SeedSearchRequest requestAt16(List<SearchCriterion> criteria, int maxAttempts, int maxHits) {
        return request(CENTER_16, VIEW_LARGE, maxAttempts, criteria, maxHits);
    }

    /** Center (0,64,0), 0..32 grid, 60 attempts — the origin varargs shape. */
    static SeedSearchRequest requestAtOrigin(int maxHits, SearchCriterion... criteria) {
        return request(CENTER, VIEW_LARGE, ATTEMPTS_STANDARD, List.of(criteria), maxHits);
    }

    /** Center (16,64,16), 0..32 grid, 60 attempts — the offset varargs shape. */
    static SeedSearchRequest requestAt16(int maxHits, SearchCriterion... criteria) {
        return request(CENTER_16, VIEW_LARGE, ATTEMPTS_STANDARD, List.of(criteria), maxHits);
    }

    /** Legacy single-biome shape: center (0,64,0), 0..16 grid, minArea 0, maxDistance 0. */
    static SeedSearchRequest legacyRequest(Identifier biome, int maxAttempts) {
        return new SeedSearchRequest(
                biome, OVERWORLD, CENTER, Y_LEVEL,
                0, VIEW_SMALL, 0, VIEW_SMALL, SAMPLE_STEP, FINGERPRINT, maxAttempts,
                0, 0
        );
    }
}
