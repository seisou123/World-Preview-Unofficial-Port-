package caeruleusTait.world.preview.infra.storage;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/**
 * Handles migration of cache entries from older schema versions to the current version.
 *
 * <p>Replaces the ad-hoc version checking and fallback logic in
 * {@code PreviewStorageCacheManager}.
 *
 * <p>Migrations are registered as functions that transform byte arrays
 * from version N to version N+1. The migration engine chains multiple
 * migrations together when the version gap is greater than 1.
 */
public final class CacheMigration {

    private final CacheSchema targetSchema;
    private final Map<String, List<MigrationStep>> migrations = new HashMap<>();

    /**
     * Creates a migration engine targeting the given schema.
     *
     * @param targetSchema the schema to migrate to
     */
    public CacheMigration(CacheSchema targetSchema) {
        this.targetSchema = Objects.requireNonNull(targetSchema, "targetSchema");
    }

    /**
     * Registers a migration step from version {@code fromVersion} to {@code fromVersion + 1}
     * for a given signature.
     *
     * @param signature the stable signature identifying the cache format
     * @param fromVersion the source version
     * @param migrator the function that transforms data from the old version to the new
     */
    public void registerMigration(String signature, int fromVersion, Function<byte[], byte[]> migrator) {
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(migrator, "migrator");
        if (fromVersion < 0) {
            throw new IllegalArgumentException("fromVersion must be non-negative, got " + fromVersion);
        }
        migrations.computeIfAbsent(signature, k -> new ArrayList<>())
                .add(new MigrationStep(fromVersion, migrator));
    }

    /**
     * Attempts to migrate data from the given source schema to the target schema.
     *
     * @param sourceSchema the schema of the current data
     * @param data the data to migrate
     * @return the migrated data, or empty if migration is not possible
     * @throws IOException if a migration step fails
     */
    public Optional<byte[]> migrate(CacheSchema sourceSchema, byte[] data) throws IOException {
        Objects.requireNonNull(sourceSchema, "sourceSchema");
        Objects.requireNonNull(data, "data");

        // No migration needed
        if (targetSchema.matches(sourceSchema)) {
            return Optional.of(data);
        }

        // Check if migration is possible
        if (!targetSchema.canMigrateFrom(sourceSchema)) {
            return Optional.empty();
        }

        // Get migration chain
        List<MigrationStep> steps = migrations.get(sourceSchema.stableSignature());
        if (steps == null || steps.isEmpty()) {
            return Optional.empty();
        }

        // Sort steps by source version
        List<MigrationStep> sortedSteps = new ArrayList<>(steps);
        sortedSteps.sort(Comparator.comparingInt(s -> s.fromVersion));

        // Apply migrations in sequence
        byte[] current = data;
        int currentVersion = sourceSchema.formatVersion();
        for (MigrationStep step : sortedSteps) {
            if (step.fromVersion == currentVersion && currentVersion < targetSchema.formatVersion()) {
                try {
                    current = step.migrator.apply(current);
                    currentVersion++;
                } catch (Exception e) {
                    throw new IOException("Migration from version " + currentVersion + " failed", e);
                }
            }
        }

        if (currentVersion != targetSchema.formatVersion()) {
            return Optional.empty();
        }

        return Optional.of(current);
    }

    /** Returns the target schema for this migration engine. */
    public CacheSchema targetSchema() {
        return targetSchema;
    }

    // ---- Internal migration step ----

    private record MigrationStep(int fromVersion, Function<byte[], byte[]> migrator) {}
}
