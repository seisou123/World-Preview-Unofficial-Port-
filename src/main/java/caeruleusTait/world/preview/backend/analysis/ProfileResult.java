package caeruleusTait.world.preview.backend.analysis;

import java.util.List;
import java.util.Objects;

public record ProfileResult(ProfileRequest request, List<ProfilePoint> points,
                            AnalysisDataState state, String unavailableReason) {
    public ProfileResult {
        request = Objects.requireNonNull(request, "request");
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        state = Objects.requireNonNull(state, "state");
        unavailableReason = unavailableReason == null ? "" : unavailableReason;
    }
}
