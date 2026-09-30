package caeruleusTait.world.preview.backend.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ContextFingerprintIntegrationTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void datapackAndRegistryDigestChangesInvalidateStableKey() {
        ContextFingerprint base = fingerprint("datapack-a|registry-a");
        ContextFingerprint changedDatapack = fingerprint("datapack-b|registry-a");
        ContextFingerprint changedRegistry = fingerprint("datapack-a|registry-b");

        assertNotEquals(base.stableKey(), changedDatapack.stableKey());
        assertNotEquals(base.stableKey(), changedRegistry.stableKey());
    }

    private static ContextFingerprint fingerprint(String digest) {
        return new ContextFingerprint("1.21.11", "fabric", 1, digest, "default", DIMENSION);
    }
}
