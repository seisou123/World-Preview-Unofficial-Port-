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

    @Test
    void incrementalSlopeMatchesWindowedRecompute() {
        MetricAggregator agg = new MetricAggregator(9, 4);
        long seed = 42; java.util.Random rnd = new java.util.Random(seed);
        short[][] h = new short[3][3];
        for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) h[z][x] = (short) (60 + rnd.nextInt(40));
        // 打乱插入顺序，模拟多线程完成次序
        List<int[]> cells = new java.util.ArrayList<>();
        for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) cells.add(new int[]{x, z});
        java.util.Collections.shuffle(cells, rnd);
        for (int[] c : cells) agg.addSample(c[0] * 4, c[1] * 4, (short) 1, h[c[1]][c[0]]);
        RegionMetrics m = agg.snapshot();
        // 全量对拍：所有 +x/+z 边（同一网格、同一 step）
        double sum = 0, max = 0; long pairs = 0, flat = 0;
        for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) {
            if (x + 1 < 3) { long d = Math.abs((long) h[z][x + 1] - h[z][x]); sum += d; max = Math.max(max, d); pairs++; if (d <= 1) flat++; }
            if (z + 1 < 3) { long d = Math.abs((long) h[z + 1][x] - h[z][x]); sum += d; max = Math.max(max, d); pairs++; if (d <= 1) flat++; }
        }
        org.junit.jupiter.api.Assertions.assertEquals(sum / pairs / 4.0, m.meanSlope().getAsDouble(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(max / 4.0, m.maxSlope().getAsDouble(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals((double) flat / pairs, m.flatRatio(), 1e-9);
    }

    @Test
    void histogramMedianAndWaterShare() {
        MetricAggregator agg = new MetricAggregator(4, 1);
        agg.setSeaLevel(63);
        agg.addSample(0, 0, (short) 1, (short) 70); // 陆地
        agg.addSample(4, 0, (short) 1, (short) 62); // 水
        agg.addSample(8, 0, (short) 1, (short) 64);
        agg.addSample(12, 0, (short) 1, (short) 70);
        RegionMetrics m = agg.snapshot();
        org.junit.jupiter.api.Assertions.assertEquals(0.25, m.waterShare(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(64.0, m.medianHeight().getAsDouble(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(62, m.minHeight().getAsInt());
        org.junit.jupiter.api.Assertions.assertEquals(70, m.maxHeight().getAsInt());
        org.junit.jupiter.api.Assertions.assertEquals(9, m.heightHistogram().length); // 62..70
        org.junit.jupiter.api.Assertions.assertEquals(62, m.histogramMinY());
        org.junit.jupiter.api.Assertions.assertEquals(1, m.heightHistogram()[0]); // y=62 一格
    }

    @Test
    void snapshotIsCheapAndConsistent() { // 快照重复调用返回同一实例（dirty flag 不失效）
        MetricAggregator agg = new MetricAggregator(2, 1);
        agg.addSample(0, 0, (short) 1, (short) 10);
        RegionMetrics a = agg.snapshot();
        org.junit.jupiter.api.Assertions.assertSame(a, agg.snapshot());
        agg.addSample(1, 1, (short) 2, (short) 20);
        RegionMetrics b = agg.snapshot();
        org.junit.jupiter.api.Assertions.assertNotSame(a, b);
        org.junit.jupiter.api.Assertions.assertEquals(15.0, b.meanHeight().getAsDouble(), 1e-9);
    }

    @Test
    void negativeHeightsProduceWrappedHistogramWithoutCrashing() {
        // 1.21 overworld surface heights can be negative (min world Y = -64);
        // the histogram window must wrap the 0xFFFF index space, not throw.
        MetricAggregator aggregator = new MetricAggregator(3, 1);
        aggregator.addSample(0, 0, (short) 1, (short) -64);
        aggregator.addSample(1, 0, (short) 1, (short) 0);
        aggregator.addSample(2, 0, (short) 1, (short) 70);

        RegionMetrics metrics = aggregator.snapshot();

        assertEquals(-64, metrics.histogramMinY());
        assertEquals(135, metrics.heightHistogram().length); // -64..70
        assertEquals(1, metrics.heightHistogram()[0]);       // y=-64 is the first bin
        assertEquals(0.0, metrics.medianHeight().getAsDouble(), 1e-9);
        assertEquals(-64, metrics.minHeight().orElseThrow());
        assertEquals(70, metrics.maxHeight().orElseThrow());
    }
}
