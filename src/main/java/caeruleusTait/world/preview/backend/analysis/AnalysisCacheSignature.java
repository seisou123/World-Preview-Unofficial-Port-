package caeruleusTait.world.preview.backend.analysis;

import caeruleusTait.world.preview.domain.session.SessionFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record AnalysisCacheSignature(
        String minecraftVersion,
        String loader,
        String registryDigest,
        String dimension,
        int pixelsPerChunk,
        int samplerType,
        int yMin,
        int yMax,
        boolean includeHeight,
        boolean includeIntersections,
        boolean includeNoise,
        int formatVersion
) {
    public AnalysisCacheSignature {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(registryDigest, "registryDigest");
        Objects.requireNonNull(dimension, "dimension");
    }

    public String stableKey() {
        String canonical = String.join("|",
                minecraftVersion,
                loader,
                registryDigest,
                dimension,
                Integer.toString(pixelsPerChunk),
                Integer.toString(samplerType),
                Integer.toString(yMin),
                Integer.toString(yMax),
                Boolean.toString(includeHeight),
                Boolean.toString(includeIntersections),
                Boolean.toString(includeNoise),
                Integer.toString(formatVersion));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Converts this cache signature to a domain-level {@link SessionFingerprint}.
     */
    public SessionFingerprint toSessionFingerprint() {
        return SessionFingerprint.full(
                minecraftVersion, loader, formatVersion,
                registryDigest, "default", dimension,
                pixelsPerChunk, samplerType, yMin, yMax,
                includeHeight, includeIntersections, includeNoise
        );
    }
}
