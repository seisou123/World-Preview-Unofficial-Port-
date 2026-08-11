package caeruleusTait.world.preview.infra.sampler;

import java.util.Set;

/**
 * Configuration for a chunk sampler.
 *
 * <p>Specifies the sampling precision, data types to collect,
 * and the Y-level range to sample.
 *
 * <p>Replaces the ad-hoc configuration scattered across
 * {@code RenderSettings}, {@code WorldPreviewConfig}, and individual
 * {@code WorkUnit} implementations.
 */
public record SamplerConfig(
        int quartStride,
        int yMin,
        int yMax,
        boolean sampleBiomes,
        boolean sampleStructures,
        boolean sampleHeightmap,
        boolean sampleIntersections,
        boolean sampleNoise,
        boolean buildFullVerticalChunk
) {

    public SamplerConfig {
        if (quartStride < 1) {
            throw new IllegalArgumentException("quartStride must be >= 1, got " + quartStride);
        }
        if (yMin > yMax) {
            throw new IllegalArgumentException("yMin (" + yMin + ") must be <= yMax (" + yMax + ")");
        }
    }

    /**
     * Returns the number of blocks per quart sample.
     * A stride of 1 means every block is sampled; 4 means every 4th block.
     */
    public int blockStride() {
        return quartStride * 4;
    }

    /** Returns the set of data types that are enabled for sampling. */
    public Set<DataType> enabledDataTypes() {
        Set<DataType> types = new java.util.LinkedHashSet<>();
        if (sampleBiomes) types.add(DataType.BIOME);
        if (sampleStructures) types.add(DataType.STRUCTURE);
        if (sampleHeightmap) types.add(DataType.HEIGHT);
        if (sampleIntersections) types.add(DataType.INTERSECTION);
        if (sampleNoise) types.add(DataType.NOISE);
        return types;
    }

    /** Data types that can be sampled. */
    public enum DataType {
        BIOME,
        STRUCTURE,
        HEIGHT,
        INTERSECTION,
        NOISE
    }

    /** Creates a builder for constructing a SamplerConfig. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link SamplerConfig}. */
    public static final class Builder {
        private int quartStride = 1;
        private int yMin = -64;
        private int yMax = 320;
        private boolean sampleBiomes = true;
        private boolean sampleStructures = false;
        private boolean sampleHeightmap = false;
        private boolean sampleIntersections = false;
        private boolean sampleNoise = false;
        private boolean buildFullVerticalChunk = false;

        private Builder() {}

        public Builder quartStride(int val) { quartStride = val; return this; }
        public Builder yMin(int val) { yMin = val; return this; }
        public Builder yMax(int val) { yMax = val; return this; }
        public Builder sampleBiomes(boolean val) { sampleBiomes = val; return this; }
        public Builder sampleStructures(boolean val) { sampleStructures = val; return this; }
        public Builder sampleHeightmap(boolean val) { sampleHeightmap = val; return this; }
        public Builder sampleIntersections(boolean val) { sampleIntersections = val; return this; }
        public Builder sampleNoise(boolean val) { sampleNoise = val; return this; }
        public Builder buildFullVerticalChunk(boolean val) { buildFullVerticalChunk = val; return this; }

        public SamplerConfig build() {
            return new SamplerConfig(quartStride, yMin, yMax,
                    sampleBiomes, sampleStructures, sampleHeightmap,
                    sampleIntersections, sampleNoise, buildFullVerticalChunk);
        }
    }
}
