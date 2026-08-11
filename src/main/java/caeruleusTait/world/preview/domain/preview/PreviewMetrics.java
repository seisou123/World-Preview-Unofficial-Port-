package caeruleusTait.world.preview.domain.preview;

/**
 * Metrics about a preview's sampling coverage.
 */
public record PreviewMetrics(
        long totalChunks,
        long sampledChunks,
        long pendingChunks,
        long sampledPoints,
        int activeBatches,
        int threadCount,
        boolean queueRunning
) {
    public PreviewMetrics {
        if (totalChunks < 0 || sampledChunks < 0 || pendingChunks < 0) {
            throw new IllegalArgumentException("chunk counts must not be negative");
        }
        if (sampledChunks > totalChunks) {
            throw new IllegalArgumentException("sampledChunks must not exceed totalChunks");
        }
    }

    /** Returns the sampling coverage ratio (0..1). */
    public double coverage() {
        return totalChunks == 0 ? 1.0 : (double) sampledChunks / totalChunks;
    }

    /** Returns {@code true} if all chunks have been sampled. */
    public boolean isComplete() {
        return sampledChunks >= totalChunks;
    }
}
