package caeruleusTait.world.preview.domain.preview.accuracy;

/**
 * Pure world �?texture �?screen mapping for a single preview viewport frame.
 * Formulas match historical PreviewDisplay behavior so wiring is behavior-preserving.
 */
public final class ViewportMapping {
    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final int texWidth;
    private final int texHeight;
    private final ScaleSpec scale;
    private final double guiScale;
    private final int worldMinX;
    private final int worldMinZ;

    public ViewportMapping(
            int centerX,
            int centerY,
            int centerZ,
            int texWidth,
            int texHeight,
            ScaleSpec scale,
            double guiScale
    ) {
        if (scale == null) {
            throw new IllegalArgumentException("scale");
        }
        if (guiScale <= 0) {
            throw new IllegalArgumentException("guiScale");
        }
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.texWidth = texWidth;
        this.texHeight = texHeight;
        this.scale = scale;
        this.guiScale = guiScale;
        double bs = scale.blockScale();
        this.worldMinX = centerX - (int)(texWidth * bs / 2.0) - 1;
        this.worldMinZ = centerZ - (int)(texHeight * bs / 2.0) - 1;
    }

    public int worldMinX() {
        return worldMinX;
    }

    public int worldMinZ() {
        return worldMinZ;
    }

    public int worldMaxX() {
        return centerX + (int)(texWidth * scale.blockScale() / 2.0) + 1;
    }

    public int worldMaxZ() {
        return centerZ + (int)(texHeight * scale.blockScale() / 2.0) + 1;
    }

    public int centerY() {
        return centerY;
    }

    public ScaleSpec scale() {
        return scale;
    }

    public int worldToTextureX(int worldX) {
        double bs = scale.blockScale();
        return (int)((((worldX - worldMinX) / 4) * 4) / bs);
    }

    public int worldToTextureZ(int worldZ) {
        double bs = scale.blockScale();
        return (int)((((worldZ - worldMinZ) / 4) * 4) / bs);
    }

    public int textureToWorldX(int texX) {
        return worldMinX + (int)(texX * scale.blockScale());
    }

    public int textureToWorldZ(int texZ) {
        return worldMinZ + (int)(texZ * scale.blockScale());
    }

    public int textureToScreenX(int texX, int widgetX) {
        return widgetX + (int) Math.floor(texX / guiScale);
    }

    public int textureToScreenZ(int texZ, int widgetY) {
        return widgetY + (int) Math.floor(texZ / guiScale);
    }

    public int textureToScreenXCeil(int texX, int widgetX) {
        return widgetX + (int) Math.ceil(texX / guiScale);
    }

    public int textureToScreenZCeil(int texZ, int widgetY) {
        return widgetY + (int) Math.ceil(texZ / guiScale);
    }

    public int textureToScreenXRound(int texX, int widgetX) {
        return widgetX + (int) Math.round(texX / guiScale);
    }

    public int textureToScreenZRound(int texZ, int widgetY) {
        return widgetY + (int) Math.round(texZ / guiScale);
    }
}
