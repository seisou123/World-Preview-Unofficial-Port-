package caeruleusTait.world.preview.backend.analysis;

import caeruleusTait.world.preview.domain.session.SessionFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Identifies the immutable world-generation context shared by analysis tasks.
 */
public record ContextFingerprint(
        String minecraftVersion,
        String loader,
        int formatVersion,
        String registryDigest,
        String worldType,
        String dimension
) {
    public ContextFingerprint {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(registryDigest, "registryDigest");
        Objects.requireNonNull(worldType, "worldType");
        Objects.requireNonNull(dimension, "dimension");
    }

    public String stableKey() {
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

    /**
     * Converts this context fingerprint to a domain-level {@link SessionFingerprint}.
     * The resulting fingerprint has context-only fields populated; sampling
     * parameters are left at their default (neutral) values.
     */
    public SessionFingerprint toSessionFingerprint() {
        return SessionFingerprint.context(
                minecraftVersion, loader, formatVersion,
                registryDigest, worldType, dimension
        );
    }

    /**
     * Converts this context fingerprint to a full {@link SessionFingerprint}
     * including sampling parameters.
     */
    public SessionFingerprint toSessionFingerprint(
            int pixelsPerChunk, int samplerType, int yMin, int yMax,
            boolean includeHeight, boolean includeIntersections, boolean includeNoise
    ) {
        return SessionFingerprint.full(
                minecraftVersion, loader, formatVersion,
                registryDigest, worldType, dimension,
                pixelsPerChunk, samplerType, yMin, yMax,
                includeHeight, includeIntersections, includeNoise
        );
    }
}
