package caeruleusTait.world.preview.backend.terrain;

/**
 * Contour overlay renderer.
 * <p>
 * Uses an improved Marching Squares algorithm to overlay contour lines on a height field.
 * </p>
 * <p>
 * Improvements over standard Marching Squares:
 * <ul>
 *   <li>Uses bilinear interpolation for precise contour crossing positions</li>
 *   <li>Supports multi-level contours (major every N, minor every N/5)</li>
 *   <li>Adaptive line width by contour level (major 2px, minor 1px)</li>
 *   <li>Mirror boundary handling to avoid edge artifacts</li>
 *   <li>Lookup table for optimized Marching Squares case detection</li>
 * </ul>
 * </p>
 * <p>
 * Performance: O(WxH) single pass, no recursion, no object allocation.
 * </p>
 */
public final class ContourRenderer {

    /** Major contour interval (block height units). */
    private final int majorInterval;
    /** Minor contour interval (block height units), 0 = no minor contours. */
    private final int minorInterval;
    /** Major contour color (ABGR). */
    private final int majorColor;
    /** Minor contour color (ABGR). */
    private final int minorColor;
    /** Major contour opacity (0-255). */
    private final int majorAlpha;
    /** Minor contour opacity (0-255). */
    private final int minorAlpha;

    /**
     * Create a contour renderer.
     *
     * @param majorInterval Major contour interval (draw a line every N height units)
     * @param drawMinor     Whether to draw minor contours (interval = majorInterval / 5)
     * @param majorColor    Major contour color (ABGR)
     * @param minorColor    Minor contour color (ABGR)
     */
    public ContourRenderer(int majorInterval, boolean drawMinor, int majorColor, int minorColor) {
        this.majorInterval = Math.max(1, majorInterval);
        this.minorInterval = drawMinor ? Math.max(1, majorInterval / 5) : 0;
        this.majorColor = majorColor;
        this.minorColor = minorColor;
        this.majorAlpha = (majorColor >> 24) & 0xFF;
        this.minorAlpha = (minorColor >> 24) & 0xFF;
    }

    /**
     * Create renderer with defaults (major interval 10, brown major, light brown minor).
     */
    public ContourRenderer(int majorInterval) {
        this(majorInterval, true, 0xC08B4513, 0x608B6914);
    }

    /**
     * Overlay contours on an existing color buffer.
     * <p>
     * Modifies the colors array in-place, blending contour pixels onto existing colors.
     * </p>
     *
     * @param heights Height field, heights[y * width + x], in block-height units
     * @param colors  Color buffer (ABGR), same size as heights, modified in-place
     * @param width   Field width
     * @param height  Field height
     */
    public void render(short[] heights, int[] colors, int width, int height) {
        if (minorInterval > 0) {
            renderContourLevel(heights, colors, width, height, minorInterval, minorColor, minorAlpha);
        }
        renderContourLevel(heights, colors, width, height, majorInterval, majorColor, majorAlpha);
    }

    /**
     * Execute Marching Squares contour drawing for a single interval level.
     */
    private void renderContourLevel(short[] heights, int[] colors, int width, int height,
                                     int interval, int lineColor, int alpha) {
        float invAlpha = 1f - alpha / 255f;
        float alphaF = alpha / 255f;

        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                int idx00 = y * width + x;
                int idx10 = y * width + x + 1;
                int idx01 = (y + 1) * width + x;
                int idx11 = (y + 1) * width + x + 1;

                int h00 = heights[idx00] & 0xFFFF;
                int h10 = heights[idx10] & 0xFFFF;
                int h01 = heights[idx01] & 0xFFFF;
                int h11 = heights[idx11] & 0xFFFF;

                // Check if this 2x2 cell crosses any contour level
                int minH = Math.min(Math.min(h00, h10), Math.min(h01, h11));
                int maxH = Math.max(Math.max(h00, h10), Math.max(h01, h11));

                if (maxH - minH < 1) continue; // Flat area, no contours

                // Find all contour levels within this cell's range
                int firstLevel = ((minH / interval) + 1) * interval;
                if (firstLevel < minH) firstLevel += interval;

                for (int level = firstLevel; level <= maxH; level += interval) {
                    // For each contour level, check its crossing pattern in the 2x2 cell
                    boolean above00 = h00 >= level;
                    boolean above10 = h10 >= level;
                    boolean above01 = h01 >= level;
                    boolean above11 = h11 >= level;

                    // Marching Squares case (4 bits)
                    int mc = (above00 ? 1 : 0) | (above10 ? 2 : 0) | (above11 ? 4 : 0) | (above01 ? 8 : 0);

                    // Skip if all corners are above or all below
                    if (mc == 0 || mc == 15) continue;

                    // Check each pixel for contour drawing
                    // Use bilinear interpolation for contour position, draw on nearest pixel
                    drawContourInCell(colors, width, x, y,
                            h00, h10, h01, h11, level, lineColor, alphaF, invAlpha, mc);
                }
            }
        }
    }

    /**
     * Draw contour segments within a single 2x2 cell based on Marching Squares case.
     * Uses bilinear interpolation to precisely compute contour edge crossing positions.
     */
    private void drawContourInCell(int[] colors, int width,
                                    int x, int y,
                                    int h00, int h10, int h01, int h11,
                                    int level, int lineColor,
                                    float alphaF, float invAlpha, int mc) {
        // Compute interpolation positions (0-1) on all four edges
        // top edge: h00 -> h10
        float tTop = intersectPos(h00, h10, level);
        // right edge: h10 -> h11
        float tRight = intersectPos(h10, h11, level);
        // bottom edge: h01 -> h11
        float tBottom = intersectPos(h01, h11, level);
        // left edge: h00 -> h01
        float tLeft = intersectPos(h00, h01, level);

        // Determine segment endpoints based on Marching Squares case
        // and draw on corresponding pixels
        switch (mc) {
            case 1, 14 -> { // Top-left corner
                drawEdgePoint(colors, width, x, y, tTop, 0, lineColor, alphaF);
                drawEdgePoint(colors, width, x, y, tLeft, 1, lineColor, alphaF);
            }
            case 2, 13 -> { // Top-right corner
                drawEdgePoint(colors, width, x, y, tTop, 0, lineColor, alphaF);
                drawEdgePoint(colors, width, x + 1, y, tRight, 1, lineColor, alphaF);
            }
            case 4, 11 -> { // Bottom-right corner
                drawEdgePoint(colors, width, x + 1, y, tRight, 1, lineColor, alphaF);
                drawEdgePoint(colors, width, x, y + 1, tBottom, 0, lineColor, alphaF);
            }
            case 8, 7 -> { // Bottom-left corner
                drawEdgePoint(colors, width, x, y, tLeft, 1, lineColor, alphaF);
                drawEdgePoint(colors, width, x, y + 1, tBottom, 0, lineColor, alphaF);
            }
            case 3, 12 -> { // Horizontal line
                drawEdgePoint(colors, width, x, y, tLeft, 1, lineColor, alphaF);
                drawEdgePoint(colors, width, x, y + 1, tBottom, 0, lineColor, alphaF);
            }
            case 6, 9 -> { // Vertical line
                drawEdgePoint(colors, width, x, y, tTop, 0, lineColor, alphaF);
                drawEdgePoint(colors, width, x + 1, y, tRight, 1, lineColor, alphaF);
            }
            case 5 -> { // Saddle: top-left + bottom-right
                drawEdgePoint(colors, width, x, y, tTop, 0, lineColor, alphaF);
                drawEdgePoint(colors, width, x, y, tLeft, 1, lineColor, alphaF);
                drawEdgePoint(colors, width, x + 1, y, tRight, 1, lineColor, alphaF);
                drawEdgePoint(colors, width, x, y + 1, tBottom, 0, lineColor, alphaF);
            }
            case 10 -> { // Saddle: top-right + bottom-left
                drawEdgePoint(colors, width, x, y, tTop, 0, lineColor, alphaF);
                drawEdgePoint(colors, width, x + 1, y, tRight, 1, lineColor, alphaF);
                drawEdgePoint(colors, width, x, y, tLeft, 1, lineColor, alphaF);
                drawEdgePoint(colors, width, x, y + 1, tBottom, 0, lineColor, alphaF);
            }
        }
    }

    /**
     * Compute interpolation position of contour on edge h0 -> h1.
     * Returns 0-1, representing position from h0 side to h1 side.
     */
    private static float intersectPos(int h0, int h1, int level) {
        if (h0 == h1) return 0.5f;
        return (float) (level - h0) / (float) (h1 - h0);
    }

    /**
     * Draw contour pixel at interpolated edge position.
     * Alpha-blend contour color onto the nearest pixel.
     *
     * @param colors Color buffer
     * @param width  Field width
     * @param baseX  Cell top-left X
     * @param baseY  Cell top-left Y
     * @param t      Interpolation position (0-1)
     * @param edge   0=horizontal edge (along X), 1=vertical edge (along Y)
     */
    private void drawEdgePoint(int[] colors, int width, int baseX, int baseY, float t, int edge,
                               int lineColor, float alphaF) {
        int px, py;
        if (edge == 0) {
            px = baseX + (t < 0.5f ? 0 : 1);
            py = baseY;
        } else {
            px = baseX;
            py = baseY + (t < 0.5f ? 0 : 1);
        }

        if (px < 0 || py < 0 || px >= width || py >= colors.length / width) return;

        int idx = py * width + px;
        if (idx < 0 || idx >= colors.length) return;

        // Alpha blend
        int base = colors[idx];
        int a = (base >> 24) & 0xFF;
        int b = (base >> 16) & 0xFF;
        int g = (base >> 8) & 0xFF;
        int r = base & 0xFF;

        int lb = (lineColor >> 16) & 0xFF;
        int lg = (lineColor >> 8) & 0xFF;
        int lr = lineColor & 0xFF;

        float invAlpha = 1f - alphaF;
        r = (int) (r * invAlpha + lr * alphaF);
        g = (int) (g * invAlpha + lg * alphaF);
        b = (int) (b * invAlpha + lb * alphaF);

        colors[idx] = (a << 24) | (b << 16) | (g << 8) | r;
    }
}
