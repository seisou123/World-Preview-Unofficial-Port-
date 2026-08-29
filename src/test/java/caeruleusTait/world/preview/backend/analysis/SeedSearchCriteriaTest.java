package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the extended {@link SeedSearchRequest} (multi-criteria + maxHits)
 * and the {@link SearchCriterion} records, including legacy compatibility.
 */
class SeedSearchCriteriaTest {

    @Test
    @DisplayName("Biome criterion validation")
    void biomeCriterionValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCriterion.Biome(null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCriterion.Biome(Identifier.parse("minecraft:plains"), -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCriterion.Biome(Identifier.parse("minecraft:plains"), 101, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCriterion.Biome(Identifier.parse("minecraft:plains"), 0, -1));
        var ok = new SearchCriterion.Biome(Identifier.parse("minecraft:plains"), 50, 128);
        assertEquals(Identifier.parse("minecraft:plains"), ok.biome());
    }

    @Test
    @DisplayName("Structure criterion validation")
    void structureCriterionValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCriterion.Structure(null, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCriterion.Structure(Identifier.parse("minecraft:village_plains"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCriterion.Structure(
                        Identifier.parse("minecraft:village_plains"), SearchCriterion.MAX_STRUCTURE_DISTANCE + 1));
        var ok = new SearchCriterion.Structure(Identifier.parse("minecraft:village_plains"), 512);
        assertEquals(512, ok.maxDistanceBlocks());
    }

    @Test
    @DisplayName("Legacy 13-arg constructor wraps biome into criteria")
    void legacyConstructorWrapsBiome() {
        var request = new SeedSearchRequest(
                Identifier.parse("minecraft:desert"), "minecraft:overworld",
                new BlockPos(0, 64, 0), 64,
                -100, 100, -100, 100, 4, "fp", 100, 25, 512
        );
        assertEquals(Identifier.parse("minecraft:desert"), request.targetBiome());
        assertEquals(1, request.criteria().size());
        assertInstanceOf(SearchCriterion.Biome.class, request.criteria().get(0));
        var biome = (SearchCriterion.Biome) request.criteria().get(0);
        assertEquals(25, biome.minAreaPercent());
        assertEquals(512, biome.maxDistance());
        assertEquals(1, request.maxHits());
        assertTrue(request.structureCriteria().isEmpty());
        assertEquals(1, request.biomeCriteria().size());
    }

    @Test
    @DisplayName("Multi-criteria constructor keeps criteria and accessors")
    void multiCriteriaConstructor() {
        var request = new SeedSearchRequest(
                "minecraft:overworld", new BlockPos(0, 64, 0), 64,
                -100, 100, -100, 100, 4, "fp", 200,
                List.of(
                        new SearchCriterion.Biome(Identifier.parse("minecraft:plains"), 10, 0),
                        new SearchCriterion.Structure(Identifier.parse("minecraft:village_plains"), 256)
                ),
                5
        );
        assertEquals(Identifier.parse("minecraft:plains"), request.targetBiome());
        assertEquals(2, request.criteria().size());
        assertEquals(1, request.structureCriteria().size());
        assertEquals(Identifier.parse("minecraft:village_plains"),
                request.structureCriteria().get(0).structure());
        assertEquals(5, request.maxHits());
    }

    @Test
    @DisplayName("Request validation rejects empty criteria and bad maxHits")
    void requestValidation() {
        assertThrows(IllegalArgumentException.class, () -> new SeedSearchRequest(
                "minecraft:overworld", new BlockPos(0, 64, 0), 64,
                -100, 100, -100, 100, 4, "fp", 100, List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new SeedSearchRequest(
                "minecraft:overworld", new BlockPos(0, 64, 0), 64,
                -100, 100, -100, 100, 4, "fp", 100,
                List.of(new SearchCriterion.Biome(Identifier.parse("minecraft:plains"), 0, 0)), 0));
        assertThrows(IllegalArgumentException.class, () -> new SeedSearchRequest(
                "minecraft:overworld", new BlockPos(0, 64, 0), 64,
                -100, 100, -100, 100, 4, "fp", 100,
                List.of(new SearchCriterion.Biome(Identifier.parse("minecraft:plains"), 0, 0)),
                SeedSearchRequest.MAX_HITS_LIMIT + 1));
    }
}
