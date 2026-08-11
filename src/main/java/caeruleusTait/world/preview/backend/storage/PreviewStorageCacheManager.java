// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.storage;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Preview disk-cache helpers.
 * <p>
 * Uses a zip container with a single {@code bin} entry holding a non-Java-serialization
 * binary payload (see {@link PreviewStorage#writeBinary} / {@link PreviewStorage#readBinary}).
 * CACHE_FORMAT_VERSION 2 rejects legacy Java-serialized caches.
 */
public interface PreviewStorageCacheManager {

    /** Bumped to 2 when Java serialization cache I/O was disabled for safety. */
    int CACHE_FORMAT_VERSION = 2;

    /** Zip entry name for the binary payload. */
    String CACHE_ZIP_ENTRY = "bin";

    PreviewStorage loadPreviewStorage(long seed, int yMin, int yMax);

    void storePreviewStorage(long seed, PreviewStorage storage);

    Path cacheDir();

    default String cacheFileCompatPart() {
        final WorldPreview worldPreview = WorldPreview.get();
        final RenderSettings settings = worldPreview.renderSettings();
        final WorldPreviewConfig cfg = worldPreview.cfg();

        long flags = 0;
        flags |= CACHE_FORMAT_VERSION & 0b1111;
        flags |= (settings.samplerType.ordinal() & 0b1111) << 4;
        flags |= (PreviewSection.SHIFT & 0b1111) << 8;
        flags |= (PreviewBlock.PREVIEW_BLOCK_SHIFT & 0b1111) << 12;
        flags |= cfg.enableCompression ? 1 << 16 : 0;

        return String.format("%s-%d-%d", settings.dimension, settings.pixelsPerChunk(), flags)
                .replace(":", "_")
                .replace(";", "_")
                .replace("/", "_")
                .replace("\\", "_");
    }

    /** Clears only the preview cache represented by this provider. Analysis data lives elsewhere. */
    default void clearCache() {
        Path root = cacheDir();
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear preview cache: " + root, e);
        }
    }

    /**
     * Writes preview storage to a zip file (entry {@value #CACHE_ZIP_ENTRY}) via a temp file
     * and atomic move.
     */
    default void writeCacheFile(PreviewStorage storage, Path outFile) {
        Path parent = outFile.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                WorldPreview.LOGGER.error("Failed to create preview cache directory {}", parent, e);
                return;
            }
        }

        Path tmp = outFile.resolveSibling(outFile.getFileName().toString() + ".tmp");
        try {
            try (OutputStream fos = Files.newOutputStream(tmp);
                 BufferedOutputStream bos = new BufferedOutputStream(fos);
                 ZipOutputStream zos = new ZipOutputStream(bos);
                 DataOutputStream dos = new DataOutputStream(zos)) {
                zos.putNextEntry(new ZipEntry(CACHE_ZIP_ENTRY));
                storage.writeBinary(dos);
                dos.flush();
                zos.closeEntry();
            }
            try {
                Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Windows/some FS may not support ATOMIC_MOVE
                Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING);
            }
            WorldPreview.LOGGER.debug("Wrote preview disk cache {}", outFile);
        } catch (IOException e) {
            WorldPreview.LOGGER.error("Failed to write preview disk cache {}", outFile, e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    /**
     * Reads a zip preview cache. On missing file, bad magic/version, or corrupt payload,
     * returns empty storage (and renames the bad file to {@code .corrupt} when possible).
     */
    default PreviewStorage readCacheFile(int yMin, int yMax, Path inFile) {
        if (!Files.exists(inFile)) {
            return new PreviewStorage(yMin, yMax);
        }

        try (InputStream fis = Files.newInputStream(inFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             ZipInputStream zis = new ZipInputStream(bis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (CACHE_ZIP_ENTRY.equals(entry.getName())) {
                    try (DataInputStream dis = new DataInputStream(zis)) {
                        return PreviewStorage.readBinary(dis, yMin, yMax);
                    }
                }
            }
            throw new IOException("Missing zip entry '" + CACHE_ZIP_ENTRY + "'");
        } catch (IOException e) {
            WorldPreview.LOGGER.warn(
                    "Ignoring corrupt or incompatible preview cache at {} (recompute): {}",
                    inFile, e.toString());
            renameCorrupt(inFile);
            return new PreviewStorage(yMin, yMax);
        }
    }

    private static void renameCorrupt(Path inFile) {
        Path corrupt = inFile.resolveSibling(inFile.getFileName().toString() + ".corrupt");
        try {
            Files.move(inFile, corrupt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            WorldPreview.LOGGER.debug("Could not rename corrupt preview cache to {}", corrupt, e);
        }
    }

}
