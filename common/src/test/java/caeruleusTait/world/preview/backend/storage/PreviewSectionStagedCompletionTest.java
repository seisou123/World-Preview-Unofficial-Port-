// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.storage;

import caeruleusTait.world.preview.backend.FillLevel;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Staged-fill completion marks: the coarse-first chain records each refinement
 * level on its own bitset, the queue probe must only skip chunks that already
 * reached the level the pass is running at, and the two possible exits differ:
 * a finished chain keeps the fine completion (the data is at target
 * resolution), an abandoned one drops it so the chunks are re-sampled.
 */
class PreviewSectionStagedCompletionTest {

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
    void coarseMarkIsBelowQuarter() {
        PreviewSection section = new PreviewSectionFull(0, 0);
        ChunkPos chunk = new ChunkPos(1, 1);
        section.markCompletedCoarse(chunk);
        assertTrue(section.isCompleted(chunk));
        assertFalse(section.isCompletedAtLeast(chunk, FillLevel.QUARTER));
        assertFalse(section.isCompletedAtLeast(chunk, FillLevel.FULL));
        assertFalse(section.isCompletedFine(chunk));
    }

    @Test
    void quarterMarkSatisfiesQuarterButNotFine() {
        PreviewSection section = new PreviewSectionFull(0, 0);
        ChunkPos chunk = new ChunkPos(2, 3);
        section.markCompletedQuarter(chunk);
        assertTrue(section.isCompleted(chunk));
        assertTrue(section.isCompletedAtLeast(chunk, FillLevel.QUARTER));
        assertFalse(section.isCompletedAtLeast(chunk, FillLevel.FULL));
        assertFalse(section.isCompletedFine(chunk));
    }

    @Test
    void stagedMarksAccumulateAndClearWithoutDemotingFineData() {
        PreviewSection section = new PreviewSectionFull(0, 0);
        ChunkPos chunk = new ChunkPos(3, 4);
        section.markCompletedCoarse(chunk);
        section.markCompletedQuarter(chunk);
        section.markCompleted(chunk);
        // Staged marks survive until the chain ends, so the next stage can
        // check its prerequisite — but the chunk is not ground truth yet.
        assertFalse(section.isCompletedFine(chunk));
        section.clearStagedMarks();
        // A finished chain keeps the fine completion: its data is at target
        // resolution.
        assertTrue(section.isCompletedFine(chunk));
    }

    @Test
    void abandonedFillSweepDropsStagedChunksEntirely() {
        PreviewSection section = new PreviewSectionFull(0, 0);
        ChunkPos staged = new ChunkPos(5, 6);
        ChunkPos untouched = new ChunkPos(6, 6);
        section.markCompletedCoarse(staged);
        section.markCompletedQuarter(staged);
        section.markCompleted(staged);
        section.markCompleted(untouched);
        section.clearStagedCompletions();
        assertFalse(section.isCompleted(staged));
        assertTrue(section.isCompletedFine(untouched));
    }
}
