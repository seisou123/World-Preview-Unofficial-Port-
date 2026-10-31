package caeruleusTait.world.preview.backend.export;

import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-tile style memo tests: a canonical holder resolves its fused style
 * (color + bucket + estimate) once per tile no matter how many pixels share
 * it, while non-canonical holders -- which cannot hit the identity memo --
 * still resolve per pixel with the correct result.
 */
class TerrainExportStyleMemoTest {

    @TempDir
    Path tempDir;

    private static final int Y_MIN = -64;
    private static final int Y_MAX = 320;

    private static Holder<Biome> plains() {
        return Holder.Reference.createStandAlone(
                new HolderOwner<Biome>() {},
                ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:plains")));
    }

    /** Delegating source that counts resolveHolder invocations. */
    private static final class CountingSource implements TerrainMapExporter.TerrainColorSource {
        private final TerrainMapExporter.TerrainColorSource delegate;
        int resolveHolderCalls;

        CountingSource(TerrainMapExporter.TerrainColorSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public int colorForId(short id) {
            return delegate.colorForId(id);
        }

        @Override
        public int bucketForId(short id) {
            return delegate.bucketForId(id);
        }

        @Override
        public int colorForHolder(Holder<Biome> holder) {
            return delegate.colorForHolder(holder);
        }

        @Override
        public int bucketForHolder(Holder<Biome> holder) {
            return delegate.bucketForHolder(holder);
        }

        @Override
        public long resolveHolder(Holder<Biome> holder) {
            resolveHolderCalls++;
            return delegate.resolveHolder(holder);
        }

        @Override
        public int bucketCount() {
            return delegate.bucketCount();
        }

        @Override
        public String modeName() {
            return delegate.modeName();
        }

        @Override
        public void writeLegend(JsonObject metadata, long[] statCounts) {
            delegate.writeLegend(metadata, statCounts);
        }
    }

    @Test
    void canonicalHolderResolvesOncePerTile() throws Exception {
        CountingSource counting = new CountingSource(TerrainMapExporter.categories(null));
        TerrainMapExporter exporter = new TerrainMapExporter(2);
        Holder<Biome> plains = plains();
        TerrainMapExporter.Result result = exporter.export(
                new TerrainExportSpec(256, 16, 0, 0, false, 1),
                (blockX, blockZ) -> plains, counting, null, null, null,
                Y_MIN, Y_MAX, tempDir, "style_", () -> false, p -> { });
        assertEquals(1, counting.resolveHolderCalls,
                "one canonical holder across a single 32x32 tile resolves once");
        assertTrue(Files.readString(result.metadataPath()).contains("\"PLAINS\": 1024"),
                "pixels keep the plains classification");
    }

    @Test
    void nonCanonicalHoldersResolvePerPixelAndStayCorrect() throws Exception {
        CountingSource counting = new CountingSource(TerrainMapExporter.categories(null));
        TerrainMapExporter exporter = new TerrainMapExporter(2);
        TerrainMapExporter.Result result = exporter.export(
                new TerrainExportSpec(256, 16, 0, 0, false, 1),
                (blockX, blockZ) -> plains(), counting, null, null, null,
                Y_MIN, Y_MAX, tempDir, "fresh_", () -> false, p -> { });
        assertEquals(1024, counting.resolveHolderCalls,
                "a fresh holder per pixel cannot hit the identity memo");
        assertTrue(Files.readString(result.metadataPath()).contains("\"PLAINS\": 1024"),
                "misses still classify correctly");
    }
}
