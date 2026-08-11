package caeruleusTait.world.preview.domain.preview.accuracy;

/**
 * Immutable sampling/render scale: mirrors {@code RenderSettings} expand/stride
 * and {@code PreviewDisplay} blockScale (blocks per texture pixel).
 */
public final class ScaleSpec {
    private final int quartExpand;
    private final int quartStride;

    private ScaleSpec(int quartExpand, int quartStride) {
        if (quartExpand < 1 || quartStride < 1) {
            throw new IllegalArgumentException("expand/stride must be >= 1");
        }
        this.quartExpand = quartExpand;
        this.quartStride = quartStride;
    }

    public static ScaleSpec of(int quartExpand, int quartStride) {
        return new ScaleSpec(quartExpand, quartStride);
    }

    /** Same mapping as {@code RenderSettings.setPixelsPerChunk}. */
    public static ScaleSpec fromPixelsPerChunk(int pixelsPerChunk) {
        return switch (pixelsPerChunk) {
            case 16 -> of(4, 1);
            case 8 -> of(2, 1);
            case 4 -> of(1, 1);
            case 2 -> of(1, 2);
            case 1 -> of(1, 4);
            case 32 -> of(8, 1);
            case 64 -> of(16, 1);
            default -> throw new IllegalArgumentException("Invalid pixelsPerChunk=" + pixelsPerChunk);
        };
    }

    public int quartExpand() {
        return quartExpand;
    }

    public int quartStride() {
        return quartStride;
    }

    public int pixelsPerChunk() {
        return (4 * quartExpand) / quartStride;
    }

    /**
     * Blocks per texture pixel. Returns double to avoid integer truncation
     * when quartExpand > QuartPos.SIZE (e.g. pixelsPerChunk=64 gives 0.25).
     */
    public double blockScale() {
        return (4.0 * quartStride) / quartExpand;
    }
}
