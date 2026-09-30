package caeruleusTait.world.preview.backend.storage;

import java.util.Objects;

public record CacheFileHeader(int formatVersion, String stableSignature, long payloadLength) {
    public CacheFileHeader {
        if (formatVersion < 0) throw new IllegalArgumentException("formatVersion must be non-negative");
        Objects.requireNonNull(stableSignature, "stableSignature");
        if (payloadLength < 0) throw new IllegalArgumentException("payloadLength must be non-negative");
    }

    public boolean matches(CacheFileHeader actual) {
        return actual != null && formatVersion == actual.formatVersion
                && stableSignature.equals(actual.stableSignature)
                && (payloadLength == 0 || payloadLength == actual.payloadLength);
    }
}
