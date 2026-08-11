package caeruleusTait.world.preview.infra.sampler;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the infra/sampler module.
 */
class SamplerTest {

    // ---- SamplerConfig tests ----

    @Test
    void samplerConfigValidatesQuartStride() {
        assertThrows(IllegalArgumentException.class, () -> new SamplerConfig(0, 0, 100, true, false, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new SamplerConfig(-1, 0, 100, true, false, false, false, false, false));
        assertDoesNotThrow(() -> new SamplerConfig(1, 0, 100, true, false, false, false, false, false));
    }

    @Test
    void samplerConfigValidatesYRange() {
        assertThrows(IllegalArgumentException.class, () -> new SamplerConfig(1, 100, 50, true, false, false, false, false, false));
        assertDoesNotThrow(() -> new SamplerConfig(1, 50, 100, true, false, false, false, false, false));
        assertDoesNotThrow(() -> new SamplerConfig(1, 50, 50, true, false, false, false, false, false));
    }

    @Test
    void samplerConfigBlockStride() {
        assertEquals(4, new SamplerConfig(1, 0, 100, true, false, false, false, false, false).blockStride());
        assertEquals(8, new SamplerConfig(2, 0, 100, true, false, false, false, false, false).blockStride());
        assertEquals(16, new SamplerConfig(4, 0, 100, true, false, false, false, false, false).blockStride());
    }

    @Test
    void samplerConfigEnabledDataTypes() {
        SamplerConfig config = new SamplerConfig(1, 0, 100, true, true, false, false, true, false);
        Set<SamplerConfig.DataType> types = config.enabledDataTypes();

        assertEquals(3, types.size());
        assertTrue(types.contains(SamplerConfig.DataType.BIOME));
        assertTrue(types.contains(SamplerConfig.DataType.STRUCTURE));
        assertTrue(types.contains(SamplerConfig.DataType.NOISE));
        assertFalse(types.contains(SamplerConfig.DataType.HEIGHT));
        assertFalse(types.contains(SamplerConfig.DataType.INTERSECTION));
    }

    @Test
    void samplerConfigBuilder() {
        SamplerConfig config = SamplerConfig.builder()
                .quartStride(2)
                .yMin(-50)
                .yMax(200)
                .sampleBiomes(true)
                .sampleStructures(true)
                .sampleHeightmap(false)
                .sampleIntersections(false)
                .sampleNoise(true)
                .buildFullVerticalChunk(true)
                .build();

        assertEquals(2, config.quartStride());
        assertEquals(-50, config.yMin());
        assertEquals(200, config.yMax());
        assertTrue(config.sampleBiomes());
        assertTrue(config.sampleStructures());
        assertFalse(config.sampleHeightmap());
        assertFalse(config.sampleIntersections());
        assertTrue(config.sampleNoise());
        assertTrue(config.buildFullVerticalChunk());
    }

    @Test
    void samplerConfigBuilderDefaultValues() {
        SamplerConfig config = SamplerConfig.builder().build();

        assertEquals(1, config.quartStride());
        assertEquals(-64, config.yMin());
        assertEquals(320, config.yMax());
        assertTrue(config.sampleBiomes());
        assertFalse(config.sampleStructures());
        assertFalse(config.sampleHeightmap());
        assertFalse(config.sampleIntersections());
        assertFalse(config.sampleNoise());
        assertFalse(config.buildFullVerticalChunk());
    }

    // ---- SamplerResult tests ----

    @Test
    void samplerResultImmutable() {
        Map<Long, Short> biomes = new HashMap<>();
        biomes.put(1L, (short) 5);
        Map<Long, Integer> heights = new HashMap<>();
        heights.put(2L, 64);

        SamplerResult result = new SamplerResult(0, 0, 64, biomes, Map.of(), heights, Map.of(), Map.of());

        // Modify original maps
        biomes.put(3L, (short) 10);
        heights.put(4L, 100);

        // Result should be unchanged
        assertEquals(1, result.biomes().size());
        assertEquals(1, result.heights().size());
        assertTrue(result.biomes().containsKey(1L));
        assertTrue(result.heights().containsKey(2L));
    }

    @Test
    void samplerResultHandlesNullMaps() {
        SamplerResult result = new SamplerResult(0, 0, 64, null, null, null, null, null);

        assertTrue(result.biomes().isEmpty());
        assertTrue(result.structures().isEmpty());
        assertTrue(result.heights().isEmpty());
        assertTrue(result.noise().isEmpty());
        assertTrue(result.intersections().isEmpty());
    }

    @Test
    void samplerResultEmptyFactory() {
        SamplerResult result = SamplerResult.empty(5, 10, 64);

        assertEquals(5, result.chunkX());
        assertEquals(10, result.chunkZ());
        assertEquals(64, result.y());
        assertTrue(result.isEmpty());
    }

    @Test
    void samplerResultTotalSamples() {
        Map<Long, Short> biomes = Map.of(1L, (short) 5, 2L, (short) 10);
        Map<Long, Integer> heights = Map.of(3L, 64);

        SamplerResult result = new SamplerResult(0, 0, 64, biomes, Map.of(), heights, Map.of(), Map.of());

        assertEquals(3, result.totalSamples());
    }

    @Test
    void samplerResultIsEmpty() {
        SamplerResult empty = SamplerResult.empty(0, 0, 64);
        assertTrue(empty.isEmpty());

        Map<Long, Short> biomes = Map.of(1L, (short) 5);
        SamplerResult nonEmpty = new SamplerResult(0, 0, 64, biomes, Map.of(), Map.of(), Map.of(), Map.of());
        assertFalse(nonEmpty.isEmpty());
    }

    @Test
    void samplerResultBuilder() {
        SamplerResult.StructureStart structure = new SamplerResult.StructureStart(
                "minecraft:village", 100, 200, 80, 180, 120, 220
        );
        SamplerResult.NoiseSample noise = new SamplerResult.NoiseSample(
                0.5f, 0.7f, -0.3f, 0.2f, 0.1f, 0.0f
        );
        SamplerResult.Intersection intersection = new SamplerResult.Intersection(
                "minecraft:fortress", 50, 40, 60
        );

        SamplerResult result = SamplerResult.builder(5, 10, 64)
                .biome(1L, (short) 5)
                .biome(2L, (short) 10)
                .structure(3L, structure)
                .height(4L, 70)
                .noise(5L, noise)
                .intersection(6L, intersection)
                .build();

        assertEquals(5, result.chunkX());
        assertEquals(10, result.chunkZ());
        assertEquals(64, result.y());
        assertEquals(2, result.biomes().size());
        assertEquals(1, result.structures().size());
        assertEquals(1, result.heights().size());
        assertEquals(1, result.noise().size());
        assertEquals(1, result.intersections().size());
    }

    // ---- SamplerResult nested records ----

    @Test
    void samplerResultStructureStart() {
        SamplerResult.StructureStart start = new SamplerResult.StructureStart(
                "minecraft:village", 100, 200, 80, 180, 120, 220
        );

        assertEquals("minecraft:village", start.structureId());
        assertEquals(100, start.centerX());
        assertEquals(200, start.centerZ());
        assertEquals(80, start.boundingBoxMinX());
        assertEquals(180, start.boundingBoxMinZ());
        assertEquals(120, start.boundingBoxMaxX());
        assertEquals(220, start.boundingBoxMaxZ());
    }

    @Test
    void samplerResultNoiseSample() {
        SamplerResult.NoiseSample noise = new SamplerResult.NoiseSample(
                0.5f, 0.7f, -0.3f, 0.2f, 0.1f, 0.0f
        );

        assertEquals(0.5f, noise.temperature());
        assertEquals(0.7f, noise.humidity());
        assertEquals(-0.3f, noise.continentalness());
        assertEquals(0.2f, noise.erosion());
        assertEquals(0.1f, noise.depth());
        assertEquals(0.0f, noise.weirdness());
    }

    @Test
    void samplerResultIntersection() {
        SamplerResult.Intersection intersection = new SamplerResult.Intersection(
                "minecraft:fortress", 50, 40, 60
        );

        assertEquals("minecraft:fortress", intersection.structureId());
        assertEquals(50, intersection.blockX());
        assertEquals(40, intersection.blockY());
        assertEquals(60, intersection.blockZ());
    }

    // ---- SamplerFactory tests ----

    @Test
    void samplerFactoryRegisterAndCreate() {
        SamplerConfig config = new SamplerConfig(1, 0, 100, true, false, false, false, false, false);
        SamplerFactory factory = new SamplerFactory();

        ChunkSampler sampler = new ChunkSampler() {
            @Override
            public SamplerConfig config() {
                return config;
            }

            @Override
            public SamplerResult sample(int chunkX, int chunkZ, int y) {
                return SamplerResult.empty(chunkX, chunkZ, y);
            }
        };

        factory.register("TEST", c -> sampler);

        assertTrue(factory.isRegistered("test"));
        assertTrue(factory.isRegistered("TEST"));

        ChunkSampler created = factory.create("test", config);
        assertSame(sampler, created);
    }

    @Test
    void samplerFactoryRejectsNullTypeName() {
        SamplerFactory factory = new SamplerFactory();
        assertThrows(NullPointerException.class, () -> factory.register(null, c -> null));
    }

    @Test
    void samplerFactoryRejectsBlankTypeName() {
        SamplerFactory factory = new SamplerFactory();
        assertThrows(IllegalArgumentException.class, () -> factory.register("", c -> null));
        assertThrows(IllegalArgumentException.class, () -> factory.register("  ", c -> null));
    }

    @Test
    void samplerFactoryRejectsNullCreator() {
        SamplerFactory factory = new SamplerFactory();
        assertThrows(NullPointerException.class, () -> factory.register("TEST", null));
    }

    @Test
    void samplerFactoryCreateThrowsForUnknownType() {
        SamplerFactory factory = new SamplerFactory();
        SamplerConfig config = new SamplerConfig(1, 0, 100, true, false, false, false, false, false);

        assertThrows(IllegalArgumentException.class, () -> factory.create("UNKNOWN", config));
    }

    @Test
    void samplerFactoryRegisteredTypes() {
        SamplerFactory factory = new SamplerFactory();
        factory.register("TYPE1", c -> null);
        factory.register("type2", c -> null);
        factory.register("Type3", c -> null);

        Set<String> types = factory.registeredTypes();

        assertEquals(3, types.size());
        assertTrue(types.contains("TYPE1"));
        assertTrue(types.contains("TYPE2"));
        assertTrue(types.contains("TYPE3"));
    }

    @Test
    void samplerFactoryUnregister() {
        SamplerFactory factory = new SamplerFactory();
        factory.register("TEST", c -> null);

        assertTrue(factory.isRegistered("test"));
        assertTrue(factory.unregister("test"));
        assertFalse(factory.isRegistered("test"));
        assertFalse(factory.unregister("test")); // already unregistered
    }

    @Test
    void samplerFactoryTypeNameCaseInsensitive() {
        SamplerFactory factory = new SamplerFactory();
        ChunkSampler sampler = new ChunkSampler() {
            @Override
            public SamplerConfig config() {
                return new SamplerConfig(1, 0, 100, true, false, false, false, false, false);
            }

            @Override
            public SamplerResult sample(int chunkX, int chunkZ, int y) {
                return SamplerResult.empty(chunkX, chunkZ, y);
            }
        };

        factory.register("test", c -> sampler);

        assertTrue(factory.isRegistered("TEST"));
        assertTrue(factory.isRegistered("test"));
        assertTrue(factory.isRegistered("Test"));

        assertSame(sampler, factory.create("TEST", new SamplerConfig(1, 0, 100, true, false, false, false, false, false)));
        assertSame(sampler, factory.create("test", new SamplerConfig(1, 0, 100, true, false, false, false, false, false)));
    }

    // ---- ChunkSampler interface tests (via mock implementation) ----

    @Test
    void chunkSamplerBlockStride() {
        SamplerConfig config = new SamplerConfig(2, 0, 100, true, false, false, false, false, false);
        ChunkSampler sampler = new ChunkSampler() {
            @Override
            public SamplerConfig config() {
                return config;
            }

            @Override
            public SamplerResult sample(int chunkX, int chunkZ, int y) {
                return SamplerResult.empty(chunkX, chunkZ, y);
            }
        };

        assertEquals(8, sampler.blockStride());
    }

    @Test
    void chunkSamplerPositionsPerChunk() {
        SamplerConfig config = new SamplerConfig(1, 0, 100, true, false, false, false, false, false);
        ChunkSampler sampler = new ChunkSampler() {
            @Override
            public SamplerConfig config() {
                return config;
            }

            @Override
            public SamplerResult sample(int chunkX, int chunkZ, int y) {
                return SamplerResult.empty(chunkX, chunkZ, y);
            }
        };

        // 16 blocks / 4 block stride = 4 per side, 4 * 4 = 16 per chunk
        assertEquals(16, sampler.positionsPerChunk());
    }

    @Test
    void chunkSamplerPositionsPerChunkWithDifferentStride() {
        SamplerConfig config = new SamplerConfig(4, 0, 100, true, false, false, false, false, false);
        ChunkSampler sampler = new ChunkSampler() {
            @Override
            public SamplerConfig config() {
                return config;
            }

            @Override
            public SamplerResult sample(int chunkX, int chunkZ, int y) {
                return SamplerResult.empty(chunkX, chunkZ, y);
            }
        };

        // 16 blocks / 16 block stride = 1 per side, 1 * 1 = 1 per chunk
        assertEquals(1, sampler.positionsPerChunk());
    }
}