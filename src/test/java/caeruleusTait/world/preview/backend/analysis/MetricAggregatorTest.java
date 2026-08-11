package caeruleusTait.world.preview.backend.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricAggregatorTest {
    @Test
    void aggregatesUnorderedSamplesAndReturnsAnImmutableSnapshot() {
        // Points on a line with step 4 — match analysis sampleStep for 4-neighbor edges.
        MetricAggregator aggregator = new MetricAggregator(5, 4);
        aggregator.addBatch(List.of(
                new MetricAggregator.Sample(8, 0, (short) 2, (short) 20),
                new MetricAggregator.Sample(0, 0, (short) 1, (short) 10)));
        aggregator.addBatch(List.of(
                new MetricAggregator.Sample(12, 0, (short) 2, (short) 30),
                new MetricAggregator.Sample(4, 0, (short) 1, (short) 10),
                new MetricAggregator.Sample(16, 0, (short) 2, (short) 30)));

        RegionMetrics metrics = aggregator.snapshot();

        assertEquals(Map.of((short) 1, 2L, (short) 2, 3L), metrics.biomeCounts());
        assertEquals(0.6, metrics.biomeCounts().get((short) 2) / 5.0, 1e-12);
        assertEquals(10, metrics.minHeight().orElseThrow());
        assertEquals(30, metrics.maxHeight().orElseThrow());
        assertEquals(20.0, metrics.meanHeight().orElseThrow(), 1e-12);
        assertEquals(20, metrics.medianHeight().orElseThrow());
        assertEquals(8.944271, metrics.standardDeviation().orElseThrow(), 1e-5);
        // Edges: (0-4 flat), (4-8 slope 10/4), (8-12 slope 10/4), (12-16 flat) => flat 0.5
        assertEquals(0.5, metrics.flatRatio(), 1e-12);
        assertEquals(AnalysisDataState.SAMPLED, metrics.state());
        assertThrows(UnsupportedOperationException.class,
                () -> metrics.biomeCounts().put((short) 3, 1L));
    }

    @Test
    void ignoresMissingValuesAndReportsPendingCoverage() {
        MetricAggregator aggregator = new MetricAggregator(3);
        aggregator.addSample(0, 0, (short) 1, (short) 10);
        aggregator.addSample(4, 0, Short.MIN_VALUE, (short) 20);
        aggregator.addSample(8, 0, (short) 2, Short.MIN_VALUE);

        RegionMetrics metrics = aggregator.snapshot();

        assertEquals(Map.of((short) 1, 1L, (short) 2, 1L), metrics.biomeCounts());
        // A sample counts as present if it has EITHER biome OR height data (Bug A1 fix)
        assertEquals(3, metrics.presentSamples());
        // All expected samples have been collected (at least partially)
        assertEquals(AnalysisDataState.SAMPLED, metrics.state());
        assertTrue(metrics.standardDeviation().isPresent());
    }

    @Test
    void reportsUnavailableWhenExplicitlyMarkedWithoutValidSamples() {
        MetricAggregator aggregator = new MetricAggregator(0);
        aggregator.markUnavailable("height data is unavailable");

        RegionMetrics metrics = aggregator.snapshot();

        assertEquals(AnalysisDataState.UNAVAILABLE, metrics.state());
        assertEquals("height data is unavailable", metrics.unavailableReason());
        assertEquals(1.0, metrics.coverage(), 1e-12);
    }

    @Test
    void slopeUsesExactSampleStepFourNeighborsOnly() {
        // Grid step 10: +x/+z edges only; no diagonal, no sorted-order wrap.
        MetricAggregator aggregator = new MetricAggregator(4, 10);
        aggregator.addBatch(List.of(
                new MetricAggregator.Sample(0, 0, (short) 1, (short) 0),
                new MetricAggregator.Sample(10, 0, (short) 1, (short) 0),
                new MetricAggregator.Sample(0, 10, (short) 1, (short) 10),
                new MetricAggregator.Sample(10, 10, (short) 1, (short) 10)));

        RegionMetrics metrics = aggregator.snapshot();

        // 2 horizontal flat (slope 0) + 2 vertical slope 1.0 => flatRatio 0.5, mean 0.5
        assertEquals(0.5, metrics.flatRatio(), 1e-12);
        assertEquals(0.5, metrics.meanSlope().orElseThrow(), 1e-12);
        assertEquals(1.0, metrics.maxSlope().orElseThrow(), 1e-12);
        assertEquals(0, metrics.minHeight().orElseThrow());
        assertEquals(10, metrics.maxHeight().orElseThrow());
        assertEquals(10, aggregator.sampleStep());
    }

    @Test
    void rejectsInvalidSampleStep() {
        assertThrows(IllegalArgumentException.class, () -> new MetricAggregator(1, 0));
    }

    @Test
    void doesNotConnectPointsFartherThanSampleStep() {
        // step=4: (0,0) and (8,0) are not direct neighbors (would need midpoint at x=4)
        MetricAggregator aggregator = new MetricAggregator(2, 4);
        aggregator.addSample(0, 0, (short) 1, (short) 0);
        aggregator.addSample(8, 0, (short) 1, (short) 40);

        RegionMetrics metrics = aggregator.snapshot();

        assertTrue(metrics.meanSlope().isEmpty());
        assertEquals(0.0, metrics.flatRatio(), 1e-12);
    }
}
