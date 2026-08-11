package caeruleusTait.world.preview.compat;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChunkGeneratorAdapter interface and VanillaChunkGeneratorAdapter.
 */
class ChunkGeneratorAdapterTest {

    @Test
    void testVanillaAdapterIsApplicable() {
        ChunkGeneratorAdapter adapter = VanillaChunkGeneratorAdapter.FACTORY.create(null, null);
        // Verify the adapter is created
        assertNotNull(adapter);
        assertEquals(net.minecraft.world.level.chunk.ChunkGenerator.class,
                adapter.supportedType());
    }

    @Test
    void testVanillaAdapterSupportsHeightmap() {
        ChunkGeneratorAdapter adapter = VanillaChunkGeneratorAdapter.FACTORY.create(null, null);
        assertTrue(adapter.supportsHeightmap());
        assertTrue(adapter.supportsStructures());
    }

    @Test
    void testVanillaAdapterIsApplicableToNull() {
        ChunkGeneratorAdapter adapter = VanillaChunkGeneratorAdapter.FACTORY.create(null, null);
        assertFalse(adapter.isApplicable(null));
    }

    @Test
    void testFactoryCreateReturnsNewInstance() {
        ChunkGeneratorAdapter adapter1 = VanillaChunkGeneratorAdapter.FACTORY.create(null, null);
        ChunkGeneratorAdapter adapter2 = VanillaChunkGeneratorAdapter.FACTORY.create(null, null);
        // Each factory call should create a new adapter instance
        assertNotSame(adapter1, adapter2);
    }
}
