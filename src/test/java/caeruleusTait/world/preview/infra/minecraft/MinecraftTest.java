package caeruleusTait.world.preview.infra.minecraft;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the infra/minecraft module.
 */
class MinecraftTest {

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
}
