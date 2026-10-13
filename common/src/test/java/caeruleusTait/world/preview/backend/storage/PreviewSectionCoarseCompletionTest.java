// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.storage;

import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drag-tier coarse completion marks.  The release sweep must leave every
 * chunk the drag touched re-sampleable at full resolution: a coarse-only
 * chunk loses its coarse mark, and a chunk carrying both a fine and a coarse
 * mark (the two units raced the drag edge, so its values may be coarse under a
 * fine bit) loses both.  Chunks the drag never touched keep their fine marks.
 * Coarse marks are never serialized into the binary cache.
 */
class PreviewSectionCoarseCompletionTest {

    @BeforeEach
    void requireChunkPos() {
        try {
            new ChunkPos(0, 0);
        } catch (Throwable t) {
            // ChunkPos's static init pulls the chunk-status pyramid, which
            // needs worldgen bootstrap.  Repos whose headless test env does
            // not bootstrap registries skip these tests; repos that bootstrap
            // (or MC versions with a bootstrap-free ChunkPos) run them for real.
            org.junit.jupiter.api.Assumptions.abort(
                    "ChunkPos requires worldgen bootstrap; unavailable in this headless test env");
        }
    }

    @Test
    void coarseMarkSatisfiesCompletionButClearsIndependently() {
        PreviewSection section = new PreviewSectionFull(0, 0);
        ChunkPos chunk = new ChunkPos(1, 2);
        assertFalse(section.isCompleted(chunk));
        section.markCompletedCoarse(chunk);
        assertTrue(section.isCompleted(chunk));
        assertFalse(section.isCompletedFine(chunk));
        section.clearCoarseCompletions();
        assertFalse(section.isCompleted(chunk));
    }

    @Test
    void touchedChunkLosesBothMarksOnSweep() {
        PreviewSection section = new PreviewSectionFull(0, 0);
        ChunkPos chunk = new ChunkPos(3, 4);
        section.markCompleted(chunk);
        section.markCompletedCoarse(chunk);
        // A coarse-touched chunk is not full-resolution ground truth even if a
        // fine unit raced the drag edge and granted its bit.
        assertFalse(section.isCompletedFine(chunk));
        section.clearCoarseCompletions();
        assertFalse(section.isCompleted(chunk));
        assertFalse(section.isCompletedFine(chunk));
    }

    @Test
    void untouchedFineChunkSurvivesSweep() {
        PreviewSection section = new PreviewSectionFull(0, 0);
        ChunkPos fine = new ChunkPos(5, 6);
        ChunkPos coarse = new ChunkPos(6, 6);
        section.markCompleted(fine);
        section.markCompletedCoarse(coarse);
        section.clearCoarseCompletions();
        assertTrue(section.isCompleted(fine));
        assertTrue(section.isCompletedFine(fine));
        assertFalse(section.isCompleted(coarse));
    }

    @Test
    void binaryRoundTripDropsCoarseMarks() throws Exception {
        PreviewSection section = new PreviewSectionFull(0, 0);
        ChunkPos chunk = new ChunkPos(7, 8);
        section.markCompletedCoarse(chunk);
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        section.writeBinary(new DataOutputStream(bout));
        PreviewSection read = PreviewSection.readBinary(
                new DataInputStream(new ByteArrayInputStream(bout.toByteArray())));
        assertFalse(read.isCompleted(chunk));
    }

    @Test
    void storageWideClearDropsTouchedAndKeepsUntouchedFine() {
        PreviewStorage storage = new PreviewStorage(-64, 320);
        ChunkPos coarseOnly = new ChunkPos(0, 0);
        ChunkPos fineOnly = new ChunkPos(1, 1);
        ChunkPos mixed = new ChunkPos(2, 2);
        final long flag = PreviewStorage.FLAG_BIOME;
        storage.section4(coarseOnly, 0, flag).markCompletedCoarse(coarseOnly);
        storage.section4(fineOnly, 0, flag).markCompleted(fineOnly);
        storage.section4(mixed, 0, flag).markCompleted(mixed);
        storage.section4(mixed, 0, flag).markCompletedCoarse(mixed);

        assertTrue(storage.isChunkSampled(
                QuartPos.fromSection(0), QuartPos.fromBlock(0), QuartPos.fromSection(0), flag));
        assertTrue(storage.isChunkSampledFine(
                QuartPos.fromSection(1), QuartPos.fromBlock(0), QuartPos.fromSection(1), flag));
        assertFalse(storage.isChunkSampledFine(
                QuartPos.fromSection(2), QuartPos.fromBlock(0), QuartPos.fromSection(2), flag));

        storage.clearCoarseCompletions();

        assertFalse(storage.isChunkSampled(
                QuartPos.fromSection(0), QuartPos.fromBlock(0), QuartPos.fromSection(0), flag));
        assertTrue(storage.isChunkSampled(
                QuartPos.fromSection(1), QuartPos.fromBlock(0), QuartPos.fromSection(1), flag));
        assertFalse(storage.isChunkSampled(
                QuartPos.fromSection(2), QuartPos.fromBlock(0), QuartPos.fromSection(2), flag));
    }
}
