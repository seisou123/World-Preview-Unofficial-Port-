package caeruleusTait.world.preview.backend.export;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Export-level tests for the storage-backed biome facts fast path (tiny
 * 32x32 PNG, no contours, no grid). The preview storage itself needs the
 * Minecraft client, so the probe is simulated directly; the cases cover the
 * exporter contract:
 * <ul>
 *   <li>facts hit -> pixel color comes from the resolver's per-id category</li>
 *   <li>probe miss (unsampled chunk) -> falls back to the noise sampler</li>
 *   <li>probe answers an id the resolver does not know -> falls back too</li>
 * </ul>
 */
class TerrainMapExporterFactsExportTest {

    @TempDir
    Path tempDir;

    private static final int Y_MIN = -64;
    private static final int Y_MAX = 320;

    /** Stand-alone "minecraft:plains" holder; classifies as PLAINS via id keywords. */
    private static TerrainMapExporter.BiomeSampler plainsSampler() {
        Holder<Biome> plains = Holder.Reference.createStandAlone(
                new HolderOwner<Biome>() {},
                ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:plains")));
        return (blockX, blockZ) -> plains;
    }

    private static TerrainExportSpec tinySpec() {
        // 512/16 = 32 -> 32x32 pixels, contours and grid disabled.
        return new TerrainExportSpec(256, 16, 0, 0, false, 1);
    }

    private static int[] readPixels(Path pngPath) throws Exception {
        try (InputStream in = Files.newInputStream(pngPath); NativeImage image = NativeImage.read(in)) {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // getPixel converts the stored ABGR value back to the exact
                    // color the exporter passed to fillRect.
                    pixels[y * width + x] = image.getPixel(x, y);
                }
            }
            return pixels;
        }
    }

    private static void assertUniform(int[] pixels, int expected) {
        for (int i = 0; i < pixels.length; i++) {
            assertEquals(expected, pixels[i], "pixel " + i + " must use the expected category color");
        }
    }

    private void exportAndCheck(@org.jetbrains.annotations.Nullable TerrainMapExporter.BiomeFacts facts,
                                int expectedColor) throws Exception {
        TerrainMapExporter exporter = new TerrainMapExporter(2);
        TerrainMapExporter.Result result = exporter.export(
                tinySpec(), plainsSampler(), null, null, facts, Y_MIN, Y_MAX,
                tempDir, "facts_", () -> false, p -> { });

        assertTrue(Files.exists(result.pngPath()), "export must write the PNG");
        String metadata = Files.readString(result.metadataPath());
        assertTrue(metadata.contains("\"heightSource\": \"estimated\""),
                "fact hits count as estimated heights; expected estimated metadata");

        assertUniform(readPixels(result.pngPath()), expectedColor);
    }

    @org.junit.jupiter.api.Test
    void factsHitPaintsStorageCategoryColor() throws Exception {
        // Storage id 1 -> OCEAN, while the noise sampler would say PLAINS.
        TerrainMapExporter.BiomeFacts facts = new TerrainMapExporter.BiomeFacts(
                (blockX, blockZ) -> (short) 1,
                new TerrainMapExporter.IdTableResolver(
                        new TerrainCategory[]{null, TerrainCategory.OCEAN},
                        new short[]{0, 50}));
        exportAndCheck(facts, TerrainCategory.OCEAN.pixelColor());
    }

    @org.junit.jupiter.api.Test
    void probeMissFallsBackToNoiseSampler() throws Exception {
        // Unsamped chunk: the probe answers null for every pixel.
        TerrainMapExporter.BiomeFacts facts = new TerrainMapExporter.BiomeFacts(
                (blockX, blockZ) -> null,
                new TerrainMapExporter.IdTableResolver(
                        new TerrainCategory[]{null, TerrainCategory.OCEAN},
                        new short[]{0, 50}));
        exportAndCheck(facts, TerrainCategory.PLAINS.pixelColor());
    }

    @org.junit.jupiter.api.Test
    void unknownStorageIdFallsBackToNoiseSampler() throws Exception {
        // Misaligned storage id 7 is outside the resolver table.
        TerrainMapExporter.BiomeFacts facts = new TerrainMapExporter.BiomeFacts(
                (blockX, blockZ) -> (short) 7,
                new TerrainMapExporter.IdTableResolver(
                        new TerrainCategory[]{null, TerrainCategory.OCEAN},
                        new short[]{0, 50}));
        exportAndCheck(facts, TerrainCategory.PLAINS.pixelColor());
    }
}
