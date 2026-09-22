package caeruleusTait.world.preview.compat;

import org.junit.jupiter.api.*;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;

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

    // ---- detectInstalledMods integration ----

    /**
     * The enumeration must read the loader's public {@code getAllMods()} API.
     * A stub loader stands in for Fabric Loader: the real singleton has no mods
     * to report in a plain unit-test JVM.
     */
    @Test
    void detectInstalledModsEnumeratesLoaderMods() {
        net.fabricmc.loader.api.FabricLoader loader = stubLoader(List.of("minecraft", "terralith"));

        java.util.Set<String> detected = ModCompatRegistry.detectInstalledMods(loader);

        assertEquals(java.util.Set.of("minecraft", "terralith"), detected);
    }

    @Test
    void detectInstalledModsIgnoresBlankAndNullIds() {
        net.fabricmc.loader.api.FabricLoader loader = stubLoader(
                java.util.Arrays.asList("minecraft", "", null, "terralith"));

        java.util.Set<String> detected = ModCompatRegistry.detectInstalledMods(loader);

        assertEquals(java.util.Set.of("minecraft", "terralith"), detected);
    }

    /**
     * Detection must feed the registry's enabled check: before this fix the
     * detection returned an empty set, so {@code isModEnabled} was always false.
     */
    @Test
    void detectedModsDriveIsModEnabled() {
        registry.setInstalledMods(ModCompatRegistry.detectInstalledMods(
                stubLoader(List.of("minecraft", "terralith"))));

        assertTrue(registry.isModEnabled("terralith"));
        assertFalse(registry.isModEnabled("biomesoplenty"));
    }

    /** Detection against the real loader must stay exception-free outside Knot. */
    @Test
    void detectInstalledModsIsSafeWithoutKnot() {
        assertNotNull(ModCompatRegistry.detectInstalledMods());
    }

    /** Builds a FabricLoader whose getAllMods() returns containers for the given ids. */
    private static net.fabricmc.loader.api.FabricLoader stubLoader(Collection<String> modIds) {
        ClassLoader cl = ModCompatRegistryTest.class.getClassLoader();
        return (net.fabricmc.loader.api.FabricLoader) Proxy.newProxyInstance(cl,
                new Class<?>[]{net.fabricmc.loader.api.FabricLoader.class},
                (proxy, method, args) -> {
                    if ("getAllMods".equals(method.getName())) {
                        return modIds.stream().map(id -> stubContainer(cl, id)).toList();
                    }
                    return null;
                });
    }

    private static net.fabricmc.loader.api.ModContainer stubContainer(ClassLoader cl, String modId) {
        net.fabricmc.loader.api.metadata.ModMetadata metadata =
                (net.fabricmc.loader.api.metadata.ModMetadata) Proxy.newProxyInstance(cl,
                        new Class<?>[]{net.fabricmc.loader.api.metadata.ModMetadata.class},
                        (proxy, method, args) -> "getId".equals(method.getName()) ? modId : null);
        return (net.fabricmc.loader.api.ModContainer) Proxy.newProxyInstance(cl,
                new Class<?>[]{net.fabricmc.loader.api.ModContainer.class},
                (proxy, method, args) -> "getMetadata".equals(method.getName()) ? metadata : null);
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
