package caeruleusTait.world.preview.backend.export;

/**
 * Terrain classification categories.
 * <p>
 * Classifies each sample point in the world into a terrain type, each with a unique RGB color
 * for intuitive terrain visualization in the exported PNG map.
 * </p>
 * <p>
 * Unlike the TFC binary land/water classification, this classifier uses a nine-level terrain hierarchy
 * based on vanilla biome tags, without depending on any third-party mods.
 * </p>
 */
public enum TerrainCategory {

    /** Deep ocean -- cold, open ocean where land structures cannot generate. */
    DEEP_OCEAN(0x1B, 0x3A, 0x5C),

    /** Shallow ocean -- nearshore waters, may be adjacent to coast. */
    OCEAN(0x2D, 0x6A, 0x9F),

    /** River -- freshwater channels. */
    RIVER(0x4A, 0x90, 0xA4),

    /** Beach/coast -- sandy or gravel transition zones. */
    BEACH(0xE8, 0xD5, 0x5A),

    /** Plains/grassland -- low-elevation open terrain. */
    PLAINS(0x7C, 0xB3, 0x4A),

    /** Forest -- wooded areas at low-to-mid elevation. */
    FOREST(0x2E, 0x7D, 0x32),

    /** Hills -- rolling terrain, not mountainous. */
    HILLS(0x9C, 0x80, 0x4B),

    /** Mountain -- high-elevation steep terrain. */
    MOUNTAIN(0x8C, 0x8C, 0x8C),

    /** Peak -- extreme high-altitude summits above the snow line. */
    PEAK(0xF0, 0xF0, 0xF0),

    /** Unclassified -- special biomes that do not fit any category above. */
    UNKNOWN(0x6A, 0x1B, 0x6A);

    private final int rgb;

    TerrainCategory(int r, int g, int b) {
        // NativeImage uses ABGR format: 0xAABBGGRR
        this.rgb = (0xFF << 24) | ((b & 0xFF) << 16) | ((g & 0xFF) << 8) | (r & 0xFF);
    }

    /**
     * Returns the ABGR pixel value for this terrain category, directly writable to {@link com.mojang.blaze3d.platform.NativeImage}.
     */
    public int pixelColor() {
        return rgb;
    }

    /**
     * Returns the RGB hex string (e.g. "#1B3A5C") for this terrain category, used in metadata JSON.
     */
    public String hexColor() {
        int r = rgb & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = (rgb >> 16) & 0xFF;
        return String.format("#%02X%02X%02X", r, g, b);
    }
}
