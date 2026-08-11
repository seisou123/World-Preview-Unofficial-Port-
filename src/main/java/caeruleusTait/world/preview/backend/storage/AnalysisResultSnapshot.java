package caeruleusTait.world.preview.backend.storage;

import com.google.gson.JsonElement;

import java.util.List;

public record AnalysisResultSnapshot(
        String taskId,
        List<JsonElement> candidates,
        long updatedAt,
        List<String> errors
) {
    public AnalysisResultSnapshot {
        candidates = candidates == null ? List.of() : candidates.stream()
                .map(element -> element == null ? null : element.deepCopy())
                .toList();
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
