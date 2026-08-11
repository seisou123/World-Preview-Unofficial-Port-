package caeruleusTait.world.preview.domain.preview;

/**
 * A rectangular preview region with bounds and sampling precision.
 */
public record PreviewRegion(
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        int sampleStep,
        int sampledMinX,
        int sampledMinZ,
        int sampledMaxX,
        int sampledMaxZ
) {
    public PreviewRegion {
        if (maxX < minX || maxZ < minZ) {
            throw new IllegalArgumentException("max must be >= min");
        }
        if (sampleStep < 1) {
            throw new IllegalArgumentException("sampleStep must be >= 1");
        }
    }

    /** Returns the width in blocks. */
    public int width() {
        return maxX - minX + 1;
    }

    /** Returns the height in blocks. */
    public int height() {
        return maxZ - minZ + 1;
    }

    /** Returns the total block area. */
    public long blockArea() {
        return (long) width() * height();
    }

    /** Returns {@code true} if the given coordinates are within this region. */
    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** Returns {@code true} if the given coordinates have been sampled. */
    public boolean isSampled(int x, int z) {
        return x >= sampledMinX && x <= sampledMaxX && z >= sampledMinZ && z <= sampledMaxZ;
    }

    /** Returns the sampling coverage ratio (0..1). */
    public double samplingCoverage() {
        long totalArea = blockArea();
        if (totalArea == 0) return 1.0;
        long sampledArea = (long)(sampledMaxX - sampledMinX + 1) * (sampledMaxZ - sampledMinZ + 1);
        return Math.min(1.0, (double) sampledArea / totalArea);
    }
}
