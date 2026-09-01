// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.storage;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import caeruleusTait.world.preview.backend.analysis.Region;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static caeruleusTait.world.preview.backend.WorkManager.Y_BLOCK_SHIFT;

public class PreviewStorage {

    public static final long FLAG_BITS = 4;
    public static final long FLAG_MASK = (1L << FLAG_BITS) - 1L;

    public static final long XZ_BITS = 30;
    public static final long XZ_MASK = (1L << XZ_BITS) - 1L;
    public static final long XZ_OFFSET = 1L << (XZ_BITS - 1);

    public static final long FLAG_SHIFT = 0L;
    public static final long Z_SHIFT = FLAG_SHIFT + FLAG_BITS;
    public static final long X_SHIFT = Z_SHIFT + XZ_BITS;

    public static final long FLAG_BIOME = 0b0000;
    public static final long FLAG_STRUCT_START = 0b0001;
    public static final long FLAG_HEIGHT = 0b0010;
    public static final long FLAG_INTERSECT = 0b0011;
    public static final long FLAG_NOISE = 0b1000;
    public static final long FLAG_NOISE_TEMPERATURE = 0b1001;
    public static final long FLAG_NOISE_HUMIDITY = 0b1010;
    public static final long FLAG_NOISE_CONTINENTALNESS = 0b1011;
    public static final long FLAG_NOISE_EROSION = 0b1100;
    public static final long FLAG_NOISE_DEPTH = 0b1101;
    public static final long FLAG_NOISE_WEIRDNESS = 0b1110;
    public static final long FLAG_STRUCT_REF = 0b0100;

    private Long2ObjectMap<PreviewBlock>[] blocks;

    private final int yMin;
    private final int yMax;

    // Incremented whenever worker threads write biome/structure data.
    // The render thread compares this value between frames to detect
    // whether a re-render is necessary (skip the expensive generateRenderData
    // + updateTexture + upload cycle when nothing changed).
    private AtomicLong writeCounter;

    @SuppressWarnings("unchecked")
    public PreviewStorage(int yMin, int yMax) {
        blocks = new Long2ObjectMap[((yMax - yMin) >> Y_BLOCK_SHIFT) + 1];
        for (int i = 0; i < blocks.length; ++i) {
            blocks[i] = new Long2ObjectOpenHashMap<>(1024, Hash.FAST_LOAD_FACTOR);
        }
        this.yMin = yMin;
        this.yMax = yMax;
        writeCounter = new AtomicLong(0);
    }

    public PreviewSection section4(BlockPos bp, long flags) {
        final int quartX = QuartPos.fromBlock(bp.getX());
        final int indexY = requireIndexY(bp.getY());
        final int quartZ = QuartPos.fromBlock(bp.getZ());
        final PreviewBlock block;
        synchronized (blocks[indexY]) {
            block = blocks[indexY].computeIfAbsent(quartPosToSectionLong(quartX, quartZ, flags), x -> new PreviewBlock(flags));
        }
        return block.get(quartX, quartZ);
    }

    public PreviewSection section4(ChunkPos chunkPos, int y, long flags) {
        final int quartX = QuartPos.fromSection(chunkPos.x());
        final int indexY = requireIndexY(y);
        final int quartZ = QuartPos.fromSection(chunkPos.z());
        final PreviewBlock block;
        synchronized (blocks[indexY]) {
            block = blocks[indexY].computeIfAbsent(quartPosToSectionLong(quartX, quartZ, flags), x -> new PreviewBlock(flags));
        }
        return block.get(quartX, quartZ);
    }

    public PreviewSection section4(int quartX, int quartY, int quartZ, long flags) {
        final int indexY = requireIndexY(QuartPos.toBlock(quartY));
        final PreviewBlock block;
        synchronized (blocks[indexY]) {
            block = blocks[indexY].computeIfAbsent(quartPosToSectionLong(quartX, quartZ, flags), x -> new PreviewBlock(flags));
        }
        return block.get(quartX, quartZ);
    }

    /**
     * Returns {@link Short#MIN_VALUE} when not found. Only use this when querying a single position!
     */
    public short getBiome4(BlockPos bp) {
        final int quartX = QuartPos.fromBlock(bp.getX());
        final int quartY = QuartPos.fromBlock(bp.getY());
        final int quartZ = QuartPos.fromBlock(bp.getZ());
        return getRawData4(quartX, quartY, quartZ, FLAG_BIOME);
    }

    public record SamplePoint(int x, int z, short value) {}

    public record RegionSamples(Region region, int y, long flags, List<SamplePoint> points,
                                long expectedPointCount, long presentPointCount) {
        public RegionSamples {
            points = List.copyOf(points);
        }

        public double coverage() {
            return expectedPointCount == 0 ? 1.0 : (double) presentPointCount / expectedPointCount;
        }
    }

    public RegionSamples readRegion(Region region, int y, long flags, int step) {
        validateStep(step);
        List<SamplePoint> points = new ArrayList<>();
        long expected = 0;
        long present = 0;
        for (int x = region.minX(); ; x = nextCoordinate(x, step, region.maxX())) {
            for (int z = region.minZ(); ; z = nextCoordinate(z, step, region.maxZ())) {
                short value = readBlockSample(x, z, y, flags);
                points.add(new SamplePoint(x, z, value));
                expected++;
                if (value != Short.MIN_VALUE) present++;
                if (z == region.maxZ()) break;
            }
            if (x == region.maxX()) break;
        }
        return new RegionSamples(region, y, flags, points, expected, present);
    }

    public List<SamplePoint> readProfile(int x1, int z1, int x2, int z2, int y, long flags, int step) {
        validateStep(step);
        List<SamplePoint> points = new ArrayList<>();
        long dx = (long) x2 - x1;
        long dz = (long) z2 - z1;
        long distance = Math.max(Math.abs(dx), Math.abs(dz));
        if (distance == 0) {
            points.add(new SamplePoint(x1, z1, readBlockSample(x1, z1, y, flags)));
        } else {
            for (long offset = 0; offset < distance; offset += step) {
                int x = interpolateCoordinate(x1, dx, offset, distance);
                int z = interpolateCoordinate(z1, dz, offset, distance);
                points.add(new SamplePoint(x, z, readBlockSample(x, z, y, flags)));
            }
            points.add(new SamplePoint(x2, z2, readBlockSample(x2, z2, y, flags)));
        }
        return Collections.unmodifiableList(points);
    }

    private static int interpolateCoordinate(int start, long delta, long offset, long distance) {
        return Math.toIntExact(Math.round(start + (double) delta * offset / distance));
    }

    private short readBlockSample(int x, int z, int y, long flags) {
        return getRawData4(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), flags);
    }

    private static void validateStep(int step) {
        if (step < 1) throw new IllegalArgumentException("step must be >= 1");
    }

    private static int nextCoordinate(int current, int step, int max) {
        long next = (long) current + step;
        return next >= max ? max : (int) next;
    }

    private static long countOrOne(long count) { return count == 0 ? 1 : count; }

    /**
     * Returns {@link Short#MIN_VALUE} when not found. Only use this when querying a single position!
     */
    public short getRawData4(int quartX, int quartY, int quartZ, long flags) {
        final int indexY = indexYOrNegative(QuartPos.toBlock(quartY));
        if (indexY < 0) {
            return Short.MIN_VALUE;
        }
        final PreviewBlock block;
        synchronized (blocks[indexY]) {
            block = blocks[indexY].get(quartPosToSectionLong(quartX, quartZ, flags));
        }
        if (block == null) {
            return Short.MIN_VALUE;
        }
        final PreviewSection section = block.get(quartX, quartZ);
        return section.get(quartX - section.quartX(), quartZ - section.quartZ());
    }

    /**
     * Read-only probe: {@code true} when the chunk containing the given quart
     * position has completed sampling for the given flag layer.  Unlike
     * {@link #getRawData4} this never creates blocks/sections and can
     * distinguish "never written" from "written zero" (raw section cells
     * default to 0, which is a valid biome id).
     * <p>
     * Used by the render side to detect visible-but-unloaded areas.
     */
    public boolean isChunkSampled(int quartX, int quartY, int quartZ, long flags) {
        final int indexY = indexYOrNegative(QuartPos.toBlock(quartY));
        if (indexY < 0) {
            return false;
        }
        final PreviewBlock block;
        synchronized (blocks[indexY]) {
            block = blocks[indexY].get(quartPosToSectionLong(quartX, quartZ, flags));
        }
        if (block == null) {
            return false;
        }
        // getIfExists (not get) keeps this probe allocation-free; a benign
        // stale-null read only ever reports "unsampled", which is safe.
        final PreviewSection section = block.getIfExists(quartX, quartZ);
        if (section == null) {
            return false;
        }
        return section.isCompleted(new ChunkPos(
                quartX >> PreviewSection.QUART_TO_SECTION_SHIFT,
                quartZ >> PreviewSection.QUART_TO_SECTION_SHIFT
        ));
    }

    /**
     * Batch query: fetch biome, height, and all 6 noise channels for a single
     * quart position in a single synchronized block, avoiding 8 separate
     * lock acquire/release cycles.
     *
     * @return a short[8] array: [biome, height, temperature, humidity,
     *         continentalness, erosion, depth, weirdness].
     *         Each element is {@link Short#MIN_VALUE} when not found.
     */
    public short[] getBatchRawData4(int quartX, int quartY, int quartZ) {
        final short[] result = new short[8];
        java.util.Arrays.fill(result, Short.MIN_VALUE);

        final int blockIndexY = indexYOrNegative(QuartPos.toBlock(quartY));
        final int heightIndexY = indexYOrNegative(0); // height uses y=0

        // Query biome from the y-layer
        if (blockIndexY >= 0) {
            synchronized (blocks[blockIndexY]) {
                final PreviewBlock biomeBlock = blocks[blockIndexY].get(quartPosToSectionLong(quartX, quartZ, FLAG_BIOME));
                if (biomeBlock != null) {
                    final PreviewSection section = biomeBlock.get(quartX, quartZ);
                    result[0] = section.get(quartX - section.quartX(), quartZ - section.quartZ());
                }
            }
        }

        // Query height (uses y=0 layer)
        if (heightIndexY >= 0) {
            synchronized (blocks[heightIndexY]) {
                final PreviewBlock heightBlock = blocks[heightIndexY].get(quartPosToSectionLong(quartX, quartZ, FLAG_HEIGHT));
                if (heightBlock != null) {
                    final PreviewSection section = heightBlock.get(quartX, quartZ);
                    result[1] = section.get(quartX - section.quartX(), quartZ - section.quartZ());
                }
            }
        }

        // Query noise channels (same y-layer as biome)
        if (blockIndexY >= 0) {
            synchronized (blocks[blockIndexY]) {
                final long[] noiseFlags = {
                    FLAG_NOISE_TEMPERATURE, FLAG_NOISE_HUMIDITY, FLAG_NOISE_CONTINENTALNESS,
                    FLAG_NOISE_EROSION, FLAG_NOISE_DEPTH, FLAG_NOISE_WEIRDNESS
                };
                for (int i = 0; i < noiseFlags.length; i++) {
                    final PreviewBlock noiseBlock = blocks[blockIndexY].get(quartPosToSectionLong(quartX, quartZ, noiseFlags[i]));
                    if (noiseBlock != null) {
                        final PreviewSection section = noiseBlock.get(quartX, quartZ);
                        result[2 + i] = section.get(quartX - section.quartX(), quartZ - section.quartZ());
                    }
                }
            }
        }

        return result;
    }

    /** Maps block Y to layer index, or -1 when outside the storage range (safe for read paths). */
    private int indexYOrNegative(int blockY) {
        final int indexY = (blockY - yMin) >> Y_BLOCK_SHIFT;
        if (indexY < 0 || indexY >= blocks.length) {
            return -1;
        }
        return indexY;
    }

    /** Maps block Y to layer index; fails fast for worker write paths with invalid Y. */
    private int requireIndexY(int blockY) {
        final int indexY = (blockY - yMin) >> Y_BLOCK_SHIFT;
        if (indexY < 0 || indexY >= blocks.length) {
            throw new IllegalArgumentException(
                    "Y index out of range: blockY=" + blockY + ", yMin=" + yMin + ", yMax=" + yMax
                            + ", indexY=" + indexY + ", layers=" + blocks.length);
        }
        return indexY;
    }

    public static long blockPos2SectionLong(BlockPos bp, long flags) {
        return quartPosToSectionLong(QuartPos.fromBlock(bp.getX()), QuartPos.fromBlock(bp.getZ()), flags);
    }

    public static long quartPosToSectionLong(long quartX, long quartZ, long flags) {
        final long sX = quartX >> (PreviewSection.SHIFT + PreviewBlock.PREVIEW_BLOCK_SHIFT);
        final long sZ = quartZ >> (PreviewSection.SHIFT + PreviewBlock.PREVIEW_BLOCK_SHIFT);
        return (sX & XZ_MASK) << X_SHIFT | (sZ & XZ_MASK) << Z_SHIFT | (flags & FLAG_MASK) << FLAG_SHIFT;
    }

    public static long compressXYZ(long x, long z, long flags) {
        return (x & XZ_MASK) << X_SHIFT | (z & XZ_MASK) << Z_SHIFT | (flags & FLAG_MASK) << FLAG_SHIFT;
    }

    /**
     * Returns the current write counter.  The render thread uses this to detect
     * whether any worker thread has written new data since the last frame.
     */
    public long writeCounter() {
        return writeCounter.get();
    }

    /**
     * Called by worker threads after writing biome/structure data.
     */
    public void notifyWrite() {
        writeCounter.incrementAndGet();
    }

    public List<Short> compressionStatistics() {
        List<Short> res = new ArrayList<>();
        for (var x : blocks) {
            for (PreviewBlock block : x.values()) {
                for (PreviewSection section : block.sectionsRaw()) {
                    if (!(section instanceof PreviewSectionCompressed cSection)) {
                        continue;
                    }
                    res.add(cSection.mapSize());
                }
            }
        }
        return res;
    }

    /** Magic for the safe binary cache payload ('WPv2'). */
    public static final int BINARY_CACHE_MAGIC = 0x57507632;

    /**
     * Writes this storage using the non-Java-serialization binary format.
     * Format: magic, version, yMin, yMax, layerCount, then per non-empty layer.
     */
    public void writeBinary(DataOutputStream out) throws IOException {
        out.writeInt(BINARY_CACHE_MAGIC);
        out.writeInt(PreviewStorageCacheManager.CACHE_FORMAT_VERSION);
        out.writeInt(yMin);
        out.writeInt(yMax);

        int layerCount = 0;
        for (Long2ObjectMap<PreviewBlock> layer : blocks) {
            synchronized (layer) {
                if (!layer.isEmpty()) {
                    layerCount++;
                }
            }
        }
        out.writeInt(layerCount);

        for (int yIndex = 0; yIndex < blocks.length; yIndex++) {
            final Long2ObjectMap<PreviewBlock> layer = blocks[yIndex];
            final long[] keys;
            final PreviewBlock[] values;
            synchronized (layer) {
                if (layer.isEmpty()) {
                    continue;
                }
                final var entrySet = layer.long2ObjectEntrySet();
                keys = new long[entrySet.size()];
                values = new PreviewBlock[entrySet.size()];
                int i = 0;
                for (var entry : entrySet) {
                    keys[i] = entry.getLongKey();
                    values[i] = entry.getValue();
                    i++;
                }
            }
            out.writeInt(yIndex);
            out.writeInt(keys.length);
            for (int i = 0; i < keys.length; i++) {
                out.writeLong(keys[i]);
                values[i].writeBinary(out);
            }
        }
    }

    /**
     * Reads storage from the safe binary format. Throws {@link IOException} on
     * magic/version/y-range mismatch or truncated data.
     */
    public static PreviewStorage readBinary(DataInputStream in, int expectedYMin, int expectedYMax) throws IOException {
        final int magic = in.readInt();
        if (magic != BINARY_CACHE_MAGIC) {
            throw new IOException("Bad preview cache magic: 0x" + Integer.toHexString(magic));
        }
        final int version = in.readInt();
        if (version != PreviewStorageCacheManager.CACHE_FORMAT_VERSION) {
            throw new IOException("Unsupported preview cache version: " + version);
        }
        final int yMin = in.readInt();
        final int yMax = in.readInt();
        if (yMin != expectedYMin || yMax != expectedYMax) {
            throw new IOException("Preview cache y-range mismatch: got [" + yMin + "," + yMax
                    + "] expected [" + expectedYMin + "," + expectedYMax + "]");
        }

        final PreviewStorage storage = new PreviewStorage(yMin, yMax);
        final int layerCount = in.readInt();
        if (layerCount < 0 || layerCount > storage.blocks.length) {
            throw new IOException("Invalid layerCount: " + layerCount);
        }

        for (int i = 0; i < layerCount; i++) {
            final int yIndex = in.readInt();
            if (yIndex < 0 || yIndex >= storage.blocks.length) {
                throw new IOException("Invalid yIndex: " + yIndex);
            }
            final int entryCount = in.readInt();
            if (entryCount < 0) {
                throw new IOException("Invalid entryCount: " + entryCount);
            }
            final Long2ObjectMap<PreviewBlock> layer = storage.blocks[yIndex];
            synchronized (layer) {
                for (int j = 0; j < entryCount; j++) {
                    final long key = in.readLong();
                    final long flags = (key >> FLAG_SHIFT) & FLAG_MASK;
                    final PreviewBlock block = PreviewBlock.readBinary(in, flags);
                    layer.put(key, block);
                }
            }
        }
        return storage;
    }

    /**
     * Returns the bounding box of all sampled biome data for the given y-layer,
     * or {@code null} if no data has been sampled for that layer.
     *
     * The returned array is {minX, minZ, maxX, maxZ} in block coordinates.
     */
    public int[] sampledBounds(int y) {
        return sampledBounds(y, FLAG_BIOME);
    }

    /**
     * Channel-aware variant of {@link #sampledBounds(int)}: computes the
     * bounds of the given channel (biome/height/noise/...) independently, so
     * consumers never treat "biome sampled" as "every channel sampled".
     */
    public int[] sampledBounds(int y, long flags) {
        final int indexY = (y - yMin) >> Y_BLOCK_SHIFT;
        if (indexY < 0 || indexY >= blocks.length) {
            return null;
        }

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean found = false;

        synchronized (blocks[indexY]) {
            for (var entry : blocks[indexY].long2ObjectEntrySet()) {
                final long key = entry.getLongKey();
                final long entryFlags = (key >> FLAG_SHIFT) & FLAG_MASK;
                if (entryFlags != flags) continue;

                final long sX = (key >> X_SHIFT) & XZ_MASK;
                final long sZ = (key >> Z_SHIFT) & XZ_MASK;
                // Convert signed section coords back to quart coords
                final int quartSX = (int)(sX - XZ_OFFSET);
                final int quartSZ = (int)(sZ - XZ_OFFSET);
                final int blockSX = QuartPos.toBlock(quartSX);
                final int blockSZ = QuartPos.toBlock(quartSZ);

                final PreviewBlock block = entry.getValue();
                final PreviewSection[] secs = block.sectionsRaw();
                for (PreviewSection section : secs) {
                    if (section == null) continue;
                    final int secQuartX = section.quartX();
                    final int secQuartZ = section.quartZ();
                    final int secBlockX = QuartPos.toBlock(secQuartX);
                    final int secBlockZ = QuartPos.toBlock(secQuartZ);
                    final int secSize = QuartPos.toBlock(1 << PreviewSection.SHIFT);

                    minX = Math.min(minX, secBlockX);
                    minZ = Math.min(minZ, secBlockZ);
                    maxX = Math.max(maxX, secBlockX + secSize);
                    maxZ = Math.max(maxZ, secBlockZ + secSize);
                    found = true;
                }
            }
        }

        return found ? new int[]{minX, minZ, maxX, maxZ} : null;
    }

    /**
     * Returns the total number of sampled quart positions for the given y-layer.
     */
    public int sampledCount(int y) {
        return sampledCount(y, FLAG_BIOME);
    }

    /**
     * Channel-aware variant of {@link #sampledCount(int)}: counts sampled
     * quart positions. Every section covers {@code SIZE * SIZE} quarts
     * regardless of its storage resolution (compressed sections sub-sample
     * the same area), so the count is the section count times the section
     * area in quarts.
     */
    public int sampledCount(int y, long flags) {
        final int indexY = (y - yMin) >> Y_BLOCK_SHIFT;
        if (indexY < 0 || indexY >= blocks.length) {
            return 0;
        }
        int count = 0;
        synchronized (blocks[indexY]) {
            for (var entry : blocks[indexY].long2ObjectEntrySet()) {
                final long key = entry.getLongKey();
                final long entryFlags = (key >> FLAG_SHIFT) & FLAG_MASK;
                if (entryFlags != flags) continue;
                for (PreviewSection section : entry.getValue().sectionsRaw()) {
                    if (section == null) continue;
                    count += PreviewSection.SIZE * PreviewSection.SIZE;
                }
            }
        }
        return count;
    }

    /**
     * Fills a minimap NativeImage with biome colors from sampled data.
     *
     * @param y          The block Y coordinate to sample
     * @param sampledMinX Block X of the sampled area's left edge
     * @param sampledMinZ Block Z of the sampled area's top edge
     * @param sampledW   Width of the sampled area in blocks
     * @param sampledH   Height of the sampled area in blocks
     * @param colorMap   Map from biome ID to ARGB color
     * @param img        The NativeImage to fill
     * @param imgW       Width of the image
     * @param imgH       Height of the image
     */
    public void fillMinimapImage(int y, int sampledMinX, int sampledMinZ, int sampledW, int sampledH,
                                  int[] colorMap, com.mojang.blaze3d.platform.NativeImage img, int imgW, int imgH) {
        final int indexY = (y - yMin) >> Y_BLOCK_SHIFT;
        if (indexY < 0 || indexY >= blocks.length) return;

        final int quartY = QuartPos.fromBlock(y);

        synchronized (blocks[indexY]) {
            for (var entry : blocks[indexY].long2ObjectEntrySet()) {
                final long key = entry.getLongKey();
                final long flags = (key >> FLAG_SHIFT) & FLAG_MASK;
                if (flags != FLAG_BIOME) continue;

                final PreviewBlock block = entry.getValue();
                final PreviewSection[] secs = block.sectionsRaw();
                for (PreviewSection section : secs) {
                    if (section == null) continue;

                    final int secQuartX = section.quartX();
                    final int secQuartZ = section.quartZ();

                    // Batch optimization: merge consecutive same-color pixels
                    // along the Z axis to reduce JNI fillRect calls.
                    for (int qx = 0; qx < PreviewSection.SIZE; qx++) {
                        int batchStartPy = -1;
                        int batchEndPy = -1;
                        int batchColor = 0;
                        for (int qz = 0; qz < PreviewSection.SIZE; qz++) {
                            short biomeId = section.get(qx, qz);
                            int blockX = QuartPos.toBlock(secQuartX + qx);
                            int blockZ = QuartPos.toBlock(secQuartZ + qz);
                            int py = (int)((float)(blockZ - sampledMinZ) / sampledH * imgH);
                            boolean inBounds = py >= 0 && py < imgH;

                            if (biomeId < 0 || biomeId >= colorMap.length || !inBounds) {
                                // Flush batch
                                if (batchEndPy > batchStartPy) {
                                    int px = (int)((float)(QuartPos.toBlock(secQuartX + qx) - sampledMinX) / sampledW * imgW);
                                    img.fillRect(px, batchStartPy, 1, batchEndPy - batchStartPy, batchColor);
                                }
                                batchStartPy = -1;
                                continue;
                            }

                            int color = colorMap[biomeId];
                            int px = (int)((float)(blockX - sampledMinX) / sampledW * imgW);
                            if (px < 0 || px >= imgW) {
                                batchStartPy = -1;
                                continue;
                            }

                            if (batchStartPy == -1 || color != batchColor) {
                                // Flush previous batch
                                if (batchEndPy > batchStartPy) {
                                    img.fillRect(px, batchStartPy, 1, batchEndPy - batchStartPy, batchColor);
                                }
                                batchStartPy = py;
                                batchColor = color;
                            }
                            batchEndPy = py + 1;
                        }
                        // Flush remaining batch
                        if (batchEndPy > batchStartPy && batchStartPy >= 0) {
                            int px = (int)((float)(QuartPos.toBlock(secQuartX + qx) - sampledMinX) / sampledW * imgW);
                            if (px >= 0 && px < imgW) {
                                img.fillRect(px, batchStartPy, 1, batchEndPy - batchStartPy, batchColor);
                            }
                        }
                    }
                }
            }
        }
    }
}
