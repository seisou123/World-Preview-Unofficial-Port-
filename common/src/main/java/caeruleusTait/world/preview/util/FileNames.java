package caeruleusTait.world.preview.util;

/**
 * Shared helpers for turning user-influenced text (dimension ids, seeds)
 * into file-name-safe tokens. Dimension ids arrive from datapacks and may
 * contain arbitrary characters, so every fragment that goes into an export
 * file name must pass through {@link #sanitizeFileToken(String)}.
 */
public final class FileNames {

    private FileNames() {
    }

    /**
     * Replaces every character outside {@code [A-Za-z0-9._-]} with an
     * underscore; an empty input yields {@code "unknown"}.
     */
    public static String sanitizeFileToken(String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }
}
