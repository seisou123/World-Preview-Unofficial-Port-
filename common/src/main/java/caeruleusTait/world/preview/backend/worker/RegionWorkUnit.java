package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.analysis.AnalysisRequest;
import caeruleusTait.world.preview.backend.analysis.AnalysisSession;
import caeruleusTait.world.preview.backend.analysis.MetricAggregator;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Samples one region row and publishes the row atomically to storage and metrics. */
public final class RegionWorkUnit {
    private final AnalysisRequest request;
    private final PreviewStorage storage;
    private final MetricAggregator metrics;
    private final AnalysisSession.Sampler sampler;

    public RegionWorkUnit(AnalysisRequest request, PreviewStorage storage,
                          MetricAggregator metrics, AnalysisSession.Sampler sampler) {
        this.request = Objects.requireNonNull(request, "request");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
    }

    public void run(long row, BooleanSupplier cancelled) throws Exception {
        int z = coordinate(request.region().minZ(), row, request.sampleStep(), request.region().maxZ());
        for (int x : coordinates(request.region().minX(), request.region().maxX(), request.sampleStep())) {
            // Cancel only prevents starting new samples; already-taken samples must still be published.
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
            AnalysisSession.Sample sample = sampler.sample(x, z, request.y());
            synchronized (metrics) {
                metrics.addSample(x, z, sample.biome(), sample.height());
            }
            write(storage, x, z, request.y(), PreviewStorage.FLAG_BIOME, sample.biome());
            if (request.includeHeight()) {
                write(storage, x, z, 0, PreviewStorage.FLAG_HEIGHT, sample.height());
            }
            if (request.includeIntersections() && sample.intersection() != null) {
                write(storage, x, z, request.y(), PreviewStorage.FLAG_INTERSECT, sample.intersection());
            }
            if (request.includeNoise() && sample.noise() != null) {
                long[] flags = {
                        PreviewStorage.FLAG_NOISE_TEMPERATURE,
                        PreviewStorage.FLAG_NOISE_HUMIDITY,
                        PreviewStorage.FLAG_NOISE_CONTINENTALNESS,
                        PreviewStorage.FLAG_NOISE_EROSION,
                        PreviewStorage.FLAG_NOISE_DEPTH,
                        PreviewStorage.FLAG_NOISE_WEIRDNESS
                };
                short[] noise = sample.noise();
                for (int i = 0; i < Math.min(flags.length, noise.length); i++) {
                    write(storage, x, z, request.y(), flags[i], noise[i]);
                }
            }
        }
        storage.notifyWrite();
    }

    public static List<Integer> coordinates(int min, int max, int step) {
        if (step < 1) throw new IllegalArgumentException("step must be >= 1");
        List<Integer> coordinates = new ArrayList<>();
        for (long value = min; value <= max; value += step) {
            coordinates.add((int) value);
            if (value > (long) max - step) break;
        }
        if (coordinates.get(coordinates.size() - 1) != max) coordinates.add(max);
        return List.copyOf(coordinates);
    }

    public static long coordinateCount(int min, int max, int step) {
        if (step < 1) throw new IllegalArgumentException("step must be >= 1");
        long distance = (long) max - min;
        return distance / step + (distance % step == 0 ? 1 : 2);
    }

    private static void write(PreviewStorage storage, int x, int z, int y, long flag, short value) {
        if (value == Short.MIN_VALUE) return;
        int qx = QuartPos.fromBlock(x);
        int qy = QuartPos.fromBlock(y);
        int qz = QuartPos.fromBlock(z);
        PreviewSection section = storage.section4(qx, qy, qz, flag);
        section.set(qx - section.quartX(), qz - section.quartZ(), value);
    }

    private static int coordinate(int start, long index, int step, int max) {
        long value = start + index * (long) step;
        return value >= max ? max : (int) value;
    }

    private static int next(int current, int step, int max) {
        long value = current + (long) step;
        return value >= max ? max : (int) value;
    }
}
