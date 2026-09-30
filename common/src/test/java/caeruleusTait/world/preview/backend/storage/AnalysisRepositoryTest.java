package caeruleusTait.world.preview.backend.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisRepositoryTest {
    @Test
    void taskJsonRoundTripsAndCorruptFilesAreQuarantined() throws Exception {
        Path root = Files.createTempDirectory("analysis-repository");
        FileAnalysisRepository repository = new FileAnalysisRepository();
        AnalysisTaskSnapshot snapshot = new AnalysisTaskSnapshot("task-1", 10L, 100L, 2L,
                12L, "RUNNING", "dimension", "fingerprint", "rule", 3L);

        repository.writeTask(root, snapshot);
        assertEquals(snapshot, repository.readTask(root, "task-1").orElseThrow());

        Files.writeString(root.resolve("task-task-1.json"), "{broken");
        assertTrue(repository.readTask(root, "task-1").isEmpty());
        try (var files = Files.list(root)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("task-task-1.json.corrupt")));
        }
    }

    @Test
    void previewRoundTripsAndRejectsWrongHeader() throws Exception {
        Path file = Files.createTempFile("preview", ".bin");
        FileAnalysisRepository repository = new FileAnalysisRepository();
        CacheFileHeader header = new CacheFileHeader(1, "stable", 0L);
        PreviewStorage storage = new PreviewStorage(0, 64);

        repository.writePreview(file, header, storage);
        assertTrue(repository.readPreview(file, header, 0, 64).isPresent());
        assertTrue(repository.readPreview(file, new CacheFileHeader(1, "other", 0L), 0, 64).isEmpty());
    }
}
