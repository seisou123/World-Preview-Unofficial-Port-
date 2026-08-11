package caeruleusTait.world.preview.backend.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class MetricAggregator {
    public record Sample(int x, int z, short biome, short height) {}

    private final long expectedSamples;
    private final int sampleStep;
    private final Map<Short, Long> biomeCounts = new HashMap<>();
    private final List<Sample> validHeightSamples = new ArrayList<>();
    private long presentSamples;
    private long heightCount;
    private double heightMean;
    private double heightM2;
    private String unavailableReason = "";

    public MetricAggregator(long expectedSamples) {
        this(expectedSamples, 1);
    }

    public MetricAggregator(long expectedSamples, int sampleStep) {
        if (expectedSamples < 0) throw new IllegalArgumentException("expectedSamples must be >= 0");
        if (sampleStep < 1) throw new IllegalArgumentException("sampleStep must be at least 1");
        this.expectedSamples = expectedSamples;
        this.sampleStep = sampleStep;
    }

    public int sampleStep() {
        return sampleStep;
    }

    public void addBatch(Iterable<Sample> samples) {
        for (Sample sample : samples) addSample(sample.x(), sample.z(), sample.biome(), sample.height());
    }

    public void addSample(int x, int z, short biome, short height) {
        boolean biomePresent = biome != Short.MIN_VALUE;
        boolean heightPresent = height != Short.MIN_VALUE;
        if (biomePresent) biomeCounts.merge(biome, 1L, Long::sum);
        if (biomePresent || heightPresent) presentSamples++;
        if (heightPresent) {
            validHeightSamples.add(new Sample(x, z, biome, height));
            heightCount++;
            double value = height;
            double delta = value - heightMean;
            heightMean += delta / heightCount;
            heightM2 += delta * (value - heightMean);
        }
    }

    public void markUnavailable(String reason) {
        unavailableReason = reason == null ? "" : reason;
    }

    public void reset() {
        biomeCounts.clear();
        validHeightSamples.clear();
        presentSamples = 0;
        heightCount = 0;
        heightMean = 0;
        heightM2 = 0;
        unavailableReason = "";
    }

    public RegionMetrics snapshot() {
        List<Sample> heightSamples = validHeightSamples;
        OptionalInt min = OptionalInt.empty();
        OptionalInt max = OptionalInt.empty();
        OptionalDouble median = OptionalDouble.empty();
        OptionalDouble meanSlope = OptionalDouble.empty();
        OptionalDouble maxSlope = OptionalDouble.empty();
        double flatRatio = 0.0;
        if (!heightSamples.isEmpty()) {
            int[] heights = heightSamples.stream().mapToInt(Sample::height).sorted().toArray();
            min = OptionalInt.of(heights[0]);
            max = OptionalInt.of(heights[heights.length - 1]);
            median = OptionalDouble.of(heights.length % 2 == 1
                    ? heights[heights.length / 2]
                    : (heights[heights.length / 2 - 1] + heights[heights.length / 2]) / 2.0);
        }

        // Exact 4-neighborhood on the analysis grid: only +x and +z edges of length sampleStep
        // so each undirected edge is counted once (no diagonals, no sorted-order wrap).
        Map<Long, Short> heightByPos = new HashMap<>(Math.max(16, heightSamples.size() * 2));
        for (Sample sample : heightSamples) {
            heightByPos.put(pack(sample.x(), sample.z()), sample.height());
        }
        double slopeSum = 0.0;
        double slopeMax = 0.0;
        long pairs = 0;
        long flatPairs = 0;
        for (Sample sample : heightSamples) {
            Short right = heightByPos.get(pack(sample.x() + sampleStep, sample.z()));
            if (right != null) {
                double slope = Math.abs((double) right - sample.height()) / (double) sampleStep;
                slopeSum += slope;
                slopeMax = Math.max(slopeMax, slope);
                pairs++;
                if (Math.abs((long) right - sample.height()) <= 1L) flatPairs++;
            }
            Short down = heightByPos.get(pack(sample.x(), sample.z() + sampleStep));
            if (down != null) {
                double slope = Math.abs((double) down - sample.height()) / (double) sampleStep;
                slopeSum += slope;
                slopeMax = Math.max(slopeMax, slope);
                pairs++;
                if (Math.abs((long) down - sample.height()) <= 1L) flatPairs++;
            }
        }
        if (pairs > 0) {
            meanSlope = OptionalDouble.of(slopeSum / pairs);
            maxSlope = OptionalDouble.of(slopeMax);
            flatRatio = (double) flatPairs / pairs;
        }

        AnalysisDataState state;
        if (presentSamples < expectedSamples) {
            state = AnalysisDataState.PENDING;
        } else if (heightCount == 0 && biomeCounts.isEmpty() && !unavailableReason.isEmpty()) {
            state = AnalysisDataState.UNAVAILABLE;
        } else {
            state = AnalysisDataState.SAMPLED;
        }
        return new RegionMetrics(state, expectedSamples, presentSamples, biomeCounts,
                min, max,
                heightCount == 0 ? OptionalDouble.empty() : OptionalDouble.of(heightMean),
                median,
                heightCount == 0 ? OptionalDouble.empty() : OptionalDouble.of(Math.sqrt(heightM2 / heightCount)),
                meanSlope, maxSlope, flatRatio, unavailableReason);
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }
}
