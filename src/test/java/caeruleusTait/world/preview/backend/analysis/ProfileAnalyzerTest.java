package caeruleusTait.world.preview.backend.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileAnalyzerTest {
    @Test
    void horizontalProfileIncludesEndpointsAndDoesNotInterpolateMissingData() {
        ProfileRequest request = new ProfileRequest(0, 0, 10, 0, 0, 0, 4, false);
        ProfileAnalyzer analyzer = new ProfileAnalyzer((x, z, y) ->
                new ProfileAnalyzer.Sample((short) (x == 4 ? Short.MIN_VALUE : 7),
                        (short) (x == 4 ? Short.MIN_VALUE : 20)));

        ProfileResult result = analyzer.analyze(request);

        assertEquals(List.of(0, 4, 8, 10), result.points().stream().map(ProfilePoint::x).toList());
        assertEquals(List.of(0, 0, 0, 0), result.points().stream().map(ProfilePoint::z).toList());
        assertEquals(Short.MIN_VALUE, result.points().get(1).biome());
        assertEquals(Short.MIN_VALUE, result.points().get(1).height());
        assertEquals(AnalysisDataState.PENDING, result.state());
    }

    @Test
    void verticalProfileSamplesInclusiveRange() {
        ProfileRequest request = new ProfileRequest(3, 4, 3, 4, 0, 16, 8, true);
        ProfileAnalyzer analyzer = new ProfileAnalyzer((x, z, y) ->
                new ProfileAnalyzer.Sample((short) 9, (short) y));

        ProfileResult result = analyzer.analyze(request);

        assertEquals(List.of(0, 8, 16), result.points().stream().map(ProfilePoint::y).toList());
        assertEquals(AnalysisDataState.SAMPLED, result.state());
    }

    @Test
    void validatesStepRangesAndPointLimit() {
        ProfileAnalyzer analyzer = new ProfileAnalyzer((x, z, y) ->
                new ProfileAnalyzer.Sample((short) 1, (short) 1));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                new ProfileRequest(0, 0, 1, 1, 0, 0, 0, false)));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                new ProfileRequest(0, 0, 0, 0, 10, 0, 1, true)));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                new ProfileRequest(0, 0, 0, 0, 0, 199_999, 2, true)));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                new ProfileRequest(0, 0, 200_000, 0, 0, 0, 1, false)));
    }
}
