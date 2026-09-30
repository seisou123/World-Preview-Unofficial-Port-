package caeruleusTait.world.preview.backend.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for the world identity lineage record. Deliberately Minecraft-free:
 * the identity must stay usable in headless tooling and cache-key computation.
 */
class WorldIdentityTest {

    @Test
    void canonicalFactoryToleratesNullComponents() {
        WorldIdentity id = WorldIdentity.of(123L, null, null, null, null, null);
        assertEquals(123L, id.seed());
        assertEquals("unknown", id.dimension());
        assertEquals("unknown", id.generatorClass());
        assertEquals("none", id.dataPacks());
        assertEquals("none", id.registryDigest());
        assertEquals("n/a", id.compatProfile());
    }

    @Test
    void stableKeyIsDeterministicAcrossInstances() {
        WorldIdentity a = WorldIdentity.of(1L, "minecraft:overworld", "OverworldGenerator", "vanilla", "ab:cd", "auto|auto|");
        WorldIdentity b = WorldIdentity.of(1L, "minecraft:overworld", "OverworldGenerator", "vanilla", "ab:cd", "auto|auto|");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a.stableKey(), b.stableKey());
        assertEquals(a.shortKey(), b.shortKey());
    }

    @Test
    void everyComponentChangeChangesTheKey() {
        WorldIdentity base = WorldIdentity.of(1L, "dim", "gen", "packs", "digest", "compat");
        assertNotEquals(base.stableKey(), WorldIdentity.of(2L, "dim", "gen", "packs", "digest", "compat").stableKey(), "seed must matter");
        assertNotEquals(base.stableKey(), WorldIdentity.of(1L, "dim2", "gen", "packs", "digest", "compat").stableKey(), "dimension must matter");
        assertNotEquals(base.stableKey(), WorldIdentity.of(1L, "dim", "gen2", "packs", "digest", "compat").stableKey(), "generator must matter");
        assertNotEquals(base.stableKey(), WorldIdentity.of(1L, "dim", "gen", "packs2", "digest", "compat").stableKey(), "datapacks must matter");
        assertNotEquals(base.stableKey(), WorldIdentity.of(1L, "dim", "gen", "packs", "digest2", "compat").stableKey(), "registry digest must matter");
        assertNotEquals(base.stableKey(), WorldIdentity.of(1L, "dim", "gen", "packs", "digest", "compat2").stableKey(), "compat profile must matter");
    }

    @Test
    void shortKeyIsFirstTwelveHexCharsOfStableKey() {
        WorldIdentity id = WorldIdentity.of(-42L, "minecraft:the_nether", "gen", "packs", "d", "auto");
        String stable = id.stableKey();
        assertEquals(64, stable.length(), "SHA-256 hex digest length");
        assertEquals(12, id.shortKey().length());
        assertEquals(stable.substring(0, 12), id.shortKey());
    }

    @Test
    void nullCanonicalizationMatchesExplicitStrings() {
        // The canonical constructor normalizes nulls, so the factory with nulls
        // and an explicit construction with the documented defaults are equal.
        assertEquals(
                WorldIdentity.of(9L, null, null, null, null, null),
                new WorldIdentity(9L, "unknown", "unknown", "none", "none", "n/a"));
    }
}
