package caeruleusTait.world.preview.infra.minecraft;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the infra/minecraft module.
 */
class MinecraftTest {

    private static final String VILLAGE = "minecraft:village";

    /**
     * Named stand-in for the two anonymous {@link MinecraftChunkGenerator} mocks
     * these tests used to declare inline. Both share surfaceHeight/minY/maxY;
     * the structure response and biome grid differ, so they are constructor flags.
     */
    private static final class StubGenerator implements MinecraftChunkGenerator {

        /** Former mock 1: village at (0, 0) and a 4x4 grid filled with biome id 1. */
        static final StubGenerator VILLAGE_AND_BIOMES = new StubGenerator(true, (short) 1);

        /** Former mock 2: no structures at all and an empty biome grid. */
        static final StubGenerator EMPTY = new StubGenerator(false, (short) -1);

        private final boolean village;
        private final short biomeId;

        private StubGenerator(boolean village, short biomeId) {
            this.village = village;
            this.biomeId = biomeId;
        }

        @Override
        public short[][] generateBiomes(int chunkX, int chunkZ) {
            if (biomeId < 0) {
                return new short[0][0];
            }
            short[][] result = new short[4][4];
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    result[z][x] = biomeId; // biome ID
                }
            }
            return result;
        }

        @Override
        public boolean hasStructureStart(int chunkX, int chunkZ, String structureId) {
            return village && chunkX == 0 && chunkZ == 0 && structureId.equals(VILLAGE);
        }

        @Override
        public Set<String> structureStarts(int chunkX, int chunkZ) {
            if (village && chunkX == 0 && chunkZ == 0) {
                return Set.of(VILLAGE);
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
    }

    @Test
    void minecraftChunkGeneratorMock() {
        MinecraftChunkGenerator generator = StubGenerator.VILLAGE_AND_BIOMES;

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
        MinecraftChunkGenerator generator = StubGenerator.EMPTY;

        assertEquals(384, generator.height());
    }
}
