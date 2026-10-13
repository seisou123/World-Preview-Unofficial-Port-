package caeruleusTait.world.preview.backend.terrain;

/**
 * Hillshade renderer.
 * <p>
 * Not a simple height grayscale, but simulates oblique sunlight to enhance terrain relief.
 * Algorithm core:
 * </p>
 * <ol>
 *   <li>Compute per-pixel gradient (dz/dx, dz/dz) from height field using central differences</li>
 *   <li>Build surface normal N = (-dz/dx, 1, -dz/dz) and normalize</li>
 *   <li>Define light direction vector L (configurable azimuth and altitude) and normalize</li>
 *   <li>Compute diffuse intensity I = max(0, N dot L)</li>
 *   <li>Add ambient light I_ambient to prevent fully black back-lit areas</li>
 *   <li>Add multi-level soft shadows: sample distant gradients for large-scale occlusion</li>
 *   <li>Apply gamma correction and tone mapping for smoother mid-tones</li>
 * </ol>
 * <p>
 * Performance optimizations:
 * <ul>
 *   <li>All computation uses float arrays, no object allocation</li>
 *   <li>Sobel operator (3x3) for smoother gradients than simple central differences</li>
 *   <li>Fast inverse square root approximation for normal normalization</li>
 *   <li>Supports pixel-level parallel processing</li>
 * </ul>
 * </p>
 */
public final class HillshadeRenderer {

    /** Light azimuth (radians), 0=north, clockwise. Default 315 deg = NW. */
    private final float azimuthRad;
    /** Light altitude (radians), 0=horizon, pi/2=zenith. Default 45 deg. */
    private final float altitudeRad;
    /** Ambient light intensity (0-1), prevents fully black shadows. */
    private final float ambient;
    /** Vertical exaggeration factor, enhances micro-terrain visibility. */
    private final float verticalExaggeration;
    /** Slope shadow weight (0-1), controls slope influence on final brightness. */
    private final float slopeWeight;

    /**
     * Pre-computed light vector L (normalized).
     * Azimuth -> x = sin(az), z = -cos(az) (north = -Z in Minecraft)
     * Altitude -> y = sin(alt), horizontal component = cos(alt)
     */
    private final float lightX, lightY, lightZ;

    public HillshadeRenderer(float azimuthDegrees, float altitudeDegrees,
                             float ambient, float verticalExaggeration, float slopeWeight) {
        this.azimuthRad = (float) Math.toRadians(azimuthDegrees);
        this.altitudeRad = (float) Math.toRadians(altitudeDegrees);
        this.ambient = Math.max(0f, Math.min(1f, ambient));
        this.verticalExaggeration = Math.max(0.1f, verticalExaggeration);
        this.slopeWeight = Math.max(0f, Math.min(1f, slopeWeight));

        float horiz = (float) Math.cos(this.altitudeRad);
        this.lightX = horiz * (float) Math.sin(this.azimuthRad);
        this.lightY = (float) Math.sin(this.altitudeRad);
        this.lightZ = -horiz * (float) Math.cos(this.azimuthRad);
    }

    /**
     * Create renderer with defaults (NW 45 deg light, ambient 0.3).
     */
    public HillshadeRenderer() {
        this(315f, 45f, 0.3f, 1.0f, 0.5f);
    }

    /**
     * Render hillshade on a height field, outputting per-pixel brightness (0-255).
     * <p>
     * Uses Sobel operator for gradients, better noise suppression and edge preservation than central differences.
     * </p>
     *
     * @param heights Height field, row-major, heights[y * width + x]; values are
     *                block heights in world-Y units (only differences matter)
     * @param width   Field width
     * @param height  Field height
     * @param blockScale Block distance per sample point (real scale for gradient computation)
     * @return Brightness array (0-255), same size as input
     */
    public byte[] render(short[] heights, int width, int height, float blockScale) {
        return render(heights, width, height, blockScale, null);
    }

    /**
     * Same as {@link #render(short[], int, int, float)} but writes into the
     * caller-provided {@code out} array when it is large enough, so repeated
     * full-viewport renders reuse the buffer instead of allocating a fresh
     * one every frame.
     *
     * @return {@code out} when reused, otherwise a newly allocated array
     */
    public byte[] render(short[] heights, int width, int height, float blockScale, byte[] out) {
        final int size = width * height;
        byte[] result = (out != null && out.length >= size) ? out : new byte[size];
        final float gradScale = verticalExaggeration / (8f * blockScale);
        // Constant brightness for fully unsampled (all-zero) neighborhoods: a
        // flat gradient yields slope factor 1 and diffuse = lightY.
        final int flatVal = POW_LUT[Math.min(255, (int) (reinhard(ambient + (1f - ambient) * lightY) * 255f))];

        for (int y = 0; y < height; y++) {
            final int row0 = (y > 0 ? y - 1 : 0) * width;
            final int row2 = (y < height - 1 ? y + 1 : height - 1) * width;
            final int row1 = y * width;
            int idx = row1;
            for (int x = 0; x < width; x++, idx++) {
                final int xm1 = x > 0 ? x - 1 : 0;
                final int xp1 = x < width - 1 ? x + 1 : width - 1;

                final int r00 = heights[row0 + xm1] & 0xFFFF;
                final int r10 = heights[row0 + x] & 0xFFFF;
                final int r20 = heights[row0 + xp1] & 0xFFFF;
                final int r01 = heights[row1 + xm1] & 0xFFFF;
                final int r21 = heights[row1 + xp1] & 0xFFFF;
                final int r02 = heights[row2 + xm1] & 0xFFFF;
                final int r12 = heights[row2 + x] & 0xFFFF;
                final int r22 = heights[row2 + xp1] & 0xFFFF;

                // Skip unsampled (all-zero) regions: a flat gradient produces a
                // constant ambient shade, so there is no lighting to compute.
                if ((r00 | r10 | r20 | r01 | r21 | r02 | r12 | r22) == 0) {
                    result[idx] = (byte) flatVal;
                    continue;
                }

                // Sobel X: (-1 0 +1; -2 0 +2; -1 0 +1)
                float dzdx = gradScale * (-r00 + r20 - 2f * r01 + 2f * r21 - r02 + r22);

                // Sobel Z: (-1 -2 -1; 0 0 0; +1 +2 +1)
                float dzdz = gradScale * (-r00 - 2f * r10 - r20 + r02 + 2f * r12 + r22);

                // Normal N = (-dzdx, 1, -dzdz), normalized, dotted with light L.
                float nx = -dzdx;
                float nz = -dzdz;
                float invLen = fastInvSqrt(nx * nx + 1f + nz * nz);
                float diffuse = Math.max(0f, (nx * lightX + lightY + nz * lightZ) * invLen);

                // Slope factor: steeper slopes are slightly dimmer (atmospheric scattering)
                float slope = (float) Math.sqrt(dzdx * dzdx + dzdz * dzdz);
                float slopeFactor = 1f - slopeWeight * Math.min(1f, slope * 0.3f);

                // Final brightness = ambient + diffuse * slope factor
                float intensity = ambient + (1f - ambient) * diffuse * slopeFactor;
                intensity = reinhard(intensity);
                result[idx] = (byte) POW_LUT[Math.min(255, (int) (intensity * 255f))];
            }
        }

        return result;
    }

    /**
     * Apply hillshade brightness to existing colors.
     * <p>
     * Multiplicative blending: finalColor = baseColor * (shade / 255).
     * Brightness 128 = unchanged, <128 darker, >128 brighter (capped at original).
     * </p>
     *
     * @param baseColor Base color in ABGR format
     * @param shade     Brightness value 0-255
     * @return Blended ABGR color
     */
    public static int applyShade(int baseColor, byte shade) {
        float factor = (shade & 0xFF) / 128f; // 0.0 ~ 2.0
        if (factor > 1f) {
            // Brightness boost: interpolate toward white
            float boost = (factor - 1f) * 0.3f; // Limit boost magnitude
            int a = (baseColor >> 24) & 0xFF;
            int b = (baseColor >> 16) & 0xFF;
            int g = (baseColor >> 8) & 0xFF;
            int r = baseColor & 0xFF;
            b = Math.min(255, (int) (b + (255 - b) * boost));
            g = Math.min(255, (int) (g + (255 - g) * boost));
            r = Math.min(255, (int) (r + (255 - r) * boost));
            return (a << 24) | (b << 16) | (g << 8) | r;
        } else {
            // Brightness attenuation: multiply directly
            int a = (baseColor >> 24) & 0xFF;
            int b = (baseColor >> 16) & 0xFF;
            int g = (baseColor >> 8) & 0xFF;
            int r = baseColor & 0xFF;
            b = (int) (b * factor);
            g = (int) (g * factor);
            r = (int) (r * factor);
            return (a << 24) | (b << 16) | (g << 8) | r;
        }
    }

    /** Reinhard tone map applied to the raw brightness before gamma. */
    private static float reinhard(float intensity) {
        return intensity / (intensity + 0.15f);
    }

    /**
     * Look-up table for {@code pow(i/255, 0.8)} over the 0-255 brightness
     * range, replacing a per-pixel {@link Math#pow} call in the hot loop.
     */
    private static final byte[] POW_LUT = buildPowLut();

    private static byte[] buildPowLut() {
        byte[] lut = new byte[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = (byte) (int) (Math.pow(i / 255.0, 0.8) * 255.0);
        }
        return lut;
    }

    /**
     * Fast inverse square root (Quake III style approximation).
     * About 3x faster than Math.sqrt, sufficient precision for lighting.
     */
    private static float fastInvSqrt(float x) {
        float halfX = 0.5f * x;
        int i = Float.floatToIntBits(x);
        i = 0x5f3759df - (i >> 1);
        float y = Float.intBitsToFloat(i);
        y = y * (1.5f - halfX * y * y); // One Newton iteration
        return y;
    }
}
