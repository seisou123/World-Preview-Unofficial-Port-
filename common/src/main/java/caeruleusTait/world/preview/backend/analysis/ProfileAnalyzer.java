package caeruleusTait.world.preview.backend.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProfileAnalyzer {
    public record Sample(short biome, short height) {
        public boolean present() {
            return biome != Short.MIN_VALUE && height != Short.MIN_VALUE;
        }
    }

    @FunctionalInterface
    public interface SampleReader {
        Sample read(int x, int z, int y);
    }

    private static final long MAX_POINTS = 100_000L;
    private final SampleReader reader;

    public ProfileAnalyzer(SampleReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public ProfileResult analyze(ProfileRequest request) {
        Objects.requireNonNull(request, "request");
        validate(request);
        List<ProfilePoint> points = new ArrayList<>();
        if (request.vertical()) {
            for (long y = request.yMin(); ; y = next(y, request.step(), request.yMax())) {
                add(points, request.x1(), request.z1(), Math.toIntExact(y));
                if (y == request.yMax()) break;
            }
        } else {
            long dx = (long) request.x2() - request.x1();
            long dz = (long) request.z2() - request.z1();
            long distance = Math.max(Math.abs(dx), Math.abs(dz));
            if (distance == 0) {
                add(points, request.x1(), request.z1(), request.yMin());
            } else {
                for (long offset = 0; offset < distance; offset += request.step()) {
                    int x = interpolate(request.x1(), dx, offset, distance);
                    int z = interpolate(request.z1(), dz, offset, distance);
                    add(points, x, z, request.yMin());
                }
                add(points, request.x2(), request.z2(), request.yMin());
            }
        }
        boolean allPresent = points.stream().allMatch(point -> point.state() == AnalysisDataState.SAMPLED);
        return new ProfileResult(request, points,
                allPresent ? AnalysisDataState.SAMPLED : AnalysisDataState.PENDING, "");
    }

    private void add(List<ProfilePoint> points, int x, int z, int y) {
        Sample sample = Objects.requireNonNull(reader.read(x, z, y), "reader returned null");
        AnalysisDataState state = sample.present() ? AnalysisDataState.SAMPLED : AnalysisDataState.PENDING;
        points.add(new ProfilePoint(x, y, z, sample.biome(), sample.height(), state));
    }

    private static void validate(ProfileRequest request) {
        if (request.step() < 1) throw new IllegalArgumentException("step must be >= 1");
        if (request.vertical() && request.yMax() < request.yMin()) {
            throw new IllegalArgumentException("vertical range must be non-reversed");
        }
        long count;
        if (request.vertical()) {
            long range = (long) request.yMax() - request.yMin();
            count = (range + request.step() - 1L) / request.step() + 1L;
        } else {
            long dx = (long) request.x2() - request.x1();
            long dz = (long) request.z2() - request.z1();
            long distance = Math.max(Math.abs(dx), Math.abs(dz));
            count = distance == 0 ? 1L : (distance - 1L) / request.step() + 2L;
        }
        if (count > MAX_POINTS) throw new IllegalArgumentException("profile exceeds point limit");
    }

    private static long next(long current, int step, int max) {
        long candidate = current + step;
        return candidate >= max ? max : candidate;
    }

    private static int interpolate(int start, long delta, long offset, long distance) {
        return Math.toIntExact(Math.round(start + (double) delta * offset / distance));
    }
}
