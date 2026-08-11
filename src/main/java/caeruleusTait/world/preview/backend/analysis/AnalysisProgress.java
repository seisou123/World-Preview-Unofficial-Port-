package caeruleusTait.world.preview.backend.analysis;

public record AnalysisProgress(
        AnalysisStatus status,
        long completedUnits,
        long totalUnits,
        long sampledPoints,
        long pendingPoints,
        String stage,
        String error
) {
}
