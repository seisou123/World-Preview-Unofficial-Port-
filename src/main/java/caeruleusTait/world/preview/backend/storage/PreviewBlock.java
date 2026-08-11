// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.storage;

import caeruleusTait.world.preview.WorldPreview;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_HEIGHT;
import static caeruleusTait.world.preview.backend.storage.PreviewStorage.FLAG_STRUCT_START;

public class PreviewBlock {

    public static final int PREVIEW_BLOCK_SHIFT = 5;
    public static final int PREVIEW_BLOCK_SIZE = 1 << PREVIEW_BLOCK_SHIFT;
    public static final int PREVIEW_BLOCK_MASK = 0b11111;

    private final long flags;
    private final PreviewSection[] sections = new PreviewSection[PREVIEW_BLOCK_SIZE * PREVIEW_BLOCK_SIZE];

    public PreviewBlock(long flags) {
        this.flags = flags;
    }

    public synchronized @NotNull PreviewSection get(int quartX, int quartZ) {
        final int idx = (((quartX >> PreviewSection.SHIFT) & PREVIEW_BLOCK_MASK) * PREVIEW_BLOCK_SIZE) + ((quartZ >> PreviewSection.SHIFT) & PREVIEW_BLOCK_MASK);
        PreviewSection section = sections[idx];
        if (section == null) {
            section = sections[idx] = sectionFactory(quartX, quartZ);
        }
        return section;
    }

    private PreviewSection sectionFactory(int quartX, int quartZ) {
        if (flags == FLAG_STRUCT_START) {
            return new PreviewSectionStructure(quartX, quartZ);
        }
        final int quartStride = WorldPreview.get().renderSettings().quartStride();
        if (WorldPreview.get().cfg().enableCompression) {
            return switch (quartStride) {
                case 1 -> new PreviewSectionCompressed.Full(quartX, quartZ);
                case 2 -> new PreviewSectionCompressed.Half(quartX, quartZ);
                case 4 -> new PreviewSectionCompressed.Quarter(quartX, quartZ);
                default -> throw new IllegalStateException("Unexpected quartStride value: " + quartStride);
            };
        }
        return switch (quartStride) {
            case 1 -> new PreviewSectionFull(quartX, quartZ);
            case 2 -> new PreviewSectionHalf(quartX, quartZ);
            case 4 -> new PreviewSectionQuarter(quartX, quartZ);
            default -> throw new IllegalStateException("Unexpected quartStride value: " + quartStride);
        };
    }

    /**
     * Returns the section at the given quart coordinates without creating it.
     * @return the section, or {@code null} if it hasn't been sampled yet.
     */
    public PreviewSection getIfExists(int quartX, int quartZ) {
        final int idx = (((quartX >> PreviewSection.SHIFT) & PREVIEW_BLOCK_MASK) * PREVIEW_BLOCK_SIZE) + ((quartZ >> PreviewSection.SHIFT) & PREVIEW_BLOCK_MASK);
        return sections[idx];
    }

    /**
     * Returns the raw backing array of sections without copying.
     * <p>
     * This is intended for read-only iteration in performance-sensitive paths
     * (e.g. sampledBounds, sampledCount, fillMinimapImage).  The returned
     * array is the live internal array — do not modify it.
     */
    public PreviewSection[] sectionsRaw() {
        return sections;
    }

    public PreviewSection[] sections() {
        return Arrays.copyOf(sections, sections.length);
    }

    /**
     * Writes non-null sections for the safe binary disk cache.
     */
    synchronized void writeBinary(java.io.DataOutputStream out) throws java.io.IOException {
        int count = 0;
        for (PreviewSection section : sections) {
            if (section != null) {
                count++;
            }
        }
        out.writeInt(count);
        for (int i = 0; i < sections.length; i++) {
            final PreviewSection section = sections[i];
            if (section == null) {
                continue;
            }
            out.writeInt(i);
            section.writeBinary(out);
        }
    }

    /**
     * Reads sections for the safe binary disk cache.
     */
    static PreviewBlock readBinary(java.io.DataInputStream in, long flags) throws java.io.IOException {
        final PreviewBlock block = new PreviewBlock(flags);
        final int count = in.readInt();
        if (count < 0 || count > PREVIEW_BLOCK_SIZE * PREVIEW_BLOCK_SIZE) {
            throw new java.io.IOException("Invalid section count in PreviewBlock: " + count);
        }
        for (int i = 0; i < count; i++) {
            final int idx = in.readInt();
            if (idx < 0 || idx >= block.sections.length) {
                throw new java.io.IOException("Invalid section index in PreviewBlock: " + idx);
            }
            block.sections[idx] = PreviewSection.readBinary(in);
        }
        return block;
    }
}
