package caeruleusTait.world.preview.backend.analysis;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public record RegionMetrics(
        AnalysisDataState state,
        long expectedSamples,
        long presentSamples,
        Map<Short, Long> biomeCounts,
        OptionalInt minHeight,
        OptionalInt maxHeight,
        OptionalDouble meanHeight,
        OptionalDouble medianHeight,
        OptionalDouble standardDeviation,
        OptionalDouble meanSlope,
        OptionalDouble maxSlope,
        double flatRatio,
        String unavailableReason) {

    public RegionMetrics {
        state = Objects.requireNonNull(state, "state");
        if (expectedSamples < 0 || presentSamples < 0 || presentSamples > expectedSamples) {
            throw new IllegalArgumentException("invalid sample counts");
        }
        biomeCounts = Map.copyOf(Objects.requireNonNull(biomeCounts, "biomeCounts"));
        minHeight = Objects.requireNonNull(minHeight, "minHeight");
        maxHeight = Objects.requireNonNull(maxHeight, "maxHeight");
        meanHeight = Objects.requireNonNull(meanHeight, "meanHeight");
        medianHeight = Objects.requireNonNull(medianHeight, "medianHeight");
        standardDeviation = Objects.requireNonNull(standardDeviation, "standardDeviation");
        meanSlope = Objects.requireNonNull(meanSlope, "meanSlope");
        maxSlope = Objects.requireNonNull(maxSlope, "maxSlope");
        if (!Double.isFinite(flatRatio) || flatRatio < 0.0 || flatRatio > 1.0) {
            throw new IllegalArgumentException("flatRatio must be between 0 and 1");
        }
        unavailableReason = unavailableReason == null ? "" : unavailableReason;
    }

    public double coverage() {
        return expectedSamples == 0 ? 1.0 : (double) presentSamples / expectedSamples;
    }
}
