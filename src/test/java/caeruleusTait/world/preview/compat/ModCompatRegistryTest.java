package caeruleusTait.world.preview.compat;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ModCompatRegistry singleton and core operations.
 */
class ModCompatRegistryTest {

    private ModCompatRegistry registry;

    @BeforeEach
    void setUp() {
        registry = ModCompatRegistry.getInstance();
        registry.clear();
    }

    @AfterEach
    void tearDown() {
        registry.clear();
    }

    @Test
    void testRegisterAndGet() {
        ModCompat compat = new ModCompat("test_mod", "Test Mod", "1.0.0",
                false, true, java.util.List.of(), java.util.Optional.empty());
        registry.register(compat);
        java.util.Optional<ModCompat> result = registry.getCompat("test_mod");
        assertTrue(result.isPresent());
        assertEquals("test_mod", result.get().modId());
        assertEquals("Test Mod", result.get().modName());
    }

    @Test
    void testGetNonexistent() {
        java.util.Optional<ModCompat> result = registry.getCompat("nonexistent");
        assertFalse(result.isPresent());
    }

    @Test
    void testSetInstalledMods() {
        java.util.Set<String> mods = java.util.Set.of("minecraft", "fabric-api", "test_mod");
        registry.setInstalledMods(mods);
        assertEquals(3, registry.installedMods().size());
        assertTrue(registry.installedMods().contains("test_mod"));
    }

    @Test
    void testIsModEnabled() {
        registry.setInstalledMods(java.util.Set.of("test_mod", "other_mod"));
        registry.setDisabledMods(java.util.Set.of("other_mod"));
        assertTrue(registry.isModEnabled("test_mod"));
        assertFalse(registry.isModEnabled("other_mod"));
        assertFalse(registry.isModEnabled("missing_mod"));
    }

    @Test
    void testSelectAdapterWithNullChunkGenerator() {
        ChunkGeneratorAdapter adapter = registry.selectAdapter(null, null);
        assertNotNull(adapter);
        assertInstanceOf(VanillaChunkGeneratorAdapter.class, adapter);
    }

    @Test
    void testActiveCompatWithNullContext() {
        java.util.List<ModCompat> result = registry.activeCompat(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testClear() {
        ModCompat compat = new ModCompat("test", "Test", "1.0", false, true);
        registry.register(compat);
        registry.setInstalledMods(java.util.Set.of("test"));
        registry.setDisabledMods(java.util.Set.of("other"));
        registry.clear();
        assertTrue(registry.all().isEmpty());
        assertTrue(registry.installedMods().isEmpty());
        assertTrue(registry.disabledMods().isEmpty());
    }

    @Test
    void testAdapterFactoryCreation() {
        // Register a mod with a custom adapter factory
        ModCompat compat = new ModCompat("custom_mod", "Custom Mod", "1.0",
                false, true,
                java.util.List.of((ctx, c) -> new ChunkGeneratorAdapter() {
                    @Override public Class<? extends net.minecraft.world.level.chunk.ChunkGenerator> supportedType() {
                        return net.minecraft.world.level.chunk.ChunkGenerator.class;
                    }
                    @Override public boolean isApplicable(net.minecraft.world.level.chunk.ChunkGenerator chunkGenerator) {
                        return chunkGenerator != null;
                    }
                    @Override public int minY(net.minecraft.world.level.dimension.LevelStem levelStem) { return 0; }
                    @Override public int maxY(net.minecraft.world.level.dimension.LevelStem levelStem) { return 256; }
                    @Override public short[][] generateBiomes(caeruleusTait.world.preview.backend.analysis.WorldgenContext ctx, int chunkX, int chunkZ) { return new short[0][0]; }
                    @Override public java.util.Set<String> structureStarts(caeruleusTait.world.preview.backend.analysis.WorldgenContext ctx, int chunkX, int chunkZ) { return java.util.Set.of(); }
                    @Override public int surfaceHeight(caeruleusTait.world.preview.backend.analysis.WorldgenContext ctx, int x, int z) { return 0; }
                }),
                java.util.Optional.empty());

        registry.register(compat);
        registry.setInstalledMods(java.util.Set.of("custom_mod"));

        // Verify the adapter factory can create adapters
        ChunkGeneratorAdapter.Factory factory = compat.adapters().get(0);
        ChunkGeneratorAdapter adapter = factory.create(null, compat);
        assertNotNull(adapter);
        assertFalse(adapter instanceof VanillaChunkGeneratorAdapter);
    }
}
