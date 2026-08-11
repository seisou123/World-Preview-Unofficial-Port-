package caeruleusTait.world.preview.infra.storage;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-based implementation of {@link StorageEngine}.
 *
 * <p>Maps storage keys to file paths under a root directory.
 * Keys use '/' as a path separator and are resolved relative to the root.
 *
 * <p>Replaces the duplicated file I/O logic across
 * {@code PreviewStorageCacheManager} and {@code FileAnalysisRepository}.
 */
public final class FileStorageEngine implements StorageEngine {

    private final Path root;

    /**
     * Creates a file storage engine rooted at the given directory.
     *
     * @param root the root directory for all storage operations
     */
    public FileStorageEngine(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** Returns the root directory. */
    public Path root() {
        return root;
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolveKey(key));
    }

    @Override
    public Optional<InputStream> read(String key) throws IOException {
        Path path = resolveKey(key);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(new BufferedInputStream(Files.newInputStream(path)));
    }

    @Override
    public OutputStream write(String key) throws IOException {
        Path path = resolveKey(key);
        Files.createDirectories(path.getParent());
        return new BufferedOutputStream(Files.newOutputStream(path));
    }

    @Override
    public boolean delete(String key) throws IOException {
        Path path = resolveKey(key);
        return Files.deleteIfExists(path);
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        Path searchPath = resolveKey(prefix);
        List<String> keys = new ArrayList<>();
        if (Files.isDirectory(searchPath)) {
            try (Stream<Path> stream = Files.walk(searchPath)) {
                stream.filter(Files::isRegularFile)
                        .forEach(path -> keys.add(root.relativize(path).toString().replace('\\', '/')));
            }
        } else if (Files.isRegularFile(searchPath)) {
            keys.add(prefix);
        } else {
            // Check if prefix matches files with this prefix
            Path parent = searchPath.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                String prefixStr = searchPath.getFileName().toString();
                try (Stream<Path> stream = Files.list(parent)) {
                    stream.filter(p -> p.getFileName().toString().startsWith(prefixStr))
                            .filter(Files::isRegularFile)
                            .forEach(path -> keys.add(root.relativize(path).toString().replace('\\', '/')));
                }
            }
        }
        return keys;
    }

    @Override
    public long size(String key) throws IOException {
        Path path = resolveKey(key);
        if (!Files.exists(path)) return -1;
        return Files.size(path);
    }

    @Override
    public StorageTransaction beginTransaction() {
        return new FileTransaction(this);
    }

    /**
     * Resolves a storage key to a file path under the root directory.
     * Validates that the resolved path is within the root to prevent
     * path traversal attacks.
     */
    Path resolveKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal detected: " + key);
        }
        return resolved;
    }

    // ---- File-based transaction implementation ----

    private static final class FileTransaction implements StorageTransaction {
        private final FileStorageEngine engine;
        private final List<WriteOp> writeOps = new ArrayList<>();
        private final List<String> deleteKeys = new ArrayList<>();
        private boolean committed = false;
        private boolean rolledBack = false;

        /** Represents a pending write operation in the transaction. */
        private record WriteOp(String key, Path tempFile) {}

        FileTransaction(FileStorageEngine engine) {
            this.engine = engine;
        }

        @Override
        public void write(String key, byte[] data) throws IOException {
            if (isClosed()) {
                throw new IllegalStateException("Transaction is already closed");
            }
            Path target = engine.resolveKey(key);
            Path temp = target.resolveSibling(target.getFileName() + ".txn-" + Thread.currentThread().getId()
                    + "-" + System.nanoTime());
            Files.createDirectories(target.getParent());
            Files.write(temp, data);
            writeOps.add(new WriteOp(key, temp));
        }

        @Override
        public void delete(String key) throws IOException {
            if (isClosed()) {
                throw new IllegalStateException("Transaction is already closed");
            }
            // Defer deletion to commit time
            deleteKeys.add(key);
        }

        @Override
        public void commit() throws IOException {
            if (isClosed()) {
                throw new IllegalStateException("Transaction is already closed");
            }
            try {
                // Move all temp files to their final destinations atomically
                for (WriteOp op : writeOps) {
                    Path target = engine.resolveKey(op.key());
                    Files.move(op.tempFile(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                // Handle deletions
                for (String key : deleteKeys) {
                    engine.delete(key);
                }
                committed = true;
            } catch (IOException e) {
                rollback();
                throw e;
            }
        }

        @Override
        public void rollback() throws IOException {
            if (rolledBack || committed) return;
            for (WriteOp op : writeOps) {
                Files.deleteIfExists(op.tempFile());
            }
            writeOps.clear();
            deleteKeys.clear();
            rolledBack = true;
        }

        @Override
        public boolean isClosed() {
            return committed || rolledBack;
        }

        @Override
        public void close() throws IOException {
            if (!isClosed()) {
                rollback();
            }
        }
    }
}
