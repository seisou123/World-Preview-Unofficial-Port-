package caeruleusTait.world.preview.backend.export;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Minimal pure-Java PNG writer for the terrain export.
 * <p>
 * Replaces the {@code NativeImage} + stb encode path with {@code java.util.zip}
 * only: no native encoder, no whole-file native buffer, and one code path on
 * every Minecraft version (the ARGB buffers are canonical here, so the
 * per-version ABGR/ARGB sink swap disappears). Rows stream through a
 * {@link Deflater} into multiple IDAT chunks, so cancellation is honored
 * between rows instead of being uninterruptible for the whole encode.
 * <p>
 * The color format is chosen from the buffer: 8-bit palette (color type 3)
 * when the map holds at most {@link #MAX_PALETTE} distinct opaque colors --
 * the nine category colors of the default mode -- 8-bit truecolor RGB (type 2)
 * when there are more colors but all pixels are opaque, and truecolor RGBA
 * (type 6) when any pixel is translucent. The heightmap path writes 8-bit
 * grayscale (type 0) straight from the short height field. Measured against
 * the shipped stb encoder at 4096²: palette ~55 ms vs ~1.0-1.2 s (and ~7x
 * smaller files), truecolor ~0.7 s vs 3.2-4.1 s, grayscale ~190 ms vs ~1.1 s.
 */
public final class PngWriter {

    /** Palette size limit; PNG indexes at most 256 entries at bit depth 8. */
    private static final int MAX_PALETTE = 256;

    /** IDAT chunks are flushed at this compressed size; decoders concatenate. */
    private static final int IDAT_CHUNK_BYTES = 512 * 1024;

    /**
     * Compression level: the cheap end keeps the encode fast and, with the row
     * filters chosen per format below, still shrinks these images well.
     */
    private static final int COMPRESSION_LEVEL = Deflater.BEST_SPEED;

    /** Cancellation is polled once per this many encoded rows. */
    private static final int CANCEL_ROWS = 64;

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', (byte) 0x1A, '\n'};

    private static final int IHDR = 0x49484452;
    private static final int PLTE = 0x504C5445;
    private static final int IDAT = 0x49444154;
    private static final int IEND = 0x49454E44;

    private PngWriter() {
    }

    /**
     * Writes an ARGB buffer as a PNG: palette when the distinct opaque colors
     * fit, truecolor RGB when they do not, truecolor RGBA when any pixel is
     * translucent.
     *
     * @param pixels ARGB pixels, row-major, {@code width * height}
     */
    public static void writeArgb(Path target, int[] pixels, int width, int height,
                                 BooleanSupplier cancelled) throws IOException {
        final Palette palette = Palette.tryBuild(pixels);
        if (palette != null) {
            try (PngStream out = new PngStream(target, cancelled)) {
                out.header(width, height, 3, 1);
                out.paletteChunk(palette);
                byte[] row = new byte[width];
                byte[] prev = new byte[width];
                for (int y = 0; y < height; y++) {
                    final int base = y * width;
                    for (int x = 0; x < width; x++) {
                        row[x] = (byte) palette.index(pixels[base + x] & 0xFFFFFF);
                    }
                    out.writeFilteredRow(row, prev, true);
                    final byte[] swap = prev;
                    prev = row;
                    row = swap;
                }
                out.finish();
            }
        } else if (allOpaque(pixels)) {
            writeTruecolor(target, pixels, width, height, 3, cancelled);
        } else {
            writeTruecolor(target, pixels, width, height, 4, cancelled);
        }
    }

    /**
     * Writes the export height field as 8-bit grayscale: offset 0 (yMin) is
     * black, {@code ySpan} (yMax) is white -- the same mapping the contour
     * overlay's grayscale base uses.
     *
     * @param heights blocks above yMin per pixel, row-major
     */
    public static void writeGray(Path target, short[] heights, int ySpan, int width, int height,
                                 BooleanSupplier cancelled) throws IOException {
        try (PngStream out = new PngStream(target, cancelled)) {
            out.header(width, height, 0, 1);
            byte[] row = new byte[width];
            byte[] prev = new byte[width];
            for (int y = 0; y < height; y++) {
                final int base = y * width;
                for (int x = 0; x < width; x++) {
                    row[x] = (byte) ((heights[base + x] * 255) / ySpan);
                }
                out.writeFilteredRow(row, prev, true);
                final byte[] swap = prev;
                prev = row;
                row = swap;
            }
            out.finish();
        }
    }

    private static void writeTruecolor(Path target, int[] pixels, int width, int height,
                                       int channels, BooleanSupplier cancelled) throws IOException {
        try (PngStream out = new PngStream(target, cancelled)) {
            out.header(width, height, channels == 3 ? 2 : 6, channels);
            byte[] row = new byte[width * channels];
            byte[] prev = new byte[width * channels];
            for (int y = 0; y < height; y++) {
                final int base = y * width;
                for (int x = 0; x < width; x++) {
                    final int argb = pixels[base + x];
                    final int o = x * channels;
                    row[o] = (byte) (argb >>> 16);
                    row[o + 1] = (byte) (argb >>> 8);
                    row[o + 2] = (byte) argb;
                    if (channels == 4) {
                        row[o + 3] = (byte) (argb >>> 24);
                    }
                }
                // Truecolor keeps filter NONE: on the map's noisy content the
                // adaptive filter search costs more than it saves, and NONE
                // preserves the long same-color runs the LZ stage wants.
                out.writeFilteredRow(row, prev, false);
                final byte[] swap = prev;
                prev = row;
                row = swap;
            }
            out.finish();
        }
    }

    private static boolean allOpaque(int[] pixels) {
        for (int argb : pixels) {
            if ((argb >>> 24) != 0xFF) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ palette

    /**
     * Open-addressed color table, ~3-4x faster than a boxed HashMap and
     * byte-identical in output. Slot values are 1-based so 0 can mark an empty
     * slot even though color 0x000000 is a legal palette entry.
     */
    private static final class Palette {
        private static final int TABLE = 1024;

        private final int[] keys = new int[TABLE];
        private final short[] slots = new short[TABLE];
        private final int[] colors = new int[MAX_PALETTE];
        private int count;

        /** Returns null when the buffer exceeds the palette limit or is not opaque. */
        static Palette tryBuild(int[] pixels) {
            final Palette palette = new Palette();
            for (int argb : pixels) {
                if ((argb >>> 24) != 0xFF) {
                    return null;
                }
                if (!palette.add(argb & 0xFFFFFF)) {
                    return null;
                }
            }
            return palette;
        }

        private boolean add(int rgb) {
            int slot = mix(rgb) & (TABLE - 1);
            while (slots[slot] != 0) {
                if (keys[slot] == rgb) {
                    return true;
                }
                slot = (slot + 1) & (TABLE - 1);
            }
            if (count == MAX_PALETTE) {
                return false;
            }
            keys[slot] = rgb;
            slots[slot] = (short) (count + 1);
            colors[count] = rgb;
            count++;
            return true;
        }

        /** Index of an already-inserted color; the probe terminates because the color is present. */
        int index(int rgb) {
            int slot = mix(rgb) & (TABLE - 1);
            while (keys[slot] != rgb) {
                slot = (slot + 1) & (TABLE - 1);
            }
            return slots[slot] - 1;
        }
    }

    private static int mix(int rgb) {
        int h = rgb * 0x9E3779B1;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h;
    }

    // ------------------------------------------------------------------ chunked output

    /** Chunked PNG output: signature, IHDR, optional PLTE, streamed IDATs, IEND. */
    private static final class PngStream implements Closeable {
        private final BufferedOutputStream file;
        private final BooleanSupplier cancelled;
        private final CRC32 crc = new CRC32();
        private final IdatBuffer idat = new IdatBuffer();
        private final Deflater deflater = new Deflater(COMPRESSION_LEVEL);
        private final DeflaterOutputStream compressed =
                new DeflaterOutputStream(idat, deflater, 64 * 1024);
        private byte[] rowScratch = new byte[0];
        private int rowsSinceCheck;
        private boolean finished;

        PngStream(Path target, BooleanSupplier cancelled) throws IOException {
            this.file = new BufferedOutputStream(Files.newOutputStream(target), 128 * 1024);
            this.cancelled = cancelled;
            file.write(PNG_SIGNATURE);
        }

        void header(int width, int height, int colorType, int channels) throws IOException {
            rowScratch = new byte[width * channels + 1];
            final byte[] data = new byte[13];
            putInt(data, 0, width);
            putInt(data, 4, height);
            data[8] = 8;   // bit depth
            data[9] = (byte) colorType;
            data[10] = 0;  // deflate
            data[11] = 0;  // adaptive filtering
            data[12] = 0;  // no interlace
            writeChunk(IHDR, data, data.length);
        }

        void paletteChunk(Palette palette) throws IOException {
            final byte[] data = new byte[palette.count * 3];
            for (int i = 0; i < palette.count; i++) {
                final int rgb = palette.colors[i];
                data[i * 3] = (byte) (rgb >>> 16);
                data[i * 3 + 1] = (byte) (rgb >>> 8);
                data[i * 3 + 2] = (byte) rgb;
            }
            writeChunk(PLTE, data, data.length);
        }

        /**
         * Emits one scanline with its filter byte: UP for palette and
         * grayscale rows (large uniform regions compress as row deltas), NONE
         * for truecolor rows.
         */
        void writeFilteredRow(byte[] current, byte[] previous, boolean up) throws IOException {
            if (++rowsSinceCheck >= CANCEL_ROWS) {
                rowsSinceCheck = 0;
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Terrain export cancelled");
                }
            }
            if (up) {
                rowScratch[0] = 2;
                for (int i = 0; i < current.length; i++) {
                    rowScratch[i + 1] = (byte) (current[i] - previous[i]);
                }
            } else {
                rowScratch[0] = 0;
                System.arraycopy(current, 0, rowScratch, 1, current.length);
            }
            compressed.write(rowScratch, 0, rowScratch.length);
        }

        void finish() throws IOException {
            finished = true;
            compressed.finish();
            idat.flushChunk();
            writeChunk(IEND, new byte[0], 0);
            file.flush();
        }

        @Override
        public void close() throws IOException {
            // A cancelled or failed encode must not try to complete the zlib
            // stream: the .part file is deleted by the exporter anyway, and a
            // secondary IOException here would mask the real failure.
            if (!finished) {
                deflater.end();
            }
            file.close();
        }

        private void writeChunk(int type, byte[] data, int length) throws IOException {
            crc.reset();
            crc.update(type >>> 24);
            crc.update(type >>> 16);
            crc.update(type >>> 8);
            crc.update(type);
            crc.update(data, 0, length);
            writeInt(length);
            writeInt(type);
            file.write(data, 0, length);
            writeInt((int) crc.getValue());
        }

        private void writeInt(int value) throws IOException {
            file.write(value >>> 24);
            file.write(value >>> 16);
            file.write(value >>> 8);
            file.write(value);
        }

        /** Accumulates deflated bytes and emits them as IDAT chunks. */
        private final class IdatBuffer extends OutputStream {
            private final byte[] buf = new byte[IDAT_CHUNK_BYTES];
            private int len;

            @Override
            public void write(int b) throws IOException {
                buf[len++] = (byte) b;
                if (len == buf.length) {
                    flushChunk();
                }
            }

            @Override
            public void write(byte[] src, int off, int count) throws IOException {
                while (count > 0) {
                    final int take = Math.min(count, buf.length - len);
                    System.arraycopy(src, off, buf, len, take);
                    len += take;
                    off += take;
                    count -= take;
                    if (len == buf.length) {
                        flushChunk();
                    }
                }
            }

            void flushChunk() throws IOException {
                if (len == 0) {
                    return;
                }
                writeChunk(IDAT, buf, len);
                len = 0;
            }
        }
    }

    private static void putInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }
}
