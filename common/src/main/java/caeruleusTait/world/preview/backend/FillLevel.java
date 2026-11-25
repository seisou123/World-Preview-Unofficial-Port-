// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend;

/**
 * Sampling density of a chunk's biome lattice, from coarsest to finest.
 *
 * <p>The three lattices are strictly nested: {@link #SINGLE} samples the
 * chunk's first quart, {@link #QUARTER} samples that same quart plus three
 * more, and {@link #FULL} samples every quart.  Refining a chunk from one
 * level to the next therefore only has to sample the points the previous
 * level left out — 3 points for SINGLE→QUARTER and 12 for QUARTER→FULL —
 * which is why a staged fill costs exactly one full-resolution pass in total
 * (1 + 3 + 12 = 16 samples per chunk) while producing two extra intermediate
 * images on the way.
 */
public enum FillLevel {
    SINGLE(1),
    QUARTER(4),
    FULL(16);

    private final int samplesPerChunk;

    FillLevel(int samplesPerChunk) {
        this.samplesPerChunk = samplesPerChunk;
    }

    /** Biome samples a chunk consumes when sampled at this level. */
    public int samplesPerChunk() {
        return samplesPerChunk;
    }

    /** Next coarser level, or {@code this} at {@link #SINGLE}. */
    public FillLevel previous() {
        return switch (this) {
            case SINGLE -> SINGLE;
            case QUARTER -> SINGLE;
            case FULL -> QUARTER;
        };
    }

    /** Next finer level, or {@code this} at {@link #FULL}. */
    public FillLevel next() {
        return switch (this) {
            case SINGLE -> QUARTER;
            case QUARTER -> FULL;
            case FULL -> FULL;
        };
    }

    /** True when this level is at least as fine as {@code other}. */
    public boolean atLeast(FillLevel other) {
        return ordinal() >= other.ordinal();
    }
}
