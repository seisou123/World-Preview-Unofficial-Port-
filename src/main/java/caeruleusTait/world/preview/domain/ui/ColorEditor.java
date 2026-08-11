package caeruleusTait.world.preview.domain.ui;

/**
 * Color editor abstraction: HSV picker + color list + preview.
 *
 * <p>The actual rendering is platform-specific; this interface allows
 * the domain layer to describe color editing operations.
 */
public interface ColorEditor {

    /** Returns the current color (ARGB). */
    int currentColor();

    /** Sets the current color. */
    void setColor(int argb);

    /** Returns the HSV hue component (0..360). */
    float hue();

    /** Returns the HSV saturation component (0..1). */
    float saturation();

    /** Returns the HSV value component (0..1). */
    float value();

    /** Sets the color from HSV components. */
    void setFromHSV(float h, float s, float v);

    /** Returns the list of saved colors. */
    java.util.List<Integer> savedColors();

    /** Adds a color to the saved list. */
    void saveColor(int argb);

    /** Removes a color from the saved list. */
    void removeColor(int argb);

    /** Registers a listener for color changes. */
    void addColorChangeListener(java.util.function.IntConsumer listener);

    // ---- Color conversion utilities ----

    /** Converts RGB to HSV. */
    static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float h = 0;
        if (delta > 0) {
            if (max == rf) h = ((gf - bf) / delta) % 6;
            else if (max == gf) h = (bf - rf) / delta + 2;
            else h = (rf - gf) / delta + 4;
            h *= 60;
            if (h < 0) h += 360;
        }
        float s = max == 0 ? 0 : delta / max;
        return new float[]{h, s, max};
    }

    /** Converts HSV to RGB. */
    static int hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60) % 2 - 1));
        float m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return ((int)((r + m) * 255) << 16)
                | ((int)((g + m) * 255) << 8)
                | (int)((b + m) * 255);
    }
}
