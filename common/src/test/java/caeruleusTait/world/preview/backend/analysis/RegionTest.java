package caeruleusTait.world.preview.backend.analysis;

import caeruleusTait.world.preview.backend.worker.RegionWorkUnit;
import caeruleusTait.world.preview.client.gui.screens.PreviewContainer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTest {
    @Test
    void regionNormalizesReversedCornersAndRejectsOverflow() {
        Region region = Region.of(100, 80, -20, -40);

        assertEquals(-20, region.minX());
        assertEquals(-40, region.minZ());
        assertEquals(100, region.maxX());
        assertEquals(80, region.maxZ());
        assertEquals(121L * 121L, region.blockArea());
        assertThrows(IllegalArgumentException.class,
                () -> Region.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 1));
    }

    @Test
    void containsIncludesAllFourCornersAndExcludesJustOutside() {
        Region region = Region.of(-20, -40, 100, 80);

        assertTrue(region.contains(-20, -40));
        assertTrue(region.contains(-20, 80));
        assertTrue(region.contains(100, -40));
        assertTrue(region.contains(100, 80));
        assertFalse(region.contains(-21, -40));
        assertFalse(region.contains(101, 80));
        assertFalse(region.contains(-20, -41));
        assertFalse(region.contains(100, 81));
    }

    @Test
    void candidateAnalysisYUsesTargetDimensionHeight() {
        assertEquals(384, PreviewContainer.analysisYForDimension(-64, 448));
        assertEquals(256, PreviewContainer.analysisYForDimension(0, 256));
    }

    @Test
    void samplingIncludesNonDivisibleMaximumOnce() {
        assertEquals(List.of(0, 3, 5), RegionWorkUnit.coordinates(0, 5, 3));
        assertEquals(3L, RegionWorkUnit.coordinateCount(0, 5, 3));
    }

    @Test
    void samplingDoesNotDuplicateDivisibleMaximum() {
        assertEquals(List.of(0, 3, 6), RegionWorkUnit.coordinates(0, 6, 3));
    }

    @Test
    void supportsSinglePointRegion() {
        Region region = Region.of(7, -3, 7, -3);

        assertEquals(1L, region.blockArea());
        assertTrue(region.contains(7, -3));
        assertFalse(region.contains(6, -3));
        assertFalse(region.contains(7, -2));
    }

    @Test
    void supportsMaximumLegalDimensionsAndIntCoordinates() {
        Region region = Region.of(Integer.MIN_VALUE, Integer.MIN_VALUE, -2, -2);
        long maxDimension = Integer.MAX_VALUE;

        assertEquals(Integer.MIN_VALUE, region.minX());
        assertEquals(Integer.MIN_VALUE, region.minZ());
        assertEquals(-2, region.maxX());
        assertEquals(-2, region.maxZ());
        assertEquals(maxDimension * maxDimension, region.blockArea());
        assertTrue(region.contains(Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertTrue(region.contains(-2, -2));

        Region maxPoint = Region.of(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(1L, maxPoint.blockArea());
        assertTrue(maxPoint.contains(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
