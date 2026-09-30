package caeruleusTait.world.preview.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Shared helpers for crash-safe file writes.
 *
 * <p>All persistent data written by this mod (configs, caches, analysis results)
 * goes through a "write sibling temp file, then move over the target" pattern so
 * an interrupted write never corrupts existing data.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * Moves {@code from} onto {@code to}, replacing any existing file.
     * Prefers {@link StandardCopyOption#ATOMIC_MOVE} and falls back to a
     * plain replace when the filesystem does not support it (e.g. some
     * Windows/network filesystems).
     */
    public static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes {@code text} to {@code target} via a sibling {@code .tmp} file and
     * an atomic replace. The temp file is removed on failure.
     */
    public static void writeStringAtomic(Path target, String text) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(temp, text);
            moveReplace(temp, target);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }
}
