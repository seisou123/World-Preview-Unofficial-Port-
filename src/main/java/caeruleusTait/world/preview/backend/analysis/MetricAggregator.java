package caeruleusTait.world.preview.backend.analysis;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class MetricAggregator {
    public record Sample(int x, int z, short biome, short height) {}

    private final long expectedSamples;
    private final int sampleStep;
    private final Map<Short, Long> biomeCounts = new HashMap<>();
    /**
     * Persistent sample grid: a new sample pairs immediately with the
     * already-present neighbors at (x±step, z) / (x, z±step), so each
     * undirected +x/+z grid edge is counted exactly once when its second
     * endpoint arrives — regardless of worker completion order. Snapshots
     * never recompute slopes.
     */
    private final Map<Long, Short> heightByPos = new HashMap<>();
    /** Height histogram, index = height & 0xFFFF (height is a short; world heights fit easily). */
    private final int[] heightHistogram = new int[65536];
    private int histMinY = Integer.MAX_VALUE;   // smallest height seen so far
    private int histMaxY = Integer.MIN_VALUE;   // largest height seen so far
    @Nullable private Integer seaLevel;         // null = do not count water
    private long presentSamples;
    private long heightCount;
    private long waterSamples;
    private double heightMean;
    private double heightM2;
    private long slopeSumDelta;   // Σ|Δh| in raw block units (divided by step per block on read)
    private long slopeMaxDelta;
    private long slopePairs;
    private long flatPairs;

    // Memoized snapshot: the analysis UI polls result()/progress() every tick
    // (20/s) while workers keep mutating the counts; without the dirty flag
    // each poll would rebuild the histogram window and boxed map on the client
    // thread. Callers already synchronize on this instance.
    @Nullable private RegionMetrics snapshotCache;
    private boolean snapshotDirty = true;

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

    /**
     * Sets the sea level for water share accounting: height samples strictly
     * below it count as water. {@code null} disables water accounting; values
     * outside short range are treated as {@code null}. Safe to call at any
     * time — samples already accumulated are re-counted from the histogram.
     */
    public void setSeaLevel(@Nullable Integer seaLevel) {
        this.seaLevel = seaLevel != null && seaLevel > Short.MIN_VALUE && seaLevel < Short.MAX_VALUE ? seaLevel : null;
        waterSamples = this.seaLevel == null ? 0 : countBelow(this.seaLevel);
        snapshotDirty = true;
    }

    /** Number of accumulated height samples strictly below {@code exclusiveUpper}. */
    private long countBelow(int exclusiveUpper) {
        if (heightCount == 0) return 0;
        long count = 0;
        int upper = Math.min(exclusiveUpper - 1, histMaxY);
        for (int y = histMinY; y <= upper; y++) {
            count += heightHistogram[y & 0xFFFF];
        }
        return count;
    }

    public void addBatch(Iterable<Sample> samples) {
        for (Sample sample : samples) addSample(sample.x(), sample.z(), sample.biome(), sample.height());
    }

    /**
     * Cheap count of samples with biome or height data, without building a
     * {@link RegionMetrics} snapshot. Safe under the same monitor that guards
     * {@link #addSample}.
     */
    public long presentSampleCount() {
        return presentSamples;
    }

    /**
     * Cheap test for "has data worth exporting", without building a
     * {@link RegionMetrics} snapshot.
     */
    public boolean hasExportableData() {
        return presentSamples > 0 && !biomeCounts.isEmpty();
    }

    public void addSample(int x, int z, short biome, short height) {
        boolean biomePresent = biome != Short.MIN_VALUE;
        boolean heightPresent = height != Short.MIN_VALUE;
        if (biomePresent) biomeCounts.merge(biome, 1L, Long::sum);
        if (biomePresent || heightPresent) presentSamples++;
        snapshotDirty = true;
        if (!heightPresent) return;
        heightCount++;
        if (seaLevel != null && height < seaLevel) waterSamples++;
        heightHistogram[height & 0xFFFF]++;
        histMinY = Math.min(histMinY, height);
        histMaxY = Math.max(histMaxY, height);
        double value = height;
        double delta = value - heightMean;
        heightMean += delta / heightCount;
        heightM2 += delta * (value - heightMean);
        // Incremental slope pairing: each undirected +x/+z grid edge is counted
        // exactly once, at whichever endpoint arrives second (all four neighbor
        // directions are checked), so results are insertion-order independent.
        Short left = heightByPos.get(pack(x - sampleStep, z));
        if (left != null) addEdge(left, height);
        Short right = heightByPos.get(pack(x + sampleStep, z));
        if (right != null) addEdge(right, height);
        Short up = heightByPos.get(pack(x, z - sampleStep));
        if (up != null) addEdge(up, height);
        Short down = heightByPos.get(pack(x, z + sampleStep));
        if (down != null) addEdge(down, height);
        heightByPos.put(pack(x, z), height);
    }

    private void addEdge(short a, short b) {
        long d = Math.abs((long) b - a);
        slopeSumDelta += d;
        slopeMaxDelta = Math.max(slopeMaxDelta, d);
        slopePairs++;
        if (d <= 1L) flatPairs++;
    }

    public void reset() {
        biomeCounts.clear();
        heightByPos.clear();
        Arrays.fill(heightHistogram, 0);
        histMinY = Integer.MAX_VALUE;
        histMaxY = Integer.MIN_VALUE;
        seaLevel = null;
        presentSamples = 0;
        heightCount = 0;
        waterSamples = 0;
        heightMean = 0;
        heightM2 = 0;
        slopeSumDelta = 0;
        slopeMaxDelta = 0;
        slopePairs = 0;
        flatPairs = 0;
        snapshotCache = null;
        snapshotDirty = true;
    }

    public RegionMetrics snapshot() {
        if (!snapshotDirty) {
            return snapshotCache;
        }
        OptionalInt min = histMinY == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(histMinY);
        OptionalInt max = histMaxY == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(histMaxY);
        OptionalDouble median = OptionalDouble.empty();
        if (heightCount > 0) {
            // Median with parity to the old sorted-array implementation:
            // lo = smallest y reaching half the samples, hi = smallest y reaching
            // half plus one; exact middle value for odd counts, (lo + hi) / 2
            // (interpolated) for even counts.
            long seen = 0;
            long lo = -1, hi = -1;
            for (int y = histMinY; y <= histMaxY; y++) {
                seen += heightHistogram[y & 0xFFFF];
                if (lo < 0 && seen * 2 >= heightCount) lo = y;
                if (seen * 2 >= heightCount + 1) {
                    hi = y;
                    break;
                }
            }
            median = OptionalDouble.of(heightCount % 2 == 1 ? lo : (lo + hi) / 2.0);
        }
        OptionalDouble meanSlope = slopePairs == 0 ? OptionalDouble.empty()
                : OptionalDouble.of((double) slopeSumDelta / slopePairs / sampleStep);
        OptionalDouble maxSlope = slopePairs == 0 ? OptionalDouble.empty()
                : OptionalDouble.of((double) slopeMaxDelta / sampleStep);
        double flatRatio = slopePairs == 0 ? 0.0 : (double) flatPairs / slopePairs;
        double waterShare = heightCount == 0 ? 0.0 : (double) waterSamples / heightCount;
        int[] histCopy = histogramWindow();
        AnalysisDataState state = presentSamples < expectedSamples
                ? AnalysisDataState.PENDING : AnalysisDataState.SAMPLED;
        snapshotCache = new RegionMetrics(state, expectedSamples, presentSamples, biomeCounts,
                min, max,
                heightCount == 0 ? OptionalDouble.empty() : OptionalDouble.of(heightMean),
                median,
                heightCount == 0 ? OptionalDouble.empty() : OptionalDouble.of(Math.sqrt(heightM2 / heightCount)),
                meanSlope, maxSlope, flatRatio, "",
                waterShare, histCopy, heightCount == 0 ? 0 : histMinY);
        snapshotDirty = false;
        return snapshotCache;
    }

    /**
     * Copy of the histogram window [histMinY..histMaxY]. Height indices live in
     * the unsigned 16-bit space (height is a short), so a window spanning
     * negative and positive heights is assembled from two segments.
     */
    private int[] histogramWindow() {
        if (heightCount == 0) return new int[0];
        int from = histMinY & 0xFFFF;
        int to = (histMaxY & 0xFFFF) + 1;
        if (from < to) return Arrays.copyOfRange(heightHistogram, from, to);
        int[] window = new int[(65536 - from) + to];
        System.arraycopy(heightHistogram, from, window, 0, 65536 - from);
        System.arraycopy(heightHistogram, 0, window, 65536 - from, to);
        return window;
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }
}
