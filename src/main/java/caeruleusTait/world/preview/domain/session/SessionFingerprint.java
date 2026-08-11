package caeruleusTait.world.preview.domain.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable context fingerprint for a session — a digest that uniquely
 * identifies the world-generation context (MC version, loader, registry
 * digest, world type, dimension, etc.).
 *
 * <p>This replaces the per-system fingerprint logic previously duplicated
 * in {@code ContextFingerprint} and {@code AnalysisCacheSignature}.
 */
public record SessionFingerprint(
        String minecraftVersion,
        String loader,
        int formatVersion,
        String registryDigest,
        String worldType,
        String dimension,
        int pixelsPerChunk,
        int samplerType,
        int yMin,
        int yMax,
        boolean includeHeight,
        boolean includeIntersections,
        boolean includeNoise
) {

    /**
     * Compact constructor for the full fingerprint.
     * Only the core context fields are required; sampling fields default to neutral values.
     */
    public SessionFingerprint {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(registryDigest, "registryDigest");
        Objects.requireNonNull(worldType, "worldType");
        Objects.requireNonNull(dimension, "dimension");
    }

    /**
     * Creates a context-only fingerprint (without sampling parameters).
     * Useful for session recovery where only the world-gen context matters.
     */
    public static SessionFingerprint context(
            String minecraftVersion, String loader, int formatVersion,
            String registryDigest, String worldType, String dimension
    ) {
        return new SessionFingerprint(
                minecraftVersion, loader, formatVersion,
                registryDigest, worldType, dimension,
                0, 0, 0, 0, false, false, false
        );
    }

    /**
     * Creates a full fingerprint including sampling parameters.
     * Useful for analysis cache signatures.
     */
    public static SessionFingerprint full(
            String minecraftVersion, String loader, int formatVersion,
            String registryDigest, String worldType, String dimension,
            int pixelsPerChunk, int samplerType, int yMin, int yMax,
            boolean includeHeight, boolean includeIntersections, boolean includeNoise
    ) {
        return new SessionFingerprint(
                minecraftVersion, loader, formatVersion,
                registryDigest, worldType, dimension,
                pixelsPerChunk, samplerType, yMin, yMax,
                includeHeight, includeIntersections, includeNoise
        );
    }

    /** Returns the SHA-256 stable key for this fingerprint. */
    public String stableKey() {
        String canonical = String.join("|",
                minecraftVersion,
                loader,
                Integer.toString(formatVersion),
                registryDigest,
                worldType,
                dimension,
                Integer.toString(pixelsPerChunk),
                Integer.toString(samplerType),
                Integer.toString(yMin),
                Integer.toString(yMax),
                Boolean.toString(includeHeight),
                Boolean.toString(includeIntersections),
                Boolean.toString(includeNoise));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Returns a context-only stable key (ignoring sampling parameters). */
    public String contextStableKey() {
        String canonical = String.join("|",
                minecraftVersion,
                loader,
                Integer.toString(formatVersion),
                registryDigest,
                worldType,
                dimension);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Returns {@code true} if the context portion (excluding sampling params) matches. */
    public boolean contextMatches(SessionFingerprint other) {
        if (other == null) return false;
        return minecraftVersion.equals(other.minecraftVersion)
                && loader.equals(other.loader)
                && formatVersion == other.formatVersion
                && registryDigest.equals(other.registryDigest)
                && worldType.equals(other.worldType)
                && dimension.equals(other.dimension);
    }
}
