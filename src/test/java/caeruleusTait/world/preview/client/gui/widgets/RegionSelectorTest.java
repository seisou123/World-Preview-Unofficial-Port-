package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.backend.analysis.Region;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionSelectorTest {
    @Test
    void normalizeAcceptsReversedCorners() {
        Optional<Region> normalized = RegionSelector.normalize("100", "80", "-20", "-40");

        assertTrue(normalized.isPresent());
        assertEquals(new Region(-20, -40, 100, 80), normalized.orElseThrow());
    }

    @Test
    void normalizeRejectsMalformedAndOversizedInput() {
        assertTrue(RegionSelector.normalize("oops", "0", "1", "1").isEmpty());
        assertTrue(RegionSelector.normalize("0", "0", "100000", "100000").isEmpty());
    }

    @Test
    void normalizeAcceptsSinglePoint() {
        assertEquals(Optional.of(new Region(7, -3, 7, -3)),
                RegionSelector.normalize("7", "-3", "7", "-3"));
    }
}
