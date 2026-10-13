// Modified from original World Preview (https://modrinth.com/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.sampler;

import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import net.minecraft.core.QuartPos;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalInt;

/**
 * Unified read-only query entrance over the shared sampling facts (biome,
 * height, noise channels) collected by the live preview.
 *
 * <p>Analysis, comparison and terrain export should read from this query
 * first and only fall back to their own worldgen computation when a value is
 * {@link Availability#NOT_SAMPLED}. This removes duplicate worldgen work and,
 * more importantly, guarantees that every consumer sees the same facts.</p>
 *
 * <p>Missing values use an explicit three-state {@link Availability}:
 * {@code NOT_SAMPLED} (the preview has not reached this area yet — the caller
 * may compute it itself), and unsupported channels (e.g. noise when
 * {@code storeNoiseSamples} is off) are reported by the factory so callers
 * can distinguish "not found" from "never recorded".</p>
 */
public final class SampleQuery {

    /** Tri-state availability of a sampled fact. */
    public enum Availability {
        /** A real sampled value is present. */
        PRESENT,
        /** The channel is valid but this position has not been sampled yet. */
        NOT_SAMPLED,
        /** The channel is disabled by configuration and will never fill in. */
        UNSUPPORTED
    }

    /** A biome fact: its storage id plus availability. */
    public record BiomeSample(short id, Availability availability) {
        public boolean present() {
            return availability == Availability.PRESENT;
        }
    }

    private final PreviewStorage storage;
    private final boolean noiseSupported;

    private SampleQuery(PreviewStorage storage, boolean noiseSupported) {
        this.storage = storage;
        this.noiseSupported = noiseSupported;
    }

    /** Creates a query over the given storage; noise availability per config. */
    public static SampleQuery of(PreviewStorage storage, boolean noiseSupported) {
        return new SampleQuery(storage, noiseSupported);
    }

    /** Biome at the given block position (any Y inside the storage range). */
    public BiomeSample biomeAt(int blockX, int blockY, int blockZ) {
        short v = storage.getRawData4(
                QuartPos.fromBlock(blockX), QuartPos.fromBlock(blockY),
                QuartPos.fromBlock(blockZ), PreviewStorage.FLAG_BIOME);
        return v == Short.MIN_VALUE
                ? new BiomeSample(Short.MIN_VALUE, Availability.NOT_SAMPLED)
                : new BiomeSample(v, Availability.PRESENT);
    }

    /**
     * Real sampled surface height (the heightmap channel stores the top-solid
     * block Y per quart). Empty when this position has not been height-sampled
     * yet — callers must then fall back to their own estimation and mark the
     * result as estimated.
     */
    public OptionalInt realHeightAt(int blockX, int blockZ) {
        short h = storage.getRawData4(
                QuartPos.fromBlock(blockX), 0,
                QuartPos.fromBlock(blockZ), PreviewStorage.FLAG_HEIGHT);
        return h == Short.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(h);
    }

    /** Whether noise values can ever be present (config-dependent). */
    public boolean noiseSupported() {
        return noiseSupported;
    }

    /** Noise value for the given channel flag, or empty when missing/unsupported. */
    public OptionalInt noiseAt(int blockX, int blockY, int blockZ, long noiseFlag) {
        if (!noiseSupported) {
            return OptionalInt.empty();
        }
        short v = storage.getRawData4(
                QuartPos.fromBlock(blockX), QuartPos.fromBlock(blockY),
                QuartPos.fromBlock(blockZ), noiseFlag);
        return v == Short.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(v);
    }

    /**
     * Channel-aware sampled bounds (block coordinates {minX, minZ, maxX, maxZ})
     * for the given layer and channel flag, or null when nothing sampled.
     */
    public @Nullable int[] channelBounds(int y, long flags) {
        return storage.sampledBounds(y, flags);
    }

    /** Channel-aware sampled quart count for the given layer and channel flag. */
    public int channelCount(int y, long flags) {
        return storage.sampledCount(y, flags);
    }

    public PreviewStorage storage() {
        return storage;
    }
}
