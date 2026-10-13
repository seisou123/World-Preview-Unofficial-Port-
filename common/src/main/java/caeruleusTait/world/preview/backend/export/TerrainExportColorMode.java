package caeruleusTait.world.preview.backend.export;

/**
 * Pixel color source for the terrain export map.
 * <p>
 * {@link #CATEGORY} paints the nine terrain categories (the historical
 * behavior); {@link #BIOMES} paints every biome in its preview color — same
 * color the BIOMES view uses, including user overrides — trading the fixed
 * category legend for a per-biome legend written into the export metadata.
 * </p>
 */
public enum TerrainExportColorMode {
    CATEGORY,
    BIOMES
}
