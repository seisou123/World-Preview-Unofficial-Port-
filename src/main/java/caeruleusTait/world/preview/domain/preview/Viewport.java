package caeruleusTait.world.preview.domain.preview;

import java.util.Objects;

/**
 * Immutable snapshot of the current display viewport.
 *
 * <p>A viewport defines:
 * <ul>
 *   <li>The visible block range (offset + size)</li>
 *   <li>The Y level being displayed</li>
 *   <li>The pixel-to-block scale</li>
 * </ul>
 */
public record Viewport(
        int offsetX,
        int offsetZ,
        int width,
        int height,
        int y,
        double scale
) {

    public Viewport {
        if (width <= 0) throw new IllegalArgumentException("width must be positive");
        if (height <= 0) throw new IllegalArgumentException("height must be positive");
        if (scale <= 0) throw new IllegalArgumentException("scale must be positive");
    }

    /** Creates a viewport centered on the given block coordinates. */
    public static Viewport centered(int centerX, int centerZ, int y, int width, int height, double scale) {
        return new Viewport(centerX - width / 2, centerZ - height / 2, width, height, y, scale);
    }

    /** Returns the minimum X block coordinate visible in this viewport. */
    public int minBlockX() {
        return offsetX;
    }

    /** Returns the maximum X block coordinate visible in this viewport. */
    public int maxBlockX() {
        return offsetX + width - 1;
    }

    /** Returns the minimum Z block coordinate visible in this viewport. */
    public int minBlockZ() {
        return offsetZ;
    }

    /** Returns the maximum Z block coordinate visible in this viewport. */
    public int maxBlockZ() {
        return offsetZ + height - 1;
    }

    /** Returns {@code true} if this viewport contains the given block coordinates. */
    public boolean contains(int x, int z) {
        return x >= minBlockX() && x <= maxBlockX() && z >= minBlockZ() && z <= maxBlockZ();
    }

    /** Returns a new viewport translated by the given delta. */
    public Viewport translate(int dx, int dz) {
        return new Viewport(offsetX + dx, offsetZ + dz, width, height, y, scale);
    }

    /** Returns a new viewport with a different Y level. */
    public Viewport withY(int newY) {
        return new Viewport(offsetX, offsetZ, width, height, newY, scale);
    }

    /** Returns a new viewport with a different scale, keeping the center. */
    public Viewport withScale(double newScale) {
        return new Viewport(offsetX, offsetZ, width, height, y, newScale);
    }
}
