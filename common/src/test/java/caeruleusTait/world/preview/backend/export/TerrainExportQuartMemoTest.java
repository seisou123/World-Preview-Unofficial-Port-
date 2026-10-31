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
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-tile quart memo tests: a {@link TerrainMapExporter.QuartBiomeSampler}
 * is looked up once per quart column (not per pixel) at bpp &lt; 4, plain
 * samplers keep their exact per-pixel behavior, off-grid export origins stay
 * inside the memo, and the memoized output is pixel-identical to the plain
 * sampler's.
 */
class TerrainExportQuartMemoTest {

    @TempDir
    Path tempDir;

    private static final int Y_MIN = -64;
    private static final int Y_MAX = 320;

    private static Holder<Biome> plains() {
        return Holder.Reference.createStandAlone(
                new HolderOwner<Biome>() {},
                ResourceKey.create(Registries.BIOME, ResourceLocation.parse("minecraft:plains")));
    }

    /** Counting sampler typed with the quart marker. */
    private static final class CountingQuart implements TerrainMapExporter.QuartBiomeSampler {
        final Holder<Biome> holder;
        final AtomicLong calls = new AtomicLong();

        CountingQuart(Holder<Biome> holder) {
            this.holder = holder;
        }

        @Override
        public Holder<Biome> sample(int blockX, int blockZ) {
            calls.incrementAndGet();
            return holder;
        }
    }

    /** Same counting logic without the marker: the memo must stay off. */
    private static final class CountingPlain implements TerrainMapExporter.BiomeSampler {
        final Holder<Biome> holder;
        final AtomicLong calls = new AtomicLong();

        CountingPlain(Holder<Biome> holder) {
            this.holder = holder;
        }

        @Override
        public Holder<Biome> sample(int blockX, int blockZ) {
            calls.incrementAndGet();
            return holder;
        }
    }

    private TerrainMapExporter.Result export(TerrainExportSpec spec, TerrainMapExporter.BiomeSampler sampler,
                                             String prefix) throws Exception {
        TerrainMapExporter exporter = new TerrainMapExporter(2);
        return exporter.export(spec, sampler, TerrainMapExporter.categories(null), null, null, null,
                Y_MIN, Y_MAX, tempDir, prefix, () -> false, p -> { });
    }

    @Test
    void quartLookupsReplacePixelsAtLowBpp() throws Exception {
        // Radius 256 spans 512 blocks -> 128x128 distinct quart columns.
        CountingQuart bpp1 = new CountingQuart(plains());
        export(new TerrainExportSpec(256, 1, 0, 0, false, 1), bpp1, "q1_");
        assertEquals(128 * 128, bpp1.calls.get(), "bpp 1: one lookup per quart, not per pixel (512x512 px)");

        CountingQuart bpp2 = new CountingQuart(plains());
        export(new TerrainExportSpec(256, 2, 0, 0, false, 1), bpp2, "q2_");
        assertEquals(128 * 128, bpp2.calls.get(), "bpp 2: same 512-block span, same quarts (256x256 px)");

        CountingQuart bpp4 = new CountingQuart(plains());
        export(new TerrainExportSpec(256, 4, 0, 0, false, 1), bpp4, "q4_");
        assertEquals(128 * 128, bpp4.calls.get(), "bpp 4: memo is off but lookups already equal pixels");
    }

    @Test
    void plainSamplerStaysPerPixel() throws Exception {
        CountingPlain plain = new CountingPlain(plains());
        export(new TerrainExportSpec(256, 1, 0, 0, false, 1), plain, "plain_");
        assertEquals(512L * 512L, plain.calls.get(), "a plain sampler keeps one call per pixel");
    }

    @Test
    void oddAndNegativeExportOriginsStayInsideTheMemo() throws Exception {
        CountingQuart odd = new CountingQuart(plains());
        // Width 512/3 = 170 px; origin (745, 743) is off the quart lattice.
        // The memo is per tile, and each 64-px tile counts its own quarts:
        // X tiles 48+48+32 = 128, Z tiles 49+49+32 = 130 (the &3 phase of
        // each tile origin rounds one tile up), so 128*130 lookups.
        export(new TerrainExportSpec(256, 3, 1001, 999, false, 1), odd, "odd_");
        assertEquals(128 * 130, odd.calls.get(), "odd origins must not clip or crash the memo");

        CountingQuart negative = new CountingQuart(plains());
        // Origin -223: arithmetic quart shift floors, so the memo index stays
        // >= 0; the phase-1 origin gives every 64-px tile 17 quarts, so
        // 8 tiles per axis -> 17*8 = 136 per axis.
        export(new TerrainExportSpec(256, 1, 33, 33, false, 1), negative, "neg_");
        assertEquals(136 * 136, negative.calls.get(), "negative origins must stay inside the memo");
    }

    @Test
    void memoDoesNotChangeTheOutput() throws Exception {
        Holder<Biome> holder = plains();
        CountingQuart memo = new CountingQuart(holder);
        CountingPlain plain = new CountingPlain(holder);
        TerrainMapExporter.Result memoResult =
                export(new TerrainExportSpec(256, 1, 0, 0, false, 1), memo, "eqA_");
        TerrainMapExporter.Result plainResult =
                export(new TerrainExportSpec(256, 1, 0, 0, false, 1), plain, "eqB_");
        assertTrue(Files.exists(memoResult.pngPath()));
        assertTrue(Files.exists(plainResult.pngPath()));
        // The PNG encoders are deterministic for identical pixel buffers, so
        // byte equality pins the whole memoized pipeline against the plain one.
        assertArrayEquals(Files.readAllBytes(plainResult.pngPath()), Files.readAllBytes(memoResult.pngPath()),
                "the memo must not change a single pixel");
    }
}
