package caeruleusTait.world.preview.backend.analysis;

public record AnalysisRequest(
        long seed,
        String dimension,
        Region region,
        int y,
        int sampleStep,
        boolean includeHeight,
        boolean includeIntersections,
        boolean includeNoise
) {
    public AnalysisRequest {
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
        }
        if (region == null) {
            throw new NullPointerException("region must not be null");
        }
        if (sampleStep < 1) {
            throw new IllegalArgumentException("sampleStep must be at least 1");
        }
    }
}
