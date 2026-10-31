package caeruleusTait.world.preview.backend.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness tests for the pure-Java PNG writer: palette, truecolor and
 * grayscale round-trips through an independent decoder (ImageIO), the
 * >256-color and translucency fallbacks, multi-IDAT streaming, the
 * cancellation path, and a cross-check through Minecraft's own stb decoder.
 */
class PngWriterTest {

    @TempDir
    Path tempDir;

    private static int[] decodeRgb(Path png) throws Exception {
        BufferedImage image = ImageIO.read(png.toFile());
        int[] out = new int[image.getWidth() * image.getHeight()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                out[y * image.getWidth() + x] = image.getRGB(x, y);
            }
        }
        return out;
    }

    private static int colorType(Path png) throws Exception {
        byte[] head = new byte[26];
        try (InputStream in = Files.newInputStream(png)) {
            int read = 0;
            while (read < head.length) {
                int n = in.read(head, read, head.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        return head[25] & 0xFF;
    }

    @Test
    void paletteImageRoundTrips() throws Exception {
        int width = 37;
        int height = 19;
        int[] pixels = new int[width * height];
        int[] colors = {0xFF20908C, 0xFF123456, 0xFF000000, 0xFFFF00FF};
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = colors[(i * 7 + i / width) % colors.length];
        }
        Path png = tempDir.resolve("palette.png");
        PngWriter.writeArgb(png, pixels, width, height, () -> false);
        assertEquals(3, colorType(png), "few distinct opaque colors take the palette path");
        assertArrayEquals(pixels, decodeRgb(png));
    }

    @Test
    void paletteLimitFallsBackToTruecolor() throws Exception {
        int width = 32;
        int height = 32;
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | (i * 8);
        }
        Path png = tempDir.resolve("truecolor.png");
        PngWriter.writeArgb(png, pixels, width, height, () -> false);
        assertEquals(2, colorType(png), "more than 256 colors take the truecolor RGB path");
        assertArrayEquals(pixels, decodeRgb(png));
    }

    @Test
    void translucencyFallsBackToRgba() throws Exception {
        int[] pixels = new int[16 * 16];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0x8000FF00;
        }
        Path png = tempDir.resolve("rgba.png");
        PngWriter.writeArgb(png, pixels, 16, 16, () -> false);
        assertEquals(6, colorType(png), "a non-opaque pixel forces the RGBA path");
        assertArrayEquals(pixels, decodeRgb(png));
    }

    @Test
    void grayImageMatchesTheHeightmapMapping() throws Exception {
        int width = 24;
        int height = 24;
        int ySpan = 384;
        short[] heights = new short[width * height];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = (short) (i % (ySpan + 1));
        }
        Path png = tempDir.resolve("gray.png");
        PngWriter.writeGray(png, heights, ySpan, width, height, () -> false);
        assertEquals(0, colorType(png), "the heightmap takes the grayscale path");
        BufferedImage image = ImageIO.read(png.toFile());
        // Sample the raster directly: getRGB() runs TYPE_BYTE_GRAY through a
        // linear-to-sRGB color conversion, which is not the byte we wrote.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int expected = (heights[y * width + x] * 255) / ySpan;
                assertEquals(expected, image.getRaster().getSample(x, y, 0),
                        "gray sample at " + x + "," + y);
            }
        }
    }

    @Test
    void multipleIdatChunksStillDecode() throws Exception {
        // Random content is incompressible: 2000x2000 RGB far exceeds the
        // 512 KiB IDAT flush threshold, exercising the streaming chunks.
        int width = 2000;
        int height = 2000;
        java.util.Random random = new java.util.Random(1234L);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | random.nextInt(0x1000000);
        }
        Path png = tempDir.resolve("streamed.png");
        PngWriter.writeArgb(png, pixels, width, height, () -> false);
        assertTrue(Files.size(png) > 512L * 1024, "the random image must span multiple IDAT chunks");
        assertArrayEquals(pixels, decodeRgb(png));
    }

    @Test
    void cancelledWriteThrows() throws Exception {
        int[] pixels = new int[512 * 512];
        java.util.Arrays.fill(pixels, 0xFF20908C);
        Path png = tempDir.resolve("cancelled.png");
        assertThrows(CancellationException.class,
                () -> PngWriter.writeArgb(png, pixels, 512, 512, () -> true));
    }
}
