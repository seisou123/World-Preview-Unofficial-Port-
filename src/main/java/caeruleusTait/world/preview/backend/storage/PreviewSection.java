// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.BitSet;
import java.util.List;

public abstract class PreviewSection {
    public static final int SHIFT = 6;
    public static final int SIZE = 1 << SHIFT;
    public static final int OFFSET = 1 << (SHIFT - 1);
    public static final int MASK = -SIZE;

    public static final int HALF_SHIFT = 1;
    public static final int HALF_SIZE = SIZE >> HALF_SHIFT;

    // SectionPos == ChunkPos
    public static final int QUART_TO_SECTION_SHIFT = 2;
    public static final int SECTION_SIZE = SIZE >> QUART_TO_SECTION_SHIFT;

    /** Binary cache section types (non-Java-serialization format). */
    static final byte BIN_TYPE_FULL = 1;
    static final byte BIN_TYPE_HALF = 2;
    static final byte BIN_TYPE_QUARTER = 3;
    static final byte BIN_TYPE_STRUCT = 5;

    private final int quartX;
    private final int quartZ;

    private final int chunkX;
    private final int chunkZ;

    private final BitSet completed = new BitSet(SECTION_SIZE * HALF_SIZE);

    protected PreviewSection(int quartX, int quartZ) {
        this.quartX = quartX & MASK;
        this.quartZ = quartZ & MASK;
        this.chunkX = this.quartX >> QUART_TO_SECTION_SHIFT;
        this.chunkZ = this.quartZ >> QUART_TO_SECTION_SHIFT;
    }

    /**
     * Writes this section to the safe binary cache stream.
     * Compressed sections are expanded to full short grids on write.
     */
    final void writeBinary(DataOutputStream out) throws IOException {
        final byte type = binaryType();
        out.writeByte(type);
        out.writeInt(quartX);
        out.writeInt(quartZ);
        writeCompletedBits(out);

        if (type == BIN_TYPE_STRUCT) {
            final List<PreviewStruct> structs = structures();
            out.writeInt(structs.size());
            for (PreviewStruct s : structs) {
                final BlockPos c = s.center();
                final BoundingBox bb = s.boundingBox();
                out.writeInt(c.getX());
                out.writeInt(c.getY());
                out.writeInt(c.getZ());
                out.writeShort(s.structureId());
                out.writeInt(bb.minX());
                out.writeInt(bb.minY());
                out.writeInt(bb.minZ());
                out.writeInt(bb.maxX());
                out.writeInt(bb.maxY());
                out.writeInt(bb.maxZ());
            }
            return;
        }

        final int cellSize = switch (type) {
            case BIN_TYPE_FULL -> SIZE;
            case BIN_TYPE_HALF -> HALF_SIZE;
            case BIN_TYPE_QUARTER -> SECTION_SIZE;
            default -> throw new IOException("Unexpected section binary type: " + type);
        };
        final int stride = SIZE / cellSize;
        for (int x = 0; x < cellSize; x++) {
            for (int z = 0; z < cellSize; z++) {
                out.writeShort(get(x * stride, z * stride));
            }
        }
    }

    /**
     * Reads a section from the safe binary cache stream.
     */
    static PreviewSection readBinary(DataInputStream in) throws IOException {
        final byte type = in.readByte();
        final int quartX = in.readInt();
        final int quartZ = in.readInt();

        final PreviewSection section = switch (type) {
            case BIN_TYPE_FULL -> new PreviewSectionFull(quartX, quartZ);
            case BIN_TYPE_HALF -> new PreviewSectionHalf(quartX, quartZ);
            case BIN_TYPE_QUARTER -> new PreviewSectionQuarter(quartX, quartZ);
            case BIN_TYPE_STRUCT -> new PreviewSectionStructure(quartX, quartZ);
            default -> throw new IOException("Unknown section binary type: " + type);
        };
        section.readCompletedBits(in);

        if (type == BIN_TYPE_STRUCT) {
            final int count = in.readInt();
            if (count < 0 || count > 1_000_000) {
                throw new IOException("Invalid structure count: " + count);
            }
            for (int i = 0; i < count; i++) {
                final int cX = in.readInt();
                final int cY = in.readInt();
                final int cZ = in.readInt();
                final short structureId = in.readShort();
                final int bbMinX = in.readInt();
                final int bbMinY = in.readInt();
                final int bbMinZ = in.readInt();
                final int bbMaxX = in.readInt();
                final int bbMaxY = in.readInt();
                final int bbMaxZ = in.readInt();
                section.addStructure(new PreviewStruct(
                        new BlockPos(cX, cY, cZ),
                        structureId,
                        new BoundingBox(bbMinX, bbMinY, bbMinZ, bbMaxX, bbMaxY, bbMaxZ)
                ));
            }
            return section;
        }

        final int cellSize = switch (type) {
            case BIN_TYPE_FULL -> SIZE;
            case BIN_TYPE_HALF -> HALF_SIZE;
            case BIN_TYPE_QUARTER -> SECTION_SIZE;
            default -> throw new IOException("Unexpected section binary type: " + type);
        };
        final int stride = SIZE / cellSize;
        for (int x = 0; x < cellSize; x++) {
            for (int z = 0; z < cellSize; z++) {
                section.set(x * stride, z * stride, in.readShort());
            }
        }
        return section;
    }

    private byte binaryType() {
        if (this instanceof PreviewSectionStructure) {
            return BIN_TYPE_STRUCT;
        }
        // Expand compressed (and uncompressed) grids by logical resolution.
        final int s = size();
        if (s == SIZE) {
            return BIN_TYPE_FULL;
        }
        if (s == HALF_SIZE) {
            return BIN_TYPE_HALF;
        }
        if (s == SECTION_SIZE) {
            return BIN_TYPE_QUARTER;
        }
        throw new IllegalStateException("Unsupported section size for binary cache: " + s);
    }

    private void writeCompletedBits(DataOutputStream out) throws IOException {
        final byte[] bits;
        synchronized (this) {
            bits = completed.toByteArray();
        }
        out.writeInt(bits.length);
        out.write(bits);
    }

    private void readCompletedBits(DataInputStream in) throws IOException {
        final int len = in.readInt();
        if (len < 0 || len > 4096) {
            throw new IOException("Invalid completed bitset length: " + len);
        }
        final byte[] bits = in.readNBytes(len);
        if (bits.length != len) {
            throw new IOException("Truncated completed bitset");
        }
        synchronized (this) {
            completed.clear();
            completed.or(BitSet.valueOf(bits));
        }
    }

    public abstract int size();

    public abstract short get(int x, int z);

    public abstract void set(int x, int z, short biome);

    public abstract List<PreviewStruct> structures();

    public abstract void addStructure(PreviewStruct structureData);

    /**
     * Chunk coords
     */
    public synchronized boolean isCompleted(ChunkPos chunkPos) {
        return completed.get((chunkPos.x() - chunkX) * SECTION_SIZE + (chunkPos.z() - chunkZ));
    }

    /**
     * Chunk coords
     */
    public synchronized void markCompleted(ChunkPos chunkPos) {
        completed.set((chunkPos.x() - chunkX) * SECTION_SIZE + (chunkPos.z() - chunkZ));
    }

    public AccessData calcQuartOffsetData(int minQuartX, int minQuartZ, int maxQuartX, int maxQuartZ) {
        final int accessMinX = minQuartX - quartX;
        final int accessMinZ = minQuartZ - quartZ;
        final int accessMaxX = maxQuartX - quartX;
        final int accessMaxZ = maxQuartZ - quartZ;
        return new AccessData(
                accessMinX,
                accessMinZ,
                Math.min(accessMaxX, SIZE),
                Math.min(accessMaxZ, SIZE),
                accessMaxX > SIZE,
                accessMaxZ > SIZE
        );
    }

    public int quartX() {
        return quartX;
    }

    public int quartZ() {
        return quartZ;
    }

    public int blockX() {
        return QuartPos.toBlock(quartX);
    }

    public int blockZ() {
        return QuartPos.toBlock(quartZ);
    }

        public record AccessData(int minX, int minZ, int maxX, int maxZ, boolean continueX, boolean continueZ) {
    }

    public record PreviewStruct(BlockPos center, short structureId, BoundingBox boundingBox) {
    }
}
