package caeruleusTait.world.preview.client.gui.screens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure geometry policy for the analysis screen's fullscreen overlay band:
 * the tab row reuses the panel row's width recipe, and the collapse trigger
 * stays right of the tabs and inside the band for every supported window.
 * Minecraft's logical window width never drops below 320 in its default
 * scaling, so the band floor in these tests is 304 (= 320 - 2 x 8 margin).
 */
class OverlayLayoutTest {

    @Test
    void tabWidthMatchesPanelRecipeAndClamps() {
        // 480px gui window -> 464px band -> capped width
        assertEquals(48, WorldAnalysisScreen.overlayTabWidth(464));
        // narrow band at Minecraft's minimum logical width: the cap still governs
        assertEquals(48, WorldAnalysisScreen.overlayTabWidth(304));
        // 24px floor only kicks in below a 108px band
        assertEquals(24, WorldAnalysisScreen.overlayTabWidth(60));
        // huge band cap
        assertEquals(48, WorldAnalysisScreen.overlayTabWidth(4000));
    }

    @Test
    void fourTabsPlusGapsNeverExceedBand() {
        for (int b = 304; b <= 2000; b += 7) {
            final int band = b;
            int tabW = WorldAnalysisScreen.overlayTabWidth(band);
            assertTrue(4 * tabW + 3 * 4 <= band, () -> "tabs overflow band " + band);
        }
    }

    @Test
    void collapseStaysRightOfTabsInsideBand() {
        for (int b = 304; b <= 2000; b += 7) {
            final int band = b;
            int tabW = WorldAnalysisScreen.overlayTabWidth(band);
            int tabsRight = 4 * tabW + 3 * 4;
            int collapseX = WorldAnalysisScreen.overlayCollapseX(band);
            assertTrue(collapseX >= tabsRight, () -> "collapse overlaps tabs in band " + band);
            assertTrue(collapseX + 66 <= band, () -> "collapse exceeds band " + band);
        }
    }
}
