package caeruleusTait.world.preview.backend.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure tests for {@link SpawnAdvisor}: exact scores per the documented weights,
 * reason presence rules and input validation.
 */
class SpawnAdvisorTest {

    private static final String PREFIX = "world_preview.spawnadvisor.reason.";

    private static boolean hasReason(SpawnAdvisor.SpawnResult result, String keySuffix) {
        return result.reasons().stream().anyMatch(r -> r.key().equals(PREFIX + keySuffix));
    }

    // ===== Exact scores =====

    @Test
    @DisplayName("all water + no flat data scores low")
    void allWaterScoresLow() {
        // water 0 + flat 0 + unknown slope 10 + structures 0 = 10
        var result = SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(1.0, 0.0, null, 0));
        assertEquals(10, result.score());
        // water 0 + flat 6 (0.2*30) + unknown slope 10 = 16
        assertEquals(16, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(1.0, 0.2, null, 0)).score());
    }

    @Test
    @DisplayName("perfect land + perfectly flat + unknown slope = 30 + 30 + 10 = 70")
    void perfectLandFlatUnknownSlope() {
        var result = SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 1.0, null, 0));
        assertEquals(70, result.score());
        // Half credit for the unknown slope: 20 less than a known slope of 0.
        assertEquals(80, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 1.0, 0.0, 0)).score());
    }

    @Test
    @DisplayName("structures bonus: 10 points each, capped at 20")
    void structuresBonusCapsAt20() {
        assertEquals(80, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 1.0, null, 1)).score());
        assertEquals(90, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 1.0, null, 2)).score());
        // Bonus saturates: 5 structures still add only 20.
        assertEquals(90, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 1.0, null, 5)).score());
        assertEquals(90, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 1.0, null, 100)).score());
    }

    @Test
    @DisplayName("known slope: 20 points at 0, linear to 0 points at 12+")
    void knownSlopeScoring() {
        // 20 * (1 - 6/12) = 10 -> 30 + 10 + 30 = 70
        assertEquals(70, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 1.0, 6.0, 0)).score());
        // 20 * (1 - 12/12) = 0 -> 30 + 0 + 30 = 60
        assertEquals(60, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 1.0, 12.0, 0)).score());
        // Slope beyond 12 clamps to 0 points.
        assertEquals(60, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 1.0, 24.0, 0)).score());
    }

    @Test
    @DisplayName("score is bounded to 0..100")
    void scoreBounded() {
        // Everything at its worst.
        assertEquals(0, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(1.0, 0.0, 99.0, 0)).score());
        // Everything at its best (slope known and flat) including full structure bonus.
        assertEquals(100, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 1.0, 0.0, 2)).score());
    }

    // ===== Reason presence =====

    @Test
    @DisplayName("much_water above 0.6 share with percent arg; none in the middle band")
    void muchWaterReason() {
        var result = SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.65, 0.5, null, 0));
        assertTrue(hasReason(result, "much_water"));
        SpawnAdvisor.Reason reason = result.reasons().stream()
                .filter(r -> r.key().equals(PREFIX + "much_water")).findFirst().orElseThrow();
        assertEquals(1, reason.args().length);
        assertEquals("65%", String.valueOf(reason.args()[0]));

        assertFalse(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.5, 0.5, null, 0)), "much_water"));
        assertFalse(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.6, 0.5, null, 0)), "much_water"));
        assertTrue(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.61, 0.5, null, 0)), "much_water"));
    }

    @Test
    @DisplayName("little_water below 0.1 share")
    void littleWaterReason() {
        assertTrue(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.05, 0.5, null, 0)), "little_water"));
        assertFalse(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.15, 0.5, null, 0)), "little_water"));
    }

    @Test
    @DisplayName("rough/flat terrain reasons follow the flatRatio thresholds")
    void terrainReasons() {
        assertTrue(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.5, 0.2, null, 0)), "rough_terrain"));
        assertFalse(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.5, 0.3, null, 0)), "rough_terrain"));
        assertTrue(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.5, 0.8, null, 0)), "flat_terrain"));
        assertFalse(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.5, 0.7, null, 0)), "flat_terrain"));
    }

    @Test
    @DisplayName("steep reason only for known slope above 12, with value arg")
    void steepReason() {
        var result = SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.5, 0.5, 13.5, 0));
        assertTrue(hasReason(result, "steep"));
        SpawnAdvisor.Reason reason = result.reasons().stream()
                .filter(r -> r.key().equals(PREFIX + "steep")).findFirst().orElseThrow();
        assertEquals("13.5", String.valueOf(reason.args()[0]));

        assertFalse(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.5, 0.5, 12.0, 0)), "steep"));
        // Unknown slope never produces a slope reason.
        assertFalse(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.5, 0.5, null, 0)), "steep"));
    }

    @Test
    @DisplayName("structures_nearby reason carries the count")
    void structuresReason() {
        var result = SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.5, 0.5, null, 3));
        assertTrue(hasReason(result, "structures_nearby"));
        SpawnAdvisor.Reason reason = result.reasons().stream()
                .filter(r -> r.key().equals(PREFIX + "structures_nearby")).findFirst().orElseThrow();
        assertEquals(3, reason.args()[0]);

        assertFalse(hasReason(SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.5, 0.5, null, 0)), "structures_nearby"));
    }

    @Test
    @DisplayName("good_spawn at score >= 70, poor_spawn at score < 40, neither at exactly 40")
    void verdictReasons() {
        // 30 + 30 + 10 (unknown slope) = 70 -> good_spawn.
        var good = SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 1.0, null, 0));
        assertTrue(good.score() >= 70);
        assertTrue(hasReason(good, "good_spawn"));
        assertFalse(hasReason(good, "poor_spawn"));

        // 0 + 0 + 10 = 10 -> poor_spawn.
        var poor = SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(1.0, 0.0, null, 0));
        assertTrue(poor.score() < 40);
        assertTrue(hasReason(poor, "poor_spawn"));
        assertFalse(hasReason(poor, "good_spawn"));

        // 30 (land) + 0 (flat) + 10 (unknown slope) = 40 -> neither verdict.
        var middle = SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 0.0, null, 0));
        assertEquals(40, middle.score());
        assertFalse(hasReason(middle, "good_spawn"));
        assertFalse(hasReason(middle, "poor_spawn"));
    }

    // ===== Input validation =====

    @Test
    @DisplayName("negative structuresNearby throws")
    void negativeStructuresThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpawnAdvisor.SpawnInput(0.5, 0.5, null, -1));
    }

    @Test
    @DisplayName("ratios are clamped into 0..1, not rejected")
    void ratiosClamped() {
        // Over the top: behaves like fully water (water score 0), still reports much_water.
        var over = SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(1.5, 0.5, null, 0));
        assertEquals(25, over.score()); // water 0 + flat 15 (0.5*30) + unknown slope 10
        SpawnAdvisor.Reason reason = over.reasons().stream()
                .filter(r -> r.key().equals(PREFIX + "much_water")).findFirst().orElseThrow();
        assertEquals("100%", String.valueOf(reason.args()[0]));

        // Below zero: behaves like fully land (water score 30).
        assertEquals(40, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(-0.5, 0.0, null, 0)).score());

        // flatRatio clamps too: -1 -> 0 points, 2.0 -> 30 points.
        assertEquals(40, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, -1.0, null, 0)).score());
        assertEquals(70, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(0.0, 2.0, null, 0)).score());

        // Non-finite ratios count as 0.0.
        assertEquals(40, SpawnAdvisor.evaluate(new SpawnAdvisor.SpawnInput(Double.NaN, 0.0, null, 0)).score());
    }

    @Test
    @DisplayName("reasons are exposed as translation keys, never English text")
    void reasonsUseTranslationKeys() {
        List<SpawnAdvisor.Reason> reasons = SpawnAdvisor
                .evaluate(new SpawnAdvisor.SpawnInput(1.0, 0.0, 13.5, 2))
                .reasons();
        assertFalse(reasons.isEmpty());
        for (SpawnAdvisor.Reason reason : reasons) {
            assertTrue(reason.key().startsWith("world_preview.spawnadvisor.reason."),
                    () -> "unexpected key: " + reason.key());
        }
    }
}
