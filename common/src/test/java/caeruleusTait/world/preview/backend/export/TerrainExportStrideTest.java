package caeruleusTait.world.preview.backend.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Height-sampling-stride tests: the batch probe receives a coarse grid at
 * {@code bpp * stride}, exact grid nodes keep the stride-1 heights, only the
 * nodes count as real heights in the metadata, and out-of-range strides are
 * rejected by the spec.
 */
class TerrainExportStrideTest {

    @TempDir
    Path tempDir;

    private static final int Y_MIN = -64;
    private static final int Y_MAX = 320;

    /** Records every fillGrid call: (originX, originZ, step, width, height). */
    private static final class RecordingProbe implements TerrainMapExporter.BatchHeightProbe {
        final List<int[]> calls = new ArrayList<>();

        @Override
        public void fillGrid(int originX, int originZ, int step, int width, int height, int[] out,
                             java.util.function.BooleanSupplier cancelled) {
            calls.add(new int[]{originX, originZ, step, width, height});
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    int x = originX + col * step;
                    int z = originZ + row * step;
                    out[row * width + col] = 100 + x / 16 + z / 16;
                }
            }
        }

        @Override
        public Integer heightAt(int blockX, int blockZ) {
            return 100 + blockX / 16 + blockZ / 16;
        }
    }

    private Path exportContours(int stride, RecordingProbe probe, String prefix) throws Exception {
        TerrainMapExporter exporter = new TerrainMapExporter(2);
        TerrainExportSpec spec = new TerrainExportSpec(256, 16, 0, 0, true, 10, 0, false,
                TerrainExportColorMode.CATEGORY, 64, stride);
        return exporter.export(spec, (blockX, blockZ) -> null,
                TerrainMapExporter.categories(null), probe, null, null,
                Y_MIN, Y_MAX, tempDir, prefix, () -> false, p -> { }).pngPath();
    }

    @Test
    void coarseGridIsRequestedAtTheStride() throws Exception {
        RecordingProbe probe = new RecordingProbe();
        exportContours(2, probe, "s2_");
        assertEquals(1, probe.calls.size(), "one 32x32-pixel tile is one fillGrid call");
        int[] call = probe.calls.get(0);
        assertEquals(32, call[2], "step doubles to bpp * stride");
        assertEquals(16, call[3], "width covers every 2nd pixel");
        assertEquals(16, call[4], "height covers every 2nd pixel");

        RecordingProbe probe4 = new RecordingProbe();
        exportContours(4, probe4, "s4_");
        assertEquals(64, probe4.calls.get(0)[2], "step quadruples to bpp * stride");
        assertEquals(8, probe4.calls.get(0)[3]);
    }

    @Test
    void onlyExactNodesCountAsRealHeights() throws Exception {
        RecordingProbe probe1 = new RecordingProbe();
        Path png1 = exportContours(1, probe1, "r1_");
        assertTrue(Files.readString(metadataFor(png1)).contains("\"heightSource\": \"real\""),
                "stride 1: every pixel is a real height");

        RecordingProbe probe2 = new RecordingProbe();
        Path png2 = exportContours(2, probe2, "r2_");
        assertTrue(Files.readString(metadataFor(png2)).contains("\"heightSource\": \"mixed\""),
                "stride 2: interpolated pixels are not real heights");
        assertTrue(Files.readString(metadataFor(png2)).contains("\"realHeightPixels\": 256"),
                "16x16 grid nodes of a 32x32 tile count as real");
    }

    @Test
    void strideIsRecordedInTheMetadata() throws Exception {
        RecordingProbe probe = new RecordingProbe();
        Path png = exportContours(4, probe, "m4_");
        assertTrue(Files.readString(metadataFor(png)).contains("\"heightSampleStride\": 4"));
    }

    @Test
    void outOfRangeStridesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainExportSpec(256, 16, 0, 0, false, 1, 0, false,
                        TerrainExportColorMode.CATEGORY, 64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainExportSpec(256, 16, 0, 0, false, 1, 0, false,
                        TerrainExportColorMode.CATEGORY, 64, 5));
    }

    @Test
    void nonNodePixelsCarryTheInterpolatedHeightNotTheEstimate() throws Exception {
        // Linear plane probe: worldY = 120 + blockX/16 + blockZ/16. At bpp 16
        // with minBlock (-256,-256) that is 88 + px + pz, so stride-2 node
        // (0,0) holds 88, node (2,0) holds 90, and pixel (1,0) must carry the
        // interpolated 89. The UNKNOWN-category estimate is 70: writing the
        // estimate at non-node pixels instead embeds biome-constant plateaus
        // under real node values, and every node/plateau gap straddles a minor
        // contour level -- the lattice-locked checkerboard of spurious lines.
        TerrainMapExporter exporter = new TerrainMapExporter(2);
        TerrainExportSpec spec = new TerrainExportSpec(256, 16, 0, 0, true, 10, 0, true,
                TerrainExportColorMode.CATEGORY, 64, 2);
        Path png = exporter.export(spec, (blockX, blockZ) -> null,
                TerrainMapExporter.categories(null), new PlaneProbe(), null, null,
                Y_MIN, Y_MAX, tempDir, "ip_", () -> false, p -> { }).pngPath();
        Path heightPng = png.resolveSibling(
                png.getFileName().toString().replace(".png", "_height.png"));
        BufferedImage image = ImageIO.read(heightPng.toFile());
        assertEquals(100, sample(image, 0, 0), "node (0,0) keeps the real 88 (offset 152)");
        assertEquals(101, sample(image, 1, 0),
                "non-node (1,0) carries the interpolated 89 (offset 153), not the estimate 70");
        assertEquals(104, sample(image, 3, 3),
                "cell center (3,3) is the bilinear mean of its four nodes");
    }

    /** worldY = 120 + blockX/16 + blockZ/16: a linear plane. */
    private static final class PlaneProbe implements TerrainMapExporter.BatchHeightProbe {
        @Override
        public void fillGrid(int originX, int originZ, int step, int width, int height, int[] out,
                             java.util.function.BooleanSupplier cancelled) {
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    out[row * width + col] = 120 + (originX + col * step) / 16 + (originZ + row * step) / 16;
                }
            }
        }

        @Override
        public Integer heightAt(int blockX, int blockZ) {
            return 120 + blockX / 16 + blockZ / 16;
        }
    }

    private static int sample(BufferedImage image, int x, int y) {
        return image.getRaster().getSample(x, y, 0);
    }

    private static Path metadataFor(Path png) {
        return png.resolveSibling(png.getFileName().toString().replace(".png", ".json"));
    }
}
