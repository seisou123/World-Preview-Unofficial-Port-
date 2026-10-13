package caeruleusTait.world.preview.backend.analysis;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Immutable identity of the world generation configuration that preview data,
 * search hits, analysis results and cache files belong to.
 *
 * <p>Two contexts with the same {@link WorldIdentity} are guaranteed to produce
 * interchangeable worldgen results: same seed, same dimension, same generator
 * implementation, same datapack configuration, same biome/structure registry
 * layout (so stored biome/structure ids decode identically) and same
 * compatibility configuration.</p>
 *
 * <p>Consumers MUST compare identities (via {@link #shortKey()}) before
 * reusing or publishing any worldgen-derived result. When the identity does
 * not match, the result is stale and must be discarded instead of being
 * written into the new world — this prevents cross-seed contamination when
 * the player changes the seed, generator, datapacks or compat settings.</p>
 */
public record WorldIdentity(
        long seed,
        String dimension,
        String generatorClass,
        String dataPacks,
        String registryDigest,
        String compatProfile) {

    public WorldIdentity {
        dimension = dimension == null ? "unknown" : dimension;
        generatorClass = generatorClass == null ? "unknown" : generatorClass;
        dataPacks = dataPacks == null ? "none" : dataPacks;
        registryDigest = registryDigest == null ? "none" : registryDigest;
        compatProfile = compatProfile == null ? "n/a" : compatProfile;
    }

    /**
     * Canonical factory used by {@link WorldgenContext#identity()}. Kept
     * dependency-free (no Minecraft types) so it stays unit-testable.
     */
    public static WorldIdentity of(long seed, @Nullable String dimension, @Nullable String generatorClass,
                                   @Nullable String dataPacks, @Nullable String registryDigest,
                                   @Nullable String compatProfile) {
        return new WorldIdentity(seed, dimension, generatorClass, dataPacks, registryDigest, compatProfile);
    }

    /**
     * SHA-256 over every component. Stable across JVM runs; suitable as a
     * cache-file key component.
     */
    public String stableKey() {
        String canonical = seed
                + "|" + dimension
                + "|" + generatorClass
                + "|" + dataPacks
                + "|" + registryDigest
                + "|" + compatProfile;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every supported JVM; never expected.
            return Integer.toHexString(canonical.hashCode());
        }
    }

    /**
     * Short form of {@link #stableKey()} (first 12 hex chars). Used in cache
     * file names and result lineage markers where the full digest is noise.
     */
    public String shortKey() {
        return stableKey().substring(0, 12);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorldIdentity that)) return false;
        return seed == that.seed
                && dimension.equals(that.dimension)
                && generatorClass.equals(that.generatorClass)
                && dataPacks.equals(that.dataPacks)
                && registryDigest.equals(that.registryDigest)
                && compatProfile.equals(that.compatProfile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seed, dimension, generatorClass, dataPacks, registryDigest, compatProfile);
    }
}
