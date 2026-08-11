package caeruleusTait.world.preview.infra.sampler;

import java.util.*;

/**
 * Immutable result of sampling a chunk or region.
 *
 * <p>Contains the sampled biome IDs, structure starts, height data,
 * noise values, and intersection data.
 *
 * <p>Replaces the mutable {@code WorkResult} class that is currently
 * shared across {@code WorkUnit} implementations.
 */
public record SamplerResult(
        int chunkX,
        int chunkZ,
        int y,
        Map<Long, Short> biomes,
        Map<Long, StructureStart> structures,
        Map<Long, Integer> heights,
        Map<Long, NoiseSample> noise,
        Map<Long, Intersection> intersections
) {

    public SamplerResult {
        biomes = biomes != null ? Map.copyOf(biomes) : Map.of();
        structures = structures != null ? Map.copyOf(structures) : Map.of();
        heights = heights != null ? Map.copyOf(heights) : Map.of();
        noise = noise != null ? Map.copyOf(noise) : Map.of();
        intersections = intersections != null ? Map.copyOf(intersections) : Map.of();
    }

    /** Returns the total number of sampled positions. */
    public int totalSamples() {
        return biomes.size() + structures.size() + heights.size()
                + noise.size() + intersections.size();
    }

    /** Returns {@code true} if this result contains no sampled data. */
    public boolean isEmpty() {
        return biomes.isEmpty() && structures.isEmpty() && heights.isEmpty()
                && noise.isEmpty() && intersections.isEmpty();
    }

    /** Creates a new empty result for the given chunk coordinates. */
    public static SamplerResult empty(int chunkX, int chunkZ, int y) {
        return new SamplerResult(chunkX, chunkZ, y, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Creates a builder for constructing a SamplerResult. */
    public static Builder builder(int chunkX, int chunkZ, int y) {
        return new Builder(chunkX, chunkZ, y);
    }

    /** A structure start sampled from a chunk. */
    public record StructureStart(
            String structureId,
            int centerX,
            int centerZ,
            int boundingBoxMinX,
            int boundingBoxMinZ,
            int boundingBoxMaxX,
            int boundingBoxMaxZ
    ) {}

    /** A noise sample at a specific position. */
    public record NoiseSample(
            float temperature,
            float humidity,
            float continentalness,
            float erosion,
            float depth,
            float weirdness
    ) {}

    /** An intersection between a structure and a height level. */
    public record Intersection(
            String structureId,
            int blockX,
            int blockY,
            int blockZ
    ) {}

    /** Builder for {@link SamplerResult}. */
    public static final class Builder {
        private final int chunkX;
        private final int chunkZ;
        private final int y;
        private final Map<Long, Short> biomes = new HashMap<>();
        private final Map<Long, StructureStart> structures = new HashMap<>();
        private final Map<Long, Integer> heights = new HashMap<>();
        private final Map<Long, NoiseSample> noise = new HashMap<>();
        private final Map<Long, Intersection> intersections = new HashMap<>();

        private Builder(int chunkX, int chunkZ, int y) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.y = y;
        }

        public Builder biome(long posKey, short biomeId) {
            biomes.put(posKey, biomeId);
            return this;
        }

        public Builder structure(long posKey, StructureStart start) {
            structures.put(posKey, start);
            return this;
        }

        public Builder height(long posKey, int height) {
            heights.put(posKey, height);
            return this;
        }

        public Builder noise(long posKey, NoiseSample sample) {
            noise.put(posKey, sample);
            return this;
        }

        public Builder intersection(long posKey, Intersection intersection) {
            intersections.put(posKey, intersection);
            return this;
        }

        public SamplerResult build() {
            return new SamplerResult(chunkX, chunkZ, y,
                    biomes, structures, heights, noise, intersections);
        }
    }
}
