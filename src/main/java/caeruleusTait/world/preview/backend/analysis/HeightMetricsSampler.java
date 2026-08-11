package caeruleusTait.world.preview.backend.analysis;

import java.util.*;

/**
 * Extracts height sampling metrics from a region.
 *
 * <p>Previously embedded in {@code PreviewContainer.sampleHeightMetrics()},
 * this class provides testable height metric computation (mean, median,
 * variance, min, max, slopes) without GUI dependencies.
 */
public final class HeightMetricsSampler {

    /**
     * Computes height metrics from raw height samples arranged on a grid.
     *
     * @param heights    a list of height values, ordered row-by-row (x outer, z inner)
     * @param xs         the X coordinates corresponding to each column
     * @param zs         the Z coordinates corresponding to each row
     * @return the computed height metrics
     */
    public static HeightSampleResult compute(List<Double> heights, List<Integer> xs, List<Integer> zs) {
        if (heights.isEmpty()) {
            throw new IllegalStateException("height sampling returned no points");
        }

        List<Double> sorted = new ArrayList<>(heights);
        sorted.sort(Double::compareTo);

        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (double h : heights) {
            sum += h;
            if (h < min) min = h;
            if (h > max) max = h;
        }
        double mean = sum / heights.size();

        int n = sorted.size();
        double median = n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
                : sorted.get(n / 2);

        double variance = 0;
        for (double h : heights) {
            variance += (h - mean) * (h - mean);
        }
        variance /= heights.size();
        double stddev = Math.sqrt(variance);

        // Compute slopes
        List<Double> slopes = new ArrayList<>();
        Map<Long, Double> byCoord = new HashMap<>();
        int idx = 0;
        for (int xIdx = 0; xIdx < xs.size(); xIdx++) {
            for (int zIdx = 0; zIdx < zs.size(); zIdx++) {
                if (idx < heights.size()) {
                    long key = (((long) xs.get(xIdx)) << 32) ^ (zs.get(zIdx) & 0xffffffffL);
                    byCoord.put(key, heights.get(idx));
                    idx++;
                }
            }
        }

        for (int xIdx = 0; xIdx < xs.size(); xIdx++) {
            for (int zIdx = 0; zIdx < zs.size(); zIdx++) {
                int x = xs.get(xIdx);
                int z = zs.get(zIdx);
                long key = (((long) x) << 32) ^ (z & 0xffffffffL);
                double current = byCoord.getOrDefault(key, 0.0);

                if (xIdx + 1 < xs.size()) {
                    int eastX = xs.get(xIdx + 1);
                    double east = byCoord.getOrDefault((((long) eastX) << 32) ^ (z & 0xffffffffL), 0.0);
                    long dx = (long) eastX - x;
                    double horizontalDist = Math.hypot(dx, 0);
                    slopes.add(horizontalDist > 0 ? Math.abs(east - current) / horizontalDist : 0);
                }
                if (zIdx + 1 < zs.size()) {
                    int southZ = zs.get(zIdx + 1);
                    double south = byCoord.getOrDefault((((long) x) << 32) ^ (southZ & 0xffffffffL), 0.0);
                    long dz = (long) southZ - z;
                    double horizontalDist = Math.hypot(0, dz);
                    slopes.add(horizontalDist > 0 ? Math.abs(south - current) / horizontalDist : 0);
                }
            }
        }

        double avgSlope = slopes.isEmpty() ? 0 : slopes.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        return new HeightSampleResult(mean, median, variance, stddev, min, max, avgSlope, heights.size());
    }

    /** Immutable result of height sampling. */
    public record HeightSampleResult(
            double mean,
            double median,
            double variance,
            double stddev,
            double min,
            double max,
            double avgSlope,
            int sampleCount
    ) {}
}
