package caeruleusTait.world.preview.client.gui.screens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure width policy for the content-adapted floating panels of the preview
 * tab rail.  The reference widths use the longest vanilla English biome name
 * ("Old Growth Spruce Taiga", 113px) and a typical long structure name.
 */
class FloatingPanelWidthTest {

    @Test
    void biomesWidthCoversTextAndScrollbar() {
        // 16px text inset + longest name + 6px scrollbar column + 2px clearance
        assertEquals(137, PreviewContainer.biomesPanelWidth(113, false));
    }

    @Test
    void biomesWidthAddsBadgeReserveWithCounts() {
        // + badge reserve (38) + 4px extra clearance between text and badge
        assertEquals(179, PreviewContainer.biomesPanelWidth(113, true));
    }

    @Test
    void structuresWidthCoversIconNameEyeAndScrollbar() {
        // 20px icon slot + name + 2px gap + 22px eye toggle + 8px scrollbar/clearance
        assertEquals(147, PreviewContainer.structuresPanelWidth(95));
    }

    @Test
    void widthIsClampedToFixedBounds() {
        assertEquals(96, PreviewContainer.clampFloatingPanelWidth(0));
        // Empty structures list yields 52px; the floor keeps the panel usable
        assertEquals(96, PreviewContainer.clampFloatingPanelWidth(52));
        assertEquals(96, PreviewContainer.clampFloatingPanelWidth(95));
        assertEquals(179, PreviewContainer.clampFloatingPanelWidth(179));
        assertEquals(180, PreviewContainer.clampFloatingPanelWidth(500));
    }
}
