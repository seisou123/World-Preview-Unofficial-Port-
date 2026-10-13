package caeruleusTait.world.preview.backend.analysis;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the extracted metric sampler and dimension helper classes.
 */
class ExtractedClassTest {

    // ---- BiomeMetricsSampler tests ----

    @Test
    void biomeMetricsSamplerBasic() {
        List<BiomeMetricsSampler.BiomeSample> samples = List.of(
                new BiomeMetricsSampler.BiomeSample("minecraft:plains", 0, 0),
                new BiomeMetricsSampler.BiomeSample("minecraft:plains", 0, 1),
                new BiomeMetricsSampler.BiomeSample("minecraft:forest", 1, 0),
                new BiomeMetricsSampler.BiomeSample("minecraft:plains", 1, 1)
        );

        Set<String> targets = Set.of("minecraft:plains");

        var result = BiomeMetricsSampler.compute(samples, targets, 0, 0);

        assertEquals(4, result.totalSamples());
        assertEquals(3, result.counts().get("minecraft:plains"));
        assertEquals(1, result.counts().get("minecraft:forest"));
        assertEquals(0.75, result.ratios().get("minecraft:plains"));
        assertEquals(0.25, result.ratios().get("minecraft:forest"));
        assertTrue(result.nearestDistances().containsKey("minecraft:plains"));
    }

    @Test
    void biomeMetricsSamplerEmpty() {
        List<BiomeMetricsSampler.BiomeSample> samples = List.of();
        Set<String> targets = Set.of();

        var result = BiomeMetricsSampler.compute(samples, targets, 0, 0);

        assertEquals(0, result.totalSamples());
        assertTrue(result.counts().isEmpty());
        assertTrue(result.ratios().isEmpty());
        assertTrue(result.nearestDistances().isEmpty());
    }

    @Test
    void biomeMetricsSamplerNearestDistance() {
        List<BiomeMetricsSampler.BiomeSample> samples = List.of(
                new BiomeMetricsSampler.BiomeSample("minecraft:desert", 0, 0),
                new BiomeMetricsSampler.BiomeSample("minecraft:desert", 0, 1),
                new BiomeMetricsSampler.BiomeSample("minecraft:desert", 1, 0),
                new BiomeMetricsSampler.BiomeSample("minecraft:desert", 1, 1)
        );

        Set<String> targets = Set.of("minecraft:desert");

        var result = BiomeMetricsSampler.compute(samples, targets, 0, 0);

        // The nearest distance should be 0 (at position (0,0))
        assertEquals(0.0, result.nearestDistances().get("minecraft:desert"));
    }

    // ---- HeightMetricsSampler tests ----

    @Test
    void heightMetricsSamplerBasic() {
        List<Double> heights = List.of(60.0, 70.0, 65.0, 75.0);
        List<Integer> xs = List.of(0, 1);
        List<Integer> zs = List.of(0, 1);

        var result = HeightMetricsSampler.compute(heights, xs, zs);

        assertEquals(4, result.sampleCount());
        assertEquals(67.5, result.mean(), 0.001);
        assertEquals(67.5, result.median(), 0.001);
        assertEquals(60.0, result.min());
        assertEquals(75.0, result.max());
        assertTrue(result.variance() > 0);
        assertTrue(result.stddev() > 0);
        assertTrue(result.avgSlope() >= 0);
    }

    @Test
    void heightMetricsSamplerUniform() {
        List<Double> heights = List.of(64.0, 64.0, 64.0, 64.0);
        List<Integer> xs = List.of(0, 1);
        List<Integer> zs = List.of(0, 1);

        var result = HeightMetricsSampler.compute(heights, xs, zs);

        assertEquals(64.0, result.mean());
        assertEquals(64.0, result.median());
        assertEquals(0.0, result.variance(), 0.001);
        assertEquals(0.0, result.stddev(), 0.001);
        assertEquals(64.0, result.min());
        assertEquals(64.0, result.max());
        assertEquals(0.0, result.avgSlope(), 0.001);
    }

    @Test
    void heightMetricsSamplerSinglePoint() {
        List<Double> heights = List.of(64.0);
        List<Integer> xs = List.of(0);
        List<Integer> zs = List.of(0);

        var result = HeightMetricsSampler.compute(heights, xs, zs);

        assertEquals(1, result.sampleCount());
        assertEquals(64.0, result.mean());
        assertEquals(64.0, result.median());
        assertEquals(0.0, result.variance(), 0.001);
        assertEquals(0.0, result.avgSlope(), 0.001);
    }

    @Test
    void heightMetricsSamplerEmptyThrows() {
        List<Double> heights = List.of();
        List<Integer> xs = List.of();
        List<Integer> zs = List.of();

        assertThrows(IllegalStateException.class, () -> HeightMetricsSampler.compute(heights, xs, zs));
    }

    @Test
    void heightMetricsSamplerOddCount() {
        List<Double> heights = List.of(10.0, 20.0, 30.0);
        List<Integer> xs = List.of(0, 1, 2);
        List<Integer> zs = List.of(0);

        var result = HeightMetricsSampler.compute(heights, xs, zs);

        assertEquals(3, result.sampleCount());
        assertEquals(20.0, result.mean(), 0.001);
        assertEquals(20.0, result.median(), 0.001);  // middle element of sorted [10, 20, 30]
    }

    // ---- DimensionHelper tests ----

    @Test
    void dimensionHelperAnalysisY() {
        assertEquals(320, DimensionHelper.analysisYForDimension(-64, 384));
        assertEquals(256, DimensionHelper.analysisYForDimension(0, 256));
        assertEquals(128, DimensionHelper.analysisYForDimension(-64, 192));
    }

    @Test
    void dimensionHelperIsValidY() {
        assertTrue(DimensionHelper.isValidY(0, -64, 384));
        assertTrue(DimensionHelper.isValidY(-64, -64, 384));
        assertTrue(DimensionHelper.isValidY(319, -64, 384));
        assertFalse(DimensionHelper.isValidY(-65, -64, 384));
        assertFalse(DimensionHelper.isValidY(320, -64, 384));
    }

    @Test
    void dimensionHelperClampY() {
        assertEquals(0, DimensionHelper.clampY(0, -64, 384));
        assertEquals(-64, DimensionHelper.clampY(-100, -64, 384));
        assertEquals(319, DimensionHelper.clampY(500, -64, 384));
        assertEquals(100, DimensionHelper.clampY(100, -64, 384));
    }
}
