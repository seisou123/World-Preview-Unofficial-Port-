package caeruleusTait.world.preview.compat;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ModCompat record validation and builder methods.
 */
class ModCompatTest {

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
