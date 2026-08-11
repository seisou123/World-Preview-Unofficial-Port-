package caeruleusTait.world.preview.backend.storage;

import java.util.List;

public record AnalysisTaskSnapshot(
        String taskId,
        long startSeed,
        long totalCount,
        long step,
        long cursor,
        String status,
        String dimension,
        String contextFingerprint,
        String ruleDigest,
        long updatedAt,
        List<String> errors
) {
    public AnalysisTaskSnapshot(String taskId, long startSeed, long totalCount, long step,
                                long cursor, String status, String dimension,
                                String contextFingerprint, String ruleDigest, long updatedAt) {
        this(taskId, startSeed, totalCount, step, cursor, status, dimension,
                contextFingerprint, ruleDigest, updatedAt, List.of());
    }

    public AnalysisTaskSnapshot {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
