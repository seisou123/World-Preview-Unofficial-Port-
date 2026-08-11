package caeruleusTait.world.preview.infra.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

/**
 * Interface for storage engines that handle read/write/delete/list operations.
 *
 * <p>Abstracts the file I/O operations currently scattered across
 * {@code PreviewStorageCacheManager} and {@code FileAnalysisRepository}.
 */
public interface StorageEngine {

    /**
     * Checks whether a value exists at the given key.
     *
     * @param key the storage key (e.g., a relative file path)
     * @return {@code true} if the key exists
     */
    boolean exists(String key);

    /**
     * Opens an input stream for reading the value at the given key.
     *
     * @param key the storage key
     * @return an input stream, or empty if the key does not exist
     * @throws IOException if an I/O error occurs
     */
    Optional<InputStream> read(String key) throws IOException;

    /**
     * Opens an output stream for writing a value to the given key.
     * The write is not guaranteed to be durable until the stream is closed.
     *
     * @param key the storage key
     * @return an output stream
     * @throws IOException if an I/O error occurs
     */
    OutputStream write(String key) throws IOException;

    /**
     * Deletes the value at the given key.
     *
     * @param key the storage key
     * @return {@code true} if the value was deleted, {@code false} if it did not exist
     * @throws IOException if an I/O error occurs
     */
    boolean delete(String key) throws IOException;

    /**
     * Lists all keys that start with the given prefix.
     *
     * @param prefix the key prefix (use "" for all keys)
     * @return a list of matching keys
     * @throws IOException if an I/O error occurs
     */
    List<String> list(String prefix) throws IOException;

    /**
     * Returns the size of the value at the given key in bytes.
     *
     * @param key the storage key
     * @return the size in bytes, or -1 if the key does not exist
     * @throws IOException if an I/O error occurs
     */
    long size(String key) throws IOException;

    /**
     * Creates a transaction for atomic writes.
     *
     * @return a new storage transaction
     */
    StorageTransaction beginTransaction();
}
