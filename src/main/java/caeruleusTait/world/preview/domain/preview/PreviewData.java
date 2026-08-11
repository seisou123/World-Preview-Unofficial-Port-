package caeruleusTait.world.preview.domain.preview;

import java.util.Objects;

/**
 * Immutable preview data snapshot containing biome, structure, and height data.
 *
 * <p>Replaces the mutable PreviewBlock data sharing pattern.
 */
public record PreviewData(
        long seed,
        String dimension,
        int yMin,
        int yMax,
        int quartStride,
        DataTypeFlags dataTypes
) {

    public PreviewData {
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isBlank()) throw new IllegalArgumentException("dimension must not be blank");
        if (yMax <= yMin) throw new IllegalArgumentException("yMax must be > yMin");
        if (quartStride < 1) throw new IllegalArgumentException("quartStride must be >= 1");
        dataTypes = dataTypes == null ? DataTypeFlags.none() : dataTypes;
    }

    /** Returns the height range. */
    public int height() {
        return yMax - yMin;
    }

    /** Flags indicating which data types are present in the preview. */
    public record DataTypeFlags(boolean biome, boolean height, boolean structures, boolean intersections, boolean noise) {
        public static DataTypeFlags none() {
            return new DataTypeFlags(false, false, false, false, false);
        }
        public static DataTypeFlags all() {
            return new DataTypeFlags(true, true, true, true, true);
        }
        public static DataTypeFlags biomeOnly() {
            return new DataTypeFlags(true, false, false, false, false);
        }
    }
}
