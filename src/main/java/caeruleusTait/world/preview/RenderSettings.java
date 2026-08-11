// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview;

import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.sampler.FullQuartSampler;
import caeruleusTait.world.preview.backend.sampler.QuarterQuartSampler;
import caeruleusTait.world.preview.backend.sampler.SingleQuartSampler;
import caeruleusTait.world.preview.domain.preview.accuracy.ScaleSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.IntFunction;

import static caeruleusTait.world.preview.backend.WorkManager.Y_BLOCK_STRIDE;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_BIOME;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_HEIGHT;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_INTERSECT;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_NOISE;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_NOISE_CONTINENTALNESS;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_NOISE_DEPTH;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_NOISE_EROSION;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_NOISE_HUMIDITY;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_NOISE_TEMPERATURE;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_NOISE_WEIRDNESS;

/**
 * Transient settings
 */
public class RenderSettings {
    private BlockPos center = new BlockPos(0, 0, 0);
    private int quartExpand = 1;
    private int quartStride = 1;
    public SamplerType samplerType = SamplerType.AUTO;
    public Identifier dimension = null;

    public boolean hideAllStructures = false;
    public transient RenderMode mode = RenderMode.BIOMES;
    public transient RenderMode lastNoise = RenderMode.NOISE_TEMPERATURE;

    public BlockPos center() {
        return center;
    }

    public void setCenter(BlockPos center) {
        this.center = center == null ? new BlockPos(0, 0, 0) : center;
    }

    public static RenderSettings defaults() {
        return new RenderSettings();
    }

    public RenderSettings copy() {
        RenderSettings copy = new RenderSettings();
        copy.apply(this);
        copy.mode = mode;
        copy.lastNoise = lastNoise;
        return copy;
    }

    public void apply(RenderSettings source) {
        if (source == null) {
            throw new IllegalArgumentException("source");
        }
        setCenter(source.center);
        setPixelsPerChunk(source.pixelsPerChunk());
        samplerType = source.samplerType == null ? SamplerType.AUTO : source.samplerType;
        dimension = source.dimension;
        hideAllStructures = source.hideAllStructures;
        mode = source.mode == null ? RenderMode.BIOMES : source.mode;
        lastNoise = source.lastNoise == null ? RenderMode.NOISE_TEMPERATURE : source.lastNoise;
    }

    public RenderSettings normalized() {
        RenderSettings normalized = copy();
        if (normalized.pixelsPerChunk() != 64 && normalized.pixelsPerChunk() != 32
                && normalized.pixelsPerChunk() != 16 && normalized.pixelsPerChunk() != 8
                && normalized.pixelsPerChunk() != 4 && normalized.pixelsPerChunk() != 2
                && normalized.pixelsPerChunk() != 1) {
            normalized.setPixelsPerChunk(4);
        }
        return normalized;
    }

    public void validate() {
        if (center == null || samplerType == null || mode == null || lastNoise == null) {
            throw new IllegalArgumentException("Invalid render settings");
        }
        int pixels = pixelsPerChunk();
        if (pixels < 1 || pixels > 64) {
            throw new IllegalArgumentException("Invalid pixels per chunk: " + pixels);
        }
    }

    public void resetCenter() {
        // Start at roughly 1/3 of the world height from the bottom.
        // For the overworld (-64..320) this lands at ~Y=64 (sea level),
        // so the y-intersection view immediately shows solid blocks
        // instead of air (white) at the build limit.
        final int yMin = WorldPreview.get().workManager().yMin();
        final int yMax = WorldPreview.get().workManager().yMax();
        int y = yMin + (yMax - yMin) / 3;
        // Align to Y_BLOCK_STRIDE boundary (matches incrementY/decrementY)
        y = (y / Y_BLOCK_STRIDE) * Y_BLOCK_STRIDE;
        center = new BlockPos(0, y, 0);
    }

    public void incrementY() {
        int nextY = (Math.min(center.getY() + Y_BLOCK_STRIDE, WorldPreview.get().workManager().yMax()) / Y_BLOCK_STRIDE) * Y_BLOCK_STRIDE;
        center = new BlockPos(center.getX(), nextY, center.getZ());
    }

    public void decrementY() {
        int nextY = (Math.max(center.getY() - Y_BLOCK_STRIDE, WorldPreview.get().workManager().yMin()) / Y_BLOCK_STRIDE) * Y_BLOCK_STRIDE;
        center = new BlockPos(center.getX(), nextY, center.getZ());
    }

    public int quartExpand() {
        return quartExpand;
    }

    public int quartStride() {
        return quartStride;
    }

    /** Accuracy contract scale for mapping and queue AABB. */
    public ScaleSpec toScaleSpec() {
        return ScaleSpec.of(quartExpand, quartStride);
    }

    public int pixelsPerChunk() {
        return (4 * quartExpand) / quartStride;
    }

    public void setPixelsPerChunk(int blocksPerChunk) {
        switch (blocksPerChunk) {
            case 16 -> {
                quartExpand = 4;
                quartStride = 1;
            }
            case 8 -> {
                quartExpand = 2;
                quartStride = 1;
            }
            case 4 -> {
                quartExpand = 1;
                quartStride = 1;
            }
            case 2 -> {
                quartExpand = 1;
                quartStride = 2;
            }
            case 1 -> {
                quartExpand = 1;
                quartStride = 4;
            }
            case 32 -> {
                quartExpand = 8;
                quartStride = 1;
            }
            case 64 -> {
                quartExpand = 16;
                quartStride = 1;
            }
            default -> throw new RuntimeException("Invalid blocksPerChunk=" + blocksPerChunk);
        }
    }

    // Zoom levels from most zoomed-in (64px/chunk) to most zoomed-out (4px/chunk)
    // Only levels with quartStride=1 are included because changing quartStride
    // requires recreating the sampler and storage sections (a world-change operation).
    private static final int[] ZOOM_LEVELS = {64, 32, 16, 8, 4};

    public int currentZoomLevel() {
        int ppc = pixelsPerChunk();
        for (int i = 0; i < ZOOM_LEVELS.length; i++) {
            if (ZOOM_LEVELS[i] == ppc) return i;
        }
        return 4; // default to 4px/chunk
    }

    public void zoomIn() {
        int level = currentZoomLevel();
        if (level > 0) {
            setPixelsPerChunk(ZOOM_LEVELS[level - 1]);
        }
    }

    public void zoomOut() {
        int level = currentZoomLevel();
        if (level < ZOOM_LEVELS.length - 1) {
            setPixelsPerChunk(ZOOM_LEVELS[level + 1]);
        }
    }

    public enum RenderMode {
        BIOMES(FLAG_BIOME, true),
        HEIGHTMAP(FLAG_HEIGHT, false),
        INTERSECTIONS(FLAG_INTERSECT, true),

        NOISE_TEMPERATURE(FLAG_NOISE_TEMPERATURE, true),
        NOISE_HUMIDITY(FLAG_NOISE_HUMIDITY, true),
        NOISE_CONTINENTALNESS(FLAG_NOISE_CONTINENTALNESS, true),
        NOISE_EROSION(FLAG_NOISE_EROSION, true),
        NOISE_DEPTH(FLAG_NOISE_DEPTH, true),
        NOISE_WEIRDNESS(FLAG_NOISE_WEIRDNESS, true),
        NOISE_PEAKS_AND_VALLEYS(FLAG_NOISE_WEIRDNESS, true),

        ;

        public final long flag;
        public final boolean useY;

        RenderMode(long flag, boolean useY) {
            this.flag = flag;
            this.useY = useY;
        }

        public boolean isNoise() {
            return this.name().startsWith("NOISE");
        }

        public Component toComponent() {
            return Component.translatable("world_preview.render_mode." + this.name().toLowerCase());
        }
    }

    public enum SamplerType {
        AUTO(x -> switch (x) {
            case 1 -> new FullQuartSampler();
            case 2 -> new QuarterQuartSampler();
            case 4 -> new SingleQuartSampler();
            default -> throw new RuntimeException("Unsupported quart stride: " + x);
        }),
        FULL(x -> new FullQuartSampler()),
        QUARTER(x -> new QuarterQuartSampler()),
        SINGLE(x -> new SingleQuartSampler()),
        ;

        private final IntFunction<ChunkSampler> samplerFactory;

        SamplerType(IntFunction<ChunkSampler> samplerFactory) {
            this.samplerFactory = samplerFactory;
        }

        public ChunkSampler create(int quartStride) {
            return samplerFactory.apply(quartStride);
        }
    }
}
