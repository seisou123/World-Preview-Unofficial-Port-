package caeruleusTait.world.preview.domain.preview;

/**
 * Sampling specification: data type toggles, precision, and range.
 *
 * <p>Replaces the per-component sampling parameter configuration
 * previously spread across RenderSettings and WorkManager.
 */
public record PreviewSamplingSpec(
        int quartStride,
        boolean sampleBiomes,
        boolean sampleStructures,
        boolean sampleHeightmap,
        boolean sampleIntersections,
        boolean storeNoiseSamples,
        boolean buildFullVertChunk,
        boolean backgroundSampleVertChunk
) {
    public PreviewSamplingSpec {
        if (quartStride < 1) throw new IllegalArgumentException("quartStride must be >= 1");
    }

    /** Returns the effective pixels-per-chunk based on quart stride. */
    public int pixelsPerChunk() {
        return quartStride * 4;
    }

    /** Returns {@code true} if any data type is enabled. */
    public boolean anyEnabled() {
        return sampleBiomes || sampleStructures || sampleHeightmap || sampleIntersections || storeNoiseSamples;
    }
}
