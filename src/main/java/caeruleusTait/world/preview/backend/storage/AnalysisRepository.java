package caeruleusTait.world.preview.backend.storage;

import java.nio.file.Path;
import java.util.Optional;

public interface AnalysisRepository {
    Optional<AnalysisTaskSnapshot> readTask(Path root, String taskId);
    void writeTask(Path root, AnalysisTaskSnapshot snapshot);
    Optional<AnalysisResultSnapshot> readResult(Path root, String taskId);
    void writeResult(Path root, AnalysisResultSnapshot snapshot);
    Optional<PreviewStorage> readPreview(Path file, CacheFileHeader expected, int yMin, int yMax);
    void writePreview(Path file, CacheFileHeader header, PreviewStorage storage);
    void removeTask(Path root, String taskId);
}
