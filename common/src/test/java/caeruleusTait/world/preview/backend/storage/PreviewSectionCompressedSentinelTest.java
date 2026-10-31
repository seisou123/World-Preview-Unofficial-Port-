// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sentinel-slot aliasing in the compressed section map.
 * <p>
 * {@link PreviewSectionCompressed#cacheMapIdx} overloads {@link Short#MIN_VALUE} with two
 * distinct meanings: "this map slot is still free" and "the cell holds the
 * unsampled sentinel".  Writing the sentinel through {@code set} therefore cannot
 * be told apart from allocating a fresh slot, so the sentinel cell ends up
 * pointing at a slot that later writes treat as free.  Any subsequent real
 * height lands in that slot and the unsampled cell reads back a neighbour's
 * height -- which the binary cache then persists across sessions.
 */
class PreviewSectionCompressedSentinelTest {

    private static final short UNSAMPLED = Short.MIN_VALUE;

    @Test
    void sentinelWriteDoesNotAliasASlotThatLaterWritesReuse() {
        PreviewSectionCompressed.Full section = new PreviewSectionCompressed.Full(0, 0);

        // Two real heights drive the section into first-level compression
        // (4-slot map), then the sentinel claims a slot.
        section.set(0, 0, (short) 64);
        section.set(1, 1, (short) 70);
        section.set(2, 2, UNSAMPLED);

        // A later real height must not be able to take over the sentinel's slot.
        section.set(3, 3, (short) 90);

        assertEquals(UNSAMPLED, section.get(2, 2),
                "unsampled cell must keep reading back as unsampled");
    }

    @Test
    void sentinelSurvivesRepeatedRealWritesOnTheSameSection() {
        PreviewSectionCompressed.Full section = new PreviewSectionCompressed.Full(0, 0);

        section.set(0, 0, (short) 10);
        section.set(4, 4, UNSAMPLED);

        // Fill every remaining map slot with real heights; the sentinel must
        // still be readable after the map has saturated.
        for (short value = 11; value < 40; ++value) {
            section.set(value, value, value);
        }

        assertEquals(UNSAMPLED, section.get(4, 4),
                "unsampled cell must stay unsampled while the map fills up");
        assertEquals((short) 10, section.get(0, 0), "pre-existing real value must survive");
    }

    @Test
    void sentinelAndRealValuesCoexistAcrossCompressionUpgrades() {
        PreviewSectionCompressed.Full section = new PreviewSectionCompressed.Full(0, 0);

        section.set(0, 0, (short) 64);
        section.set(1, 1, UNSAMPLED);

        // Push past the 4-slot and 16-slot levels so each grow path re-fills
        // the new map with MIN_VALUE free slots.
        for (short value = 2; value < 40; ++value) {
            section.set(value, value, (short) (100 + value));
        }

        assertEquals(UNSAMPLED, section.get(1, 1),
                "unsampled cell must stay unsampled across compression upgrades");
    }

    @Test
    void freshSentinelCellsAllReadBackUnsampled() {
        PreviewSectionCompressed.Full section = new PreviewSectionCompressed.Full(0, 0);

        section.set(0, 0, (short) 64);
        section.set(1, 0, UNSAMPLED);
        section.set(2, 0, UNSAMPLED);
        section.set(3, 0, UNSAMPLED);

        // Every sentinel cell must report unsampled, and no real write may
        // silently satisfy any of them.
        section.set(5, 5, (short) 77);

        for (int x = 1; x <= 3; ++x) {
            assertEquals(UNSAMPLED, section.get(x, 0),
                    "unsampled cell at (" + x + ",0) must not read a real height");
        }
    }
}