package caeruleusTait.world.preview.infra.minecraft;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the infra/minecraft module.
 */
class MinecraftTest {

    // ---- MinecraftBiomeProvider tests (using mock implementation) ----

    @Test
    void minecraftBiomeProviderMock() {
        MinecraftBiomeProvider provider = new MinecraftBiomeProvider() {
            @Override
            public String biomeAt(int x, int y, int z) {
                return "minecraft:plains";
            }

            @Override
            public String biomeAtQuart(int quartX, int quartY, int quartZ) {
                return "minecraft:plains";
            }

            @Override
            public Set<String> possibleBiomes() {
                return Set.of("minecraft:plains", "minecraft:forest", "minecraft:desert");
            }
        };

        assertEquals("minecraft:plains", provider.biomeAt(100, 64, 200));
        assertEquals("minecraft:plains", provider.biomeAtQuart(25, 16, 50));
        assertEquals(3, provider.possibleBiomes().size());
        assertTrue(provider.possibleBiomes().contains("minecraft:plains"));
    }

    @Test
    void minecraftBiomeProviderDefaultNoiseBiomeAt() {
        MinecraftBiomeProvider provider = new MinecraftBiomeProvider() {
            @Override
            public String biomeAt(int x, int y, int z) {
                return "minecraft:plains";
            }

            @Override
            public String biomeAtQuart(int quartX, int quartY, int quartZ) {
                return "minecraft:plains";
            }

            @Override
            public Set<String> possibleBiomes() {
                return Set.of("minecraft:plains");
            }
        };

        // Default implementation delegates to biomeAt()
        assertEquals("minecraft:plains", provider.noiseBiomeAt(50, 60, 70));
    }

    @Test
    void minecraftBiomeProviderNullBiome() {
        MinecraftBiomeProvider provider = new MinecraftBiomeProvider() {
            @Override
            public String biomeAt(int x, int y, int z) {
                return null;
            }

            @Override
            public String biomeAtQuart(int quartX, int quartY, int quartZ) {
                return null;
            }

            @Override
            public Set<String> possibleBiomes() {
                return Set.of();
            }
        };

        assertNull(provider.biomeAt(100, 64, 200));
        assertNull(provider.biomeAtQuart(25, 16, 50));
        assertTrue(provider.possibleBiomes().isEmpty());
    }

    // ---- MinecraftChunkGenerator tests (using mock implementation) ----

    @Test
    void minecraftChunkGeneratorMock() {
        MinecraftChunkGenerator generator = new MinecraftChunkGenerator() {
            @Override
            public short[][] generateBiomes(int chunkX, int chunkZ) {
                short[][] result = new short[4][4];
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        result[z][x] = 1; // biome ID
                    }
                }
                return result;
            }

            @Override
            public boolean hasStructureStart(int chunkX, int chunkZ, String structureId) {
                return chunkX == 0 && chunkZ == 0 && structureId.equals("minecraft:village");
            }

            @Override
            public Set<String> structureStarts(int chunkX, int chunkZ) {
                if (chunkX == 0 && chunkZ == 0) {
                    return Set.of("minecraft:village");
                }
                return Set.of();
            }

            @Override
            public int surfaceHeight(int x, int z) {
                return 64;
            }

            @Override
            public int minY() {
                return -64;
            }

            @Override
            public int maxY() {
                return 320;
            }
        };

        short[][] biomes = generator.generateBiomes(5, 10);
        assertEquals(4, biomes.length);
        assertEquals(4, biomes[0].length);
        assertEquals(1, biomes[0][0]);

        assertTrue(generator.hasStructureStart(0, 0, "minecraft:village"));
        assertFalse(generator.hasStructureStart(1, 0, "minecraft:village"));
        assertFalse(generator.hasStructureStart(0, 0, "minecraft:fortress"));

        assertEquals(1, generator.structureStarts(0, 0).size());
        assertTrue(generator.structureStarts(0, 0).contains("minecraft:village"));
        assertEquals(0, generator.structureStarts(5, 10).size());

        assertEquals(64, generator.surfaceHeight(100, 200));
        assertEquals(-64, generator.minY());
        assertEquals(320, generator.maxY());
    }

    @Test
    void minecraftChunkGeneratorHeight() {
        MinecraftChunkGenerator generator = new MinecraftChunkGenerator() {
            @Override
            public short[][] generateBiomes(int chunkX, int chunkZ) {
                return new short[0][0];
            }

            @Override
            public boolean hasStructureStart(int chunkX, int chunkZ, String structureId) {
                return false;
            }

            @Override
            public Set<String> structureStarts(int chunkX, int chunkZ) {
                return Set.of();
            }

            @Override
            public int surfaceHeight(int x, int z) {
                return 64;
            }

            @Override
            public int minY() {
                return -64;
            }

            @Override
            public int maxY() {
                return 320;
            }
        };

        assertEquals(384, generator.height());
    }

    // ---- MinecraftResourceRegistry tests (using mock implementation) ----

    @Test
    void minecraftResourceRegistryMock() {
        MinecraftResourceRegistry registry = new MinecraftResourceRegistry() {
            @Override
            public Optional<Object> lookup(String registryName, String identifier) {
                if ("minecraft:biome".equals(registryName) && "minecraft:plains".equals(identifier)) {
                    return Optional.of("Plains Biome");
                }
                return Optional.empty();
            }

            @Override
            public Map<String, Object> entries(String registryName) {
                if ("minecraft:biome".equals(registryName)) {
                    return Map.of(
                            "minecraft:plains", "Plains Biome",
                            "minecraft:forest", "Forest Biome"
                    );
                }
                return Map.of();
            }

            @Override
            public Set<String> biomeIds() {
                return Set.of("minecraft:plains", "minecraft:forest", "minecraft:desert");
            }

            @Override
            public Set<String> structureIds() {
                return Set.of("minecraft:village", "minecraft:fortress");
            }

            @Override
            public Set<String> dimensionIds() {
                return Set.of("minecraft:overworld", "minecraft:nether", "minecraft:end");
            }
        };

        assertTrue(registry.contains("minecraft:biome", "minecraft:plains"));
        assertFalse(registry.contains("minecraft:biome", "minecraft:invalid"));

        Optional<Object> result = registry.lookup("minecraft:biome", "minecraft:plains");
        assertTrue(result.isPresent());
        assertEquals("Plains Biome", result.get());

        result = registry.lookup("minecraft:biome", "minecraft:invalid");
        assertFalse(result.isPresent());

        Map<String, Object> biomeEntries = registry.entries("minecraft:biome");
        assertEquals(2, biomeEntries.size());
        assertTrue(biomeEntries.containsKey("minecraft:plains"));
        assertTrue(biomeEntries.containsKey("minecraft:forest"));

        assertEquals(3, registry.biomeIds().size());
        assertEquals(2, registry.structureIds().size());
        assertEquals(3, registry.dimensionIds().size());
    }

    @Test
    void minecraftResourceRegistryDefaultContains() {
        MinecraftResourceRegistry registry = new MinecraftResourceRegistry() {
            @Override
            public Optional<Object> lookup(String registryName, String identifier) {
                if ("test:registry".equals(registryName) && "test:item".equals(identifier)) {
                    return Optional.of("Test Item");
                }
                return Optional.empty();
            }

            @Override
            public Map<String, Object> entries(String registryName) {
                return Map.of();
            }

            @Override
            public Set<String> biomeIds() {
                return Set.of();
            }

            @Override
            public Set<String> structureIds() {
                return Set.of();
            }

            @Override
            public Set<String> dimensionIds() {
                return Set.of();
            }
        };

        // Default implementation delegates to lookup()
        assertTrue(registry.contains("test:registry", "test:item"));
        assertFalse(registry.contains("test:registry", "test:missing"));
    }

    @Test
    void minecraftResourceRegistryEmptyResult() {
        MinecraftResourceRegistry registry = new MinecraftResourceRegistry() {
            @Override
            public Optional<Object> lookup(String registryName, String identifier) {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> entries(String registryName) {
                return Map.of();
            }

            @Override
            public Set<String> biomeIds() {
                return Set.of();
            }

            @Override
            public Set<String> structureIds() {
                return Set.of();
            }

            @Override
            public Set<String> dimensionIds() {
                return Set.of();
            }
        };

        assertFalse(registry.contains("any:registry", "any:item"));
        assertTrue(registry.lookup("any:registry", "any:item").isEmpty());
        assertTrue(registry.entries("any:registry").isEmpty());
        assertTrue(registry.biomeIds().isEmpty());
        assertTrue(registry.structureIds().isEmpty());
        assertTrue(registry.dimensionIds().isEmpty());
    }

    // ---- MinecraftServerBuilder tests (using mock implementation) ----

    @Test
    void minecraftServerBuilderMock() throws Exception {
        MinecraftServerBuilder builder = new MinecraftServerBuilder() {
            private long seed = 0L;
            private java.net.Proxy proxy = java.net.Proxy.NO_PROXY;
            private java.nio.file.Path tempDataPackDir = null;
            private Object existingServer = null;

            @Override
            public MinecraftServerBuilder seed(long seed) {
                this.seed = seed;
                return this;
            }

            @Override
            public MinecraftServerBuilder proxy(java.net.Proxy proxy) {
                this.proxy = proxy;
                return this;
            }

            @Override
            public MinecraftServerBuilder tempDataPackDir(java.nio.file.Path path) {
                this.tempDataPackDir = path;
                return this;
            }

            @Override
            public MinecraftServerBuilder existingServer(Object server) {
                this.existingServer = server;
                return this;
            }

            @Override
            public MinecraftServerHolder build() throws Exception {
                return new MockMinecraftServerHolder(seed, existingServer);
            }
        };

        Object existingServer = new Object();
        MinecraftServerHolder holder = builder
                .seed(12345L)
                .proxy(java.net.Proxy.NO_PROXY)
                .tempDataPackDir(java.nio.file.Path.of("/tmp"))
                .existingServer(existingServer)
                .build();

        assertNotNull(holder);
        assertEquals(existingServer, holder.server());
    }

    // ---- MinecraftServerHolder tests (using mock implementation) ----

    @Test
    void minecraftServerHolderMock() {
        Object server = new Object();
        Object registryAccess = new Object();
        Object biomeSource = new Object();
        Object chunkGenerator = new Object();
        Object dimensionType = new Object();
        Object levelStem = new Object();
        Object worldOptions = new Object();
        Object resourceManager = new Object();

        MinecraftServerHolder holder = new MinecraftServerHolder() {
            private boolean closed = false;

            @Override
            public Object server() {
                return server;
            }

            @Override
            public Object registryAccess() {
                return registryAccess;
            }

            @Override
            public Object biomeSource() {
                return biomeSource;
            }

            @Override
            public Object chunkGenerator() {
                return chunkGenerator;
            }

            @Override
            public Object dimensionType() {
                return dimensionType;
            }

            @Override
            public Object levelStem() {
                return levelStem;
            }

            @Override
            public Object worldOptions() {
                return worldOptions;
            }

            @Override
            public Object resourceManager() {
                return resourceManager;
            }

            @Override
            public boolean ownsServer() {
                return true;
            }

            @Override
            public void close() {
                closed = true;
            }

            public boolean isClosed() {
                return closed;
            }
        };

        assertSame(server, holder.server());
        assertSame(registryAccess, holder.registryAccess());
        assertSame(biomeSource, holder.biomeSource());
        assertSame(chunkGenerator, holder.chunkGenerator());
        assertSame(dimensionType, holder.dimensionType());
        assertSame(levelStem, holder.levelStem());
        assertSame(worldOptions, holder.worldOptions());
        assertSame(resourceManager, holder.resourceManager());
        assertTrue(holder.ownsServer());

        holder.close();
        holder.close(); // Safe to call multiple times
    }

    @Test
    void minecraftServerHolderWithNullServer() {
        MinecraftServerHolder holder = new MinecraftServerHolder() {
            @Override
            public Object server() {
                return null; // dummy server
            }

            @Override
            public Object registryAccess() {
                return new Object();
            }

            @Override
            public Object biomeSource() {
                return new Object();
            }

            @Override
            public Object chunkGenerator() {
                return new Object();
            }

            @Override
            public Object dimensionType() {
                return new Object();
            }

            @Override
            public Object levelStem() {
                return new Object();
            }

            @Override
            public Object worldOptions() {
                return new Object();
            }

            @Override
            public Object resourceManager() {
                return new Object();
            }

            @Override
            public boolean ownsServer() {
                return false;
            }

            @Override
            public void close() {
                // No-op
            }
        };

        assertNull(holder.server());
        assertFalse(holder.ownsServer());
    }

    // ---- Helper class for tests ----

    private static class MockMinecraftServerHolder implements MinecraftServerHolder {
        private final long seed;
        private final Object server;
        private boolean closed = false;

        MockMinecraftServerHolder(long seed, Object server) {
            this.seed = seed;
            this.server = server;
        }

        @Override
        public Object server() {
            return server;
        }

        @Override
        public Object registryAccess() {
            return new Object();
        }

        @Override
        public Object biomeSource() {
            return new Object();
        }

        @Override
        public Object chunkGenerator() {
            return new Object();
        }

        @Override
        public Object dimensionType() {
            return new Object();
        }

        @Override
        public Object levelStem() {
            return new Object();
        }

        @Override
        public Object worldOptions() {
            return new Object();
        }

        @Override
        public Object resourceManager() {
            return new Object();
        }

        @Override
        public boolean ownsServer() {
            return server != null;
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }

        long getSeed() {
            return seed;
        }
    }
}