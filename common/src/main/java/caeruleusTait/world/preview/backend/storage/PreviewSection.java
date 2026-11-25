// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.storage;

import caeruleusTait.world.preview.backend.FillLevel;

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

    // Drag-tier completion marks: completion granted to units sampled with
    // the drag-time coarse sampler.  Kept separate from {@link #completed}
    // so the coarse marks can be dropped in one sweep on drag release
    // without touching fine completions; they are session-local and never
    // serialized.
    private final BitSet coarseCompleted = new BitSet(SECTION_SIZE * HALF_SIZE);

    // Quarter-level fill marks: completion granted to units that sampled the
    // chunk's full 2x2 quart lattice — the middle stage of the staged initial
    // fill.  Like the coarse marks they are session-local, never serialized,
    // and are swept as soon as the chunk is refined to full resolution.
    private final BitSet quarterCompleted = new BitSet(SECTION_SIZE * HALF_SIZE);

    // Raster-dirty tracking: bumped whenever sampled values change (once per
    // applied work result, not per cell).  The renderer compares this against
    // the version it last painted for the section and skips unchanged
    // sections, so a loading map only re-rasterizes the areas that moved.
    private volatile long writeVersion;

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
        final int index = chunkIndex(chunkPos);
        return completed.get(index) || quarterCompleted.get(index) || coarseCompleted.get(index);
    }

    /**
     * Chunk coords.  True when the chunk reached at least the given lattice
     * density.  The staged initial fill uses this to decide whether a chunk
     * still needs the next refinement pass; {@link FillLevel#FULL} means the
     * chunk is finished (the completion bit).
     */
    public synchronized boolean isCompletedAtLeast(ChunkPos chunkPos, FillLevel level) {
        final int index = chunkIndex(chunkPos);
        return switch (level) {
            case SINGLE -> completed.get(index) || quarterCompleted.get(index) || coarseCompleted.get(index);
            case QUARTER -> completed.get(index) || quarterCompleted.get(index);
            case FULL -> completed.get(index);
        };
    }

    /**
     * Chunk coords.  Like {@link #isCompleted} but also requires that the
     * chunk was NOT touched by a coarser fill stage: coarse sampling
     * overwrites a chunk's sixteen quarts with one sample, so a chunk carrying
     * both a fine and a staged mark may hold coarse values under a fine
     * completion (the two units raced the fill edge).  Consumers that treat a
     * stored value as ground truth (analysis fact reuse, terrain export
     * probes) use this variant so such a chunk is re-sampled instead of
     * trusted.
     */
    public synchronized boolean isCompletedFine(ChunkPos chunkPos) {
        final int index = chunkIndex(chunkPos);
        return completed.get(index) && !quarterCompleted.get(index) && !coarseCompleted.get(index);
    }

    private int chunkIndex(ChunkPos chunkPos) {
        return (chunkPos.x - chunkX) * SECTION_SIZE + (chunkPos.z - chunkZ);
    }

    /**
     * Chunk coords
     */
    public synchronized void markCompleted(ChunkPos chunkPos) {
        completed.set(chunkIndex(chunkPos));
    }

    /**
     * Chunk coords.  Completion granted to a unit sampled with the drag-tier
     * coarse sampler.  {@link #isCompleted} reports it so the queue probe
     * stays accurate while dragging; {@link #clearCoarseCompletions} drops
     * the marks on release so the fine pass re-samples those chunks.
     */
    public synchronized void markCompletedCoarse(ChunkPos chunkPos) {
        coarseCompleted.set(chunkIndex(chunkPos));
    }

    /**
     * Chunk coords.  Completion granted to a unit that sampled the chunk's
     * full quarter lattice — the middle stage of the staged initial fill.
     * {@link #isCompletedAtLeast} reports it, so the full-resolution pass only
     * re-samples the chunks that reached this level.
     */
    public synchronized void markCompletedQuarter(ChunkPos chunkPos) {
        quarterCompleted.set(chunkIndex(chunkPos));
    }

    /**
     * Drops every drag-tier completion mark, and with each of them the fine
     * completion of the same chunk.  The chunk must be re-sampled at full
     * resolution after a drag touched it: coarse sampling overwrote all
     * sixteen of its quarts with one sample, and while a coarse-marked chunk
     * normally carries no fine mark (the queue probe skips fine-completed
     * chunks), a fine unit that raced the drag edge can have granted one
     * anyway — leaving the coarse values masked behind a fine completion and
     * never re-sampled.  Chunks the drag never touched keep their fine marks
     * untouched.
     */
    public synchronized void clearCoarseCompletions() {
        clearAndDropFine(coarseCompleted);
    }

    /**
     * Drops the quarter-level marks of the staged fill, and with each of them
     * the fine completion of the same chunk, for the same race reason as
     * {@link #clearCoarseCompletions}.
     */
    public synchronized void clearQuarterCompletions() {
        clearAndDropFine(quarterCompleted);
    }

    /**
     * Drops every staged fill mark (single- and quarter-level) WITHOUT touching
     * the fine completion bits.  Used when a staged fill finished: every
     * completed chunk holds target-resolution data by then, so the staged
     * marks are pure bookkeeping and must not demote the chunk to
     * "not ground truth".
     */
    public synchronized void clearStagedMarks() {
        coarseCompleted.clear();
        quarterCompleted.clear();
    }

    /**
     * Drops every staged fill mark and the fine completion of the same chunks.
     * Used when a staged fill is abandoned mid-way: its values are not usable
     * as full-resolution data, so the touched chunks must be re-sampled.
     */
    public synchronized void clearStagedCompletions() {
        clearAndDropFine(coarseCompleted);
        clearAndDropFine(quarterCompleted);
    }

    /** Clears {@code marks} and the fine completion of every chunk it covers. */
    private void clearAndDropFine(BitSet marks) {
        for (int i = marks.nextSetBit(0); i >= 0; i = marks.nextSetBit(i + 1)) {
            completed.clear(i);
        }
        marks.clear();
    }

    /** Monotonic value-write version for the renderer's incremental raster cache. */
    public long writeVersion() {
        return writeVersion;
    }

    /**
     * Bumps {@link #writeVersion()}; called once per applied work result, after
     * its samples (and structures) have been written.  A per-result instead of
     * per-cell bump keeps the cost negligible while still guaranteeing that a
     * section is repainted when any of its data changed.
     */
    public void bumpWriteVersion() {
        writeVersion++;
    }

    /**
     * Number of chunk columns that completed full-resolution sampling in this
     * section.
     *
     * <p>Coarse (coarse-first / drag-tier) completions are deliberately not
     * counted: they are a transient preview that is swept and re-sampled as
     * soon as the full-resolution pass runs, so including them would make the
     * displayed progress jump back down at that transition.
     *
     * <p>Unlike a section-count, this reflects work actually performed: the
     * render path allocates viewport sections eagerly, so their existence says
     * nothing about how much of the layer has been sampled.
     */
    public synchronized int completedChunkCount() {
        return completed.cardinality();
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
