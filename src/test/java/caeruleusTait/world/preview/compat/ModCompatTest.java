package caeruleusTait.world.preview.compat;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ModCompat record validation and builder methods.
 */
class ModCompatTest {

    @Test
    void testValidConstruction() {
        ModCompat compat = new ModCompat("test_mod", "Test Mod", "1.0.0",
                false, true, java.util.List.of(), java.util.Optional.empty());
        assertEquals("test_mod", compat.modId());
        assertEquals("Test Mod", compat.modName());
        assertEquals("1.0.0", compat.version());
        assertFalse(compat.required());
        assertTrue(compat.enabledByDefault());
        assertTrue(compat.adapters().isEmpty());
        assertFalse(compat.configOverride().isPresent());
    }

    @Test
    void testDefaultConstruction() {
        ModCompat compat = new ModCompat("test", "Test", "1.0", false, true);
        assertEquals("test", compat.modId());
        assertTrue(compat.adapters().isEmpty());
        assertFalse(compat.configOverride().isPresent());
    }

    @Test
    void testWithAdapters() {
        ModCompat compat = new ModCompat("test", "Test", "1.0", false, true);
        java.util.List<ChunkGeneratorAdapter.Factory> adapters = java.util.List.of(
                (ctx, c) -> new VanillaChunkGeneratorAdapter());
        ModCompat withAdapters = compat.withAdapters(adapters);
        assertEquals(1, withAdapters.adapters().size());
    }

    @Test
    void testWithConfigOverride() {
        ModCompat compat = new ModCompat("test", "Test", "1.0", false, true);
        java.util.function.Consumer<caeruleusTait.world.preview.WorldPreviewConfig> override = cfg -> {
            // No-op override
        };
        ModCompat withOverride = compat.withConfigOverride(override);
        assertTrue(withOverride.configOverride().isPresent());
    }

    @Test
    void testBlankModIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new ModCompat("", "Test", "1.0", false, true));
    }

    @Test
    void testNullAdaptersDefaultsToEmpty() {
        ModCompat compat = new ModCompat("test", "Test", "1.0",
                false, true, null, java.util.Optional.empty());
        assertTrue(compat.adapters().isEmpty());
    }

    @Test
    void testNullConfigOverrideDefaultsToEmpty() {
        ModCompat compat = new ModCompat("test", "Test", "1.0",
                false, true, java.util.List.of(), null);
        assertFalse(compat.configOverride().isPresent());
    }
}
