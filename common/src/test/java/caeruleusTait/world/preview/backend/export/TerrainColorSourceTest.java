package caeruleusTait.world.preview.backend.export;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the export color sources: the category source wraps the facts
 * resolver, the biome source bridges storage ids and sampled holders through
 * the registry tag, both self-describe the export metadata, and a tiny
 * end-to-end export pins the biome-mode PNG and metadata output.
 */
class TerrainColorSourceTest {

    @TempDir
    Path tempDir;

    private static final int Y_MIN = -64;
    private static final int Y_MAX = 320;

    /** True where NativeImage.fillRect takes ABGR (1.20.1 and 1.21.1). */
    private static final boolean NATIVE_IMAGE_WANTS_ABGR = nativeImageWantsAbgr();

    private static Holder<Biome> biome(String tag) {
        return Holder.Reference.createStandAlone(
                new HolderOwner<Biome>() {},
                ResourceKey.create(Registries.BIOME, Identifier.parse(tag)));
    }

    private static boolean nativeImageWantsAbgr() {
        try {
            NativeImage.class.getMethod("getPixelRGBA", int.class, int.class);
            return true;
        } catch (NoSuchMethodException notOlderMapping) {
            return false;
        }
    }

    private static int storedColor(int argb) {
        return NATIVE_IMAGE_WANTS_ABGR
                ? ((argb & 0xFF00FF00) | ((argb >>> 16) & 0xFF) | ((argb & 0xFF) << 16))
                : argb;
    }

    private static TerrainMapExporter.BiomeColorTable table() {
        return new TerrainMapExporter.BiomeColorTable(
                new int[]{0, 0xFF123456, 0xFF00FF00},
                new String[]{null, "minecraft:ocean", "minecraft:plains"},
                new String[]{null, "Ocean", "Plains"});
    }

    @Test
    void categorySourceMirrorsTheResolver() {
        TerrainMapExporter.IdTableResolver resolver = new TerrainMapExporter.IdTableResolver(
                new TerrainCategory[]{null, TerrainCategory.OCEAN}, new short[]{0, 50});
        TerrainMapExporter.TerrainColorSource colors = TerrainMapExporter.categories(resolver);
        assertEquals(TerrainCategory.OCEAN.argbColor(), colors.colorForId((short) 1),
                "fast-path color comes from the resolver's per-id category");
        assertEquals(TerrainCategory.OCEAN.ordinal(), colors.bucketForId((short) 1));
        assertEquals(TerrainCategory.values().length, colors.bucketCount());
        assertEquals("vanilla_biome_tag_priority", colors.modeName());
    }

    @Test
    void categorySourceWithoutResolverStillClassifiesHolders() {
        TerrainMapExporter.TerrainColorSource colors = TerrainMapExporter.categories(null);
        assertEquals(TerrainCategory.PLAINS.argbColor(), colors.colorForHolder(biome("minecraft:plains")));
        assertEquals(TerrainCategory.PLAINS.ordinal(), colors.bucketForHolder(biome("minecraft:plains")));
    }

    @Test
    void biomeSourceBridgesIdsTagsAndHolders() {
        TerrainMapExporter.TerrainColorSource colors = TerrainMapExporter.biomes(table());
        assertEquals(0xFF123456, colors.colorForId((short) 1), "storage ids index the color table");
        assertEquals(1, colors.bucketForId((short) 1));
        assertEquals(0xFF00FF00, colors.colorForHolder(biome("minecraft:plains")),
                "sampled holders bridge through the registry tag");
        assertEquals(2, colors.bucketForHolder(biome("minecraft:plains")));
        assertEquals(TerrainCategory.UNKNOWN.argbColor(), colors.colorForHolder(biome("minecraft:savanna")),
                "unregistered biomes fall back to the UNKNOWN category color");
        assertEquals(-1, colors.bucketForHolder(biome("minecraft:savanna")));
        assertEquals(TerrainCategory.UNKNOWN.argbColor(), colors.colorForHolder(null));
        assertEquals("biome_colors", colors.modeName());
        assertEquals(3, colors.bucketCount());
    }

    /**
     * The fused holder lookup must answer exactly what the individual
     * accessors answer -- color, bucket (including the -1 skip sentinel) and
     * the height estimate (0 for null holders) -- for both color modes and
     * the four holder semantics: null, table-known, unregistered, modded.
     */
    @Test
    void resolveHolderMatchesTheIndividualAccessors() {
        TerrainMapExporter.TerrainColorSource category = TerrainMapExporter.categories(null);
        TerrainMapExporter.TerrainColorSource biome = TerrainMapExporter.biomes(table());
        Holder<Biome>[] holders = new Holder[]{
                null, biome("minecraft:plains"), biome("minecraft:savanna"), biome("somemod:weird_swamp")};
        for (TerrainMapExporter.TerrainColorSource source : new TerrainMapExporter.TerrainColorSource[]{category, biome}) {
            for (Holder<Biome> holder : holders) {
                long style = source.resolveHolder(holder);
                assertEquals(source.colorForHolder(holder), TerrainMapExporter.TerrainColorSource.argbOf(style),
                        "color slot must match colorForHolder for " + holder);
                assertEquals(source.bucketForHolder(holder), TerrainMapExporter.TerrainColorSource.bucketOf(style),
                        "bucket slot must match bucketForHolder for " + holder);
                int expectedEstimate = holder == null ? 0
                        : TerrainClassifier.categoryHeight(TerrainClassifier.classify(holder));
                assertEquals(expectedEstimate, TerrainMapExporter.TerrainColorSource.estimatedHeightOf(style),
                        "estimate slot must match the classifier estimate for " + holder);
            }
        }
    }

    /** Same contract for the storage-id fast path, including the NO_ESTIMATE slot of biome mode. */
    @Test
    void resolveIdMatchesTheIndividualAccessors() {
        TerrainMapExporter.IdTableResolver resolver = new TerrainMapExporter.IdTableResolver(
                new TerrainCategory[]{null, TerrainCategory.OCEAN}, new short[]{0, 50});
        for (short id : new short[]{1, 7}) {
            long style = TerrainMapExporter.categories(resolver).resolveId(id);
            assertEquals(TerrainMapExporter.categories(resolver).colorForId(id),
                    TerrainMapExporter.TerrainColorSource.argbOf(style), "color slot for id " + id);
            assertEquals(TerrainMapExporter.categories(resolver).bucketForId(id),
                    TerrainMapExporter.TerrainColorSource.bucketOf(style), "bucket slot for id " + id);
            assertEquals(resolver.estimatedHeight(id),
                    TerrainMapExporter.TerrainColorSource.estimatedHeightOf(style), "estimate slot for id " + id);
        }

        TerrainMapExporter.TerrainColorSource biome = TerrainMapExporter.biomes(table());
        long known = biome.resolveId((short) 1);
        assertEquals(0xFF123456, TerrainMapExporter.TerrainColorSource.argbOf(known));
        assertEquals(1, TerrainMapExporter.TerrainColorSource.bucketOf(known));
        assertEquals(TerrainMapExporter.TerrainColorSource.NO_ESTIMATE,
                TerrainMapExporter.TerrainColorSource.estimatedHeightOf(known),
                "biome mode carries no estimate on the id path");

        long unknown = biome.resolveId((short) 7);
        assertEquals(TerrainCategory.UNKNOWN.argbColor(), TerrainMapExporter.TerrainColorSource.argbOf(unknown));
        assertEquals(7, TerrainMapExporter.TerrainColorSource.bucketOf(unknown));
        assertEquals(TerrainMapExporter.TerrainColorSource.NO_ESTIMATE,
                TerrainMapExporter.TerrainColorSource.estimatedHeightOf(unknown));
    }

    @Test
    void legendWritersDescribeTheMetadata() {
        JsonObject categoryMeta = new JsonObject();
        TerrainMapExporter.categories(null).writeLegend(categoryMeta, new long[TerrainCategory.values().length]);
        assertTrue(categoryMeta.has("categoryPixels"), "category mode keeps the categoryPixels stats");
        assertTrue(categoryMeta.has("categories"), "category mode keeps the category legend");

        JsonObject biomeMeta = new JsonObject();
        long[] stats = new long[3];
        stats[2] = 9;
        TerrainMapExporter.TerrainColorSource colors = TerrainMapExporter.biomes(table());
        colors.writeLegend(biomeMeta, stats);
        assertTrue(biomeMeta.has("biomePixels"), "biome mode writes per-biome pixel counts");
        assertEquals(9, biomeMeta.get("biomePixels").getAsJsonObject().get("minecraft:plains").getAsLong());
        var biomes = biomeMeta.get("biomes").getAsJsonArray();
        assertEquals(2, biomes.size(), "unregistered id slots are not part of the legend");
        JsonObject plains = biomes.get(1).getAsJsonObject();
        assertEquals("minecraft:plains", plains.get("id").getAsString());
        assertEquals("Plains", plains.get("name").getAsString());
        assertEquals("#00FF00", plains.get("color").getAsString());
    }

    @Test
    void biomeModeExportPaintsPreviewColorsAndWritesTheJsonLegend() throws Exception {
        TerrainExportSpec spec = new TerrainExportSpec(256, 16, 0, 0, false, 1, 0, false,
                TerrainExportColorMode.BIOMES, 64);
        TerrainMapExporter exporter = new TerrainMapExporter(2);
        TerrainMapExporter.Result result = exporter.export(
                spec, (blockX, blockZ) -> biome("minecraft:plains"), TerrainMapExporter.biomes(table()),
                null, null, null, Y_MIN, Y_MAX, tempDir, "biomes_", () -> false, p -> { });

        assertTrue(Files.exists(result.pngPath()), "export must write the PNG");
        try (InputStream in = Files.newInputStream(result.pngPath()); NativeImage image = NativeImage.read(in)) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    assertEquals(storedColor(0xFF00FF00), image.getPixel(x, y),
                            "every pixel takes the biome's preview color at " + x + "," + y);
                }
            }
        }
        String metadata = Files.readString(result.metadataPath());
        assertTrue(metadata.contains("\"classificationMode\": \"biome_colors\""),
                "biome mode must record its classificationMode");
        assertTrue(metadata.contains("\"biomePixels\""), "metadata must carry per-biome pixel counts");
        assertTrue(metadata.contains("\"minecraft:plains\": 1024"), "all 32x32 pixels are plains");
        assertTrue(metadata.contains("\"exportY\": 64"), "metadata must record the sampling layer");
    }
}
