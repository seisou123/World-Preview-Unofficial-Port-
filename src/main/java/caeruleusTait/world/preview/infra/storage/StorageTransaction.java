package caeruleusTait.world.preview.infra.storage;

import java.io.IOException;

/**
 * Interface for atomic storage transactions.
 *
 * <p>Ensures that a group of writes are either all committed or all rolled back.
 * This replaces the ad-hoc temp-file + atomic-move pattern in
 * {@code FileAnalysisRepository}.
 *
 * <p>Usage:
 * <pre>{@code
 * try (StorageTransaction txn = engine.beginTransaction()) {
 *     txn.write("key1", data1);
 *     txn.write("key2", data2);
 *     txn.commit();
 * }
 * }</pre>
 */
public interface StorageTransaction extends AutoCloseable {

    /**
     * Writes data to the given key within this transaction.
     * The data is not visible to other readers until {@link #commit()} is called.
     *
     * @param key the storage key
     * @param data the data to write
     * @throws IOException if an I/O error occurs
     */
    void write(String key, byte[] data) throws IOException;

    /**
     * Deletes the value at the given key within this transaction.
     * The deletion is not visible until {@link #commit()} is called.
     *
     * @param key the storage key
     * @throws IOException if an I/O error occurs
     */
    void delete(String key) throws IOException;

    /**
     * Commits all writes in this transaction, making them visible atomically.
     *
     * @throws IOException if the commit fails
     */
    void commit() throws IOException;

    /**
     * Rolls back all writes in this transaction.
     * After rollback, no writes from this transaction are visible.
     *
     * @throws IOException if the rollback fails
     */
    void rollback() throws IOException;

    /**
     * Returns {@code true} if this transaction has been committed or rolled back.
     */
    boolean isClosed();

    /**
     * If not yet committed, rolls back the transaction.
     * This is a safety net for try-with-resources usage.
     */
    @Override
    void close() throws IOException;
}
