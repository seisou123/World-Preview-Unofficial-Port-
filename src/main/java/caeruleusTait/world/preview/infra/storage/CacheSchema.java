package caeruleusTait.world.preview.infra.storage;

import java.util.Objects;

/**
 * Describes the schema of a cache entry: format version and stable signature.
 *
 * <p>This is the domain-level equivalent of {@code CacheFileHeader},
 * without the payload length field (which is an I/O concern).
 *
 * <p>Used by {@link CacheMigration} to determine whether a cache entry
 * needs to be migrated or can be used as-is.
 */
public record CacheSchema(int formatVersion, String stableSignature) {

    public CacheSchema {
        if (formatVersion < 0) {
            throw new IllegalArgumentException("formatVersion must be non-negative, got " + formatVersion);
        }
        Objects.requireNonNull(stableSignature, "stableSignature");
        if (stableSignature.isBlank()) {
            throw new IllegalArgumentException("stableSignature must not be blank");
        }
    }

    /**
     * Checks whether this schema is compatible with another.
     * Two schemas are compatible if they have the same format version
     * and the same stable signature.
     *
     * @param other the other schema (may be {@code null})
     * @return {@code true} if the schemas are compatible
     */
    public boolean matches(CacheSchema other) {
        return other != null
                && formatVersion == other.formatVersion
                && stableSignature.equals(other.stableSignature);
    }

    /**
     * Checks whether this schema can be migrated from the given older schema.
     * Migration is possible if the stable signature matches and the other
     * format version is lower.
     *
     * @param older the potentially older schema
     * @return {@code true} if migration from {@code older} to this schema is possible
     */
    public boolean canMigrateFrom(CacheSchema older) {
        return older != null
                && stableSignature.equals(older.stableSignature)
                && older.formatVersion < formatVersion;
    }
}
