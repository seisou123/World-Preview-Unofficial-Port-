package caeruleusTait.world.preview.backend.analysis;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisCacheSignatureTest {
    private static final AnalysisCacheSignature BASE = new AnalysisCacheSignature(
            "1.21.11", "fabric", "abc", "minecraft:overworld", 4, 1, 0, 256,
            true, true, false, 1
    );

    @Test
    void stableKeyChangesWhenAnySignatureFieldChanges() {
        AnalysisCacheSignature[] changed = {
                new AnalysisCacheSignature("1.21.12", "fabric", "abc", "minecraft:overworld", 4, 1, 0, 256, true, true, false, 1),
                new AnalysisCacheSignature("1.21.11", "forge", "abc", "minecraft:overworld", 4, 1, 0, 256, true, true, false, 1),
                new AnalysisCacheSignature("1.21.11", "fabric", "def", "minecraft:overworld", 4, 1, 0, 256, true, true, false, 1),
                new AnalysisCacheSignature("1.21.11", "fabric", "abc", "minecraft:the_nether", 4, 1, 0, 256, true, true, false, 1),
                new AnalysisCacheSignature("1.21.11", "fabric", "abc", "minecraft:overworld", 8, 1, 0, 256, true, true, false, 1),
                new AnalysisCacheSignature("1.21.11", "fabric", "abc", "minecraft:overworld", 4, 2, 0, 256, true, true, false, 1),
                new AnalysisCacheSignature("1.21.11", "fabric", "abc", "minecraft:overworld", 4, 1, -1, 256, true, true, false, 1),
                new AnalysisCacheSignature("1.21.11", "fabric", "abc", "minecraft:overworld", 4, 1, 0, 257, true, true, false, 1),
                new AnalysisCacheSignature("1.21.11", "fabric", "abc", "minecraft:overworld", 4, 1, 0, 256, false, true, false, 1),
                new AnalysisCacheSignature("1.21.11", "fabric", "abc", "minecraft:overworld", 4, 1, 0, 256, true, false, false, 1),
                new AnalysisCacheSignature("1.21.11", "fabric", "abc", "minecraft:overworld", 4, 1, 0, 256, true, true, true, 1),
                new AnalysisCacheSignature("1.21.11", "fabric", "abc", "minecraft:overworld", 4, 1, 0, 256, true, true, false, 2)
        };
        for (AnalysisCacheSignature signature : changed) {
            assertNotEquals(BASE.stableKey(), signature.stableKey());
        }
    }

    @Test
    void stableKeyIsStableAndUsesExactCanonicalSha256() {
        assertEquals(BASE.stableKey(), BASE.stableKey());
        assertEquals("307a0d94e58b77b5485f3aad4da12bd68c111b1936da868a4e1c18a33025ebd5", BASE.stableKey());
    }

    @Test
    void rejectsNullSignatureStrings() {
        assertThrows(NullPointerException.class, () -> new AnalysisCacheSignature(null, "fabric", "abc", "minecraft:overworld", 4, 1, 0, 256, true, true, false, 1));
        assertThrows(NullPointerException.class, () -> new AnalysisCacheSignature("1.21.11", null, "abc", "minecraft:overworld", 4, 1, 0, 256, true, true, false, 1));
        assertThrows(NullPointerException.class, () -> new AnalysisCacheSignature("1.21.11", "fabric", null, "minecraft:overworld", 4, 1, 0, 256, true, true, false, 1));
        assertThrows(NullPointerException.class, () -> new AnalysisCacheSignature("1.21.11", "fabric", "abc", null, 4, 1, 0, 256, true, true, false, 1));
    }

    @Test
    void contextFingerprintFieldsAffectStableKeyAndKeyIsStable() {
        ContextFingerprint base = new ContextFingerprint("1.21.11", "fabric", 1, "abc", "default", "minecraft:overworld");
        ContextFingerprint[] changed = {
                new ContextFingerprint("1.21.12", "fabric", 1, "abc", "default", "minecraft:overworld"),
                new ContextFingerprint("1.21.11", "forge", 1, "abc", "default", "minecraft:overworld"),
                new ContextFingerprint("1.21.11", "fabric", 2, "abc", "default", "minecraft:overworld"),
                new ContextFingerprint("1.21.11", "fabric", 1, "def", "default", "minecraft:overworld"),
                new ContextFingerprint("1.21.11", "fabric", 1, "abc", "large_biomes", "minecraft:overworld"),
                new ContextFingerprint("1.21.11", "fabric", 1, "abc", "default", "minecraft:the_nether")
        };
        for (ContextFingerprint fingerprint : changed) {
            assertNotEquals(base.stableKey(), fingerprint.stableKey());
        }
        assertEquals(base.stableKey(), base.stableKey());
        assertNotNull(base.stableKey());
        assertTrue(!base.stableKey().isEmpty());
    }

    @Test
    void contextFingerprintRejectsNullsAndSupportsGsonRoundTrip() {
        assertThrows(NullPointerException.class, () -> new ContextFingerprint(null, "fabric", 1, "abc", "default", "minecraft:overworld"));
        assertThrows(NullPointerException.class, () -> new ContextFingerprint("1.21.11", null, 1, "abc", "default", "minecraft:overworld"));
        assertThrows(NullPointerException.class, () -> new ContextFingerprint("1.21.11", "fabric", 1, null, "default", "minecraft:overworld"));
        assertThrows(NullPointerException.class, () -> new ContextFingerprint("1.21.11", "fabric", 1, "abc", null, "minecraft:overworld"));
        assertThrows(NullPointerException.class, () -> new ContextFingerprint("1.21.11", "fabric", 1, "abc", "default", null));

        ContextFingerprint original = new ContextFingerprint("1.21.11", "fabric", 1, "abc", "default", "minecraft:overworld");
        ContextFingerprint restored = new Gson().fromJson(new Gson().toJson(original), ContextFingerprint.class);
        assertEquals(original, restored);
        assertEquals(original.stableKey(), restored.stableKey());
    }
}
