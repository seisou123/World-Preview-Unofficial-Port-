package caeruleusTait.world.preview.backend.export;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storage-band fast-path tests: the per-tile bulk probe must be
 * pixel-identical to the per-pixel probe it replaces -- for a fully covered
 * tile, for an unsupported band (returns false), and for a partially covered
 * band whose holes fall back to the per-pixel probe.
 */
class TerrainExportBandTest {

    @TempDir
    Path tempDir;

    private static final int Y_MIN = -64;
    private static final int Y_MAX = 320;

    private static Holder<Biome> plains() {
        return Holder.Reference.createStandAlone(
                new HolderOwner<Biome>() {},
                ResourceKey.create(Registries.BIOME, new ResourceLocation("minecraft:plains")));
    }

    /**
     * Checkerboard storage at 16-block granularity: chunks with an even
     * (chunkX + chunkZ) answer biome id 1 (OCEAN), odd chunks are unsampled.
     * The noise sampler answers PLAINS for everything else.
     */
    private static Short answer(int blockX, int blockZ) {
        return ((blockX >> 4) + (blockZ >> 4)) % 2 == 0 ? (short) 1 : null;
    }

    private static TerrainMapExporter.BiomeFacts facts(
            boolean withBand, boolean bandCoversEverything) {
        TerrainMapExporter.BiomeIdProbe probe = new TerrainMapExporter.BiomeIdProbe() {
            @Override
            public Short biomeIdAt(int blockX, int blockZ) {
                return answer(blockX, blockZ);
            }

            @Override
            public boolean biomeIdBand(int minBlockX, int minBlockZ, int step, int width, int height,
                                       short[] out, java.util.function.BooleanSupplier cancelled) {
                if (!withBand) {
                    return false;
                }
                for (int row = 0; row < height; row++) {
                    for (int col = 0; col < width; col++) {
                        Short hit = bandCoversEverything
                                ? answer(minBlockX + col * step, minBlockZ + row * step)
                                : (col < width / 2
                                        ? answer(minBlockX + col * step, minBlockZ + row * step)
                                        : null);
                        out[row * width + col] = hit == null ? (short) -1 : hit;
                    }
                }
                return true;
            }
        };
        return new TerrainMapExporter.BiomeFacts(
                probe, new TerrainMapExporter.IdTableResolver(
                        new TerrainCategory[]{null, TerrainCategory.OCEAN}, new short[]{0, 50}));
    }

    private Path export(TerrainMapExporter.BiomeFacts facts, String prefix) throws Exception {
        TerrainMapExporter exporter = new TerrainMapExporter(2);
        // bpp 16 -> 32x32 pixels spanning 512 blocks: a 32-chunk checkerboard.
        TerrainExportSpec spec = new TerrainExportSpec(256, 16, 0, 0, false, 1);
        return exporter.export(spec, (blockX, blockZ) -> plains(),
                TerrainMapExporter.categories(facts.resolver()), null, null, facts,
                Y_MIN, Y_MAX, tempDir, prefix, () -> false, p -> { }).pngPath();
    }

    @Test
    void fullBandMatchesThePerPixelProbe() throws Exception {
        Path perPixel = export(facts(false, true), "pp_");
        Path banded = export(facts(true, true), "band_");
        assertArrayEquals(Files.readAllBytes(perPixel), Files.readAllBytes(banded),
                "a fully covering band must be pixel-identical");
    }

    @Test
    void partialBandFallsBackPerCell() throws Exception {
        Path perPixel = export(facts(false, true), "pp2_");
        Path halfBand = export(facts(true, false), "half_");
        assertArrayEquals(Files.readAllBytes(perPixel), Files.readAllBytes(halfBand),
                "band holes must be filled by the per-pixel probe");
    }

    @Test
    void bandAnswersAffectTheImage() throws Exception {
        Path withFacts = export(facts(true, true), "hit_");
        String metadata = Files.readString(withFacts.resolveSibling(
                withFacts.getFileName().toString().replace(".png", ".json")));
        assertTrue(metadata.contains("\"OCEAN\": 512"),
                "half of the 32x32 checkerboard resolves through storage (16x16 = 512)");
    }
}
