package caeruleusTait.world.preview.backend.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the semantic overlay on the noise views.
 * <p>
 * The overlay must never invent terrain: an un-sampled column keeps its noise
 * color, an un-sampled neighbour cannot create a coastline, and flat land is
 * left untouched. Water columns take a depth-shaded blue tint whose hue comes
 * from the depth ramp alone, so a warm palette colour cannot turn a basin grey;
 * only the field's lightness survives near the shore and fades out with depth.
 * <p>
 * Two guards keep the coastline from speckling the map. A water column with no
 * water neighbour is a one-quart dip and keeps the field colour, unless not one
 * neighbour is known at all. The line itself is drawn only where at least two of
 * the eight surrounding columns are land. The threshold is what rejects a
 * single-column island (each of its orthogonal water neighbours sees exactly one
 * land column); the eight-neighbour count is what keeps a straight shore (three
 * land columns among eight, but only one among the four orthogonal ones). Both
 * are pinned by their own tests below, because either one alone would be wrong.
 * Land relief is shaded by slope, bright on east+south-facing slopes and dark on
 * west+north-facing ones.
 * </p>
 */
class NoiseTerrainOverlayTest {

    private static final int BASE = 0xFF808080;
    /** A mid cool tone, close to the "temperate" end of the temperature palette. */
    private static final int COLD_BASE = 0xFF2B61BD;
    /** A warm yellow-orange, the palette colour that used to go grey under water. */
    private static final int WARM_BASE = 0xFFDBC247;
    private static final int SEA_LEVEL = 63;
    private static final int UNKNOWN = NoiseTerrainOverlay.UNKNOWN_HEIGHT;
    /** Land height used next to water at {@link #SEA_LEVEL}. */
    private static final int LAND = 70;

    private static int apply(int base, int height, int seaLevel,
                             int east, int west, int south, int north,
                             int northEast, int northWest, int southEast, int southWest) {
        return NoiseTerrainOverlay.apply(base, height,
                east, west, south, north, northEast, northWest, southEast, southWest, seaLevel);
    }

    private static int apply(int base, int height,
                             int east, int west, int south, int north,
                             int northEast, int northWest, int southEast, int southWest) {
        return apply(base, height, SEA_LEVEL, east, west, south, north,
                northEast, northWest, southEast, southWest);
    }

    /** Flat land column: all eight neighbours equal the column height. */
    private static int flatLand(int height) {
        return apply(BASE, height, height, height, height, height, height, height, height, height);
    }

    /**
     * Open water column: all eight neighbours are water, at the same depth.
     * The caller must keep {@code height < SEA_LEVEL}; the assertion below
     * makes a mis-typed call fail loudly instead of silently testing land.
     */
    private static int openWater(int baseColor, int height) {
        assertTrue(height < SEA_LEVEL, "openWater is a water helper, got height " + height);
        return apply(baseColor, height,
                height, height, height, height, height, height, height, height);
    }

    @Test
    void flatLandIsUnchanged() {
        assertEquals(BASE, flatLand(LAND));
    }

    @Test
    void waterIsTintedBlueOverAnyFieldHue() {
        for (int base : new int[]{BASE, COLD_BASE, WARM_BASE}) {
            int color = openWater(base, 62);
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            assertTrue(b > r, "water must be blue-dominant even over base " + Integer.toHexString(base)
                    + ": got r=" + r + " g=" + g + " b=" + b);
            assertTrue(b > g, "water must be blue-dominant even over base " + Integer.toHexString(base)
                    + ": got r=" + r + " g=" + g + " b=" + b);
        }
    }

    @Test
    void warmFieldDoesNotTurnWaterGrey() {
        // Regression: a fixed blue blended over a warm palette colour cancels
        // the chroma and produced grey-green lakes.
        int overWarm = openWater(WARM_BASE, 60);
        int chroma = max(overWarm) - min(overWarm);
        assertTrue(chroma >= 40, "water over a warm field must stay saturated, got chroma=" + chroma);
    }

    @Test
    void deeperWaterIsDarker() {
        int shallow = openWater(BASE, 62);
        int deep = openWater(BASE, 10);
        assertTrue(luminance(deep) < luminance(shallow),
                "deeper water must be darker: shallow=" + luminance(shallow) + " deep=" + luminance(deep));
    }

    @Test
    void deepWaterConvergesOnTheWaterColorRegardlessOfFieldHue() {
        int overCold = openWater(COLD_BASE, -40);
        int overWarm = openWater(WARM_BASE, -40);
        assertTrue(channelDistance(overCold, overWarm) <= 2,
                "open sea must not carry the field hue: cold=" + Integer.toHexString(overCold)
                        + " warm=" + Integer.toHexString(overWarm));
    }

    @Test
    void shallowWaterKeepsFieldLightness() {
        int overDark = openWater(0xFF203040, 62);
        int overBright = openWater(0xFFF0E0A0, 62);
        assertTrue(luminance(overBright) > luminance(overDark),
                "shallow water must follow the field's lightness: dark=" + luminance(overDark)
                        + " bright=" + luminance(overBright));
    }

    @Test
    void shorelineIsDrawnOnTheWaterSideOnly() {
        int open = openWater(BASE, 60);
        // A west-facing straight shore: land to the west, the two western
        // diagonals land as well, everything else water. That is the three-land
        // configuration a straight shore always has among its eight neighbours.
        int shore = apply(BASE, 60, 60, LAND, 60, 60, 60, LAND, 60, LAND);
        assertTrue(luminance(shore) < luminance(open), "shoreline must be darker than open water");

        // The land column next to the same water takes a slope shade and must
        // come out at exactly that shade, never at the coast-line colour.
        int landNextToWater = apply(BASE, LAND, 60, LAND, LAND, LAND, LAND, LAND, LAND, LAND);
        assertEquals(NoiseTerrainOverlay.scale(BASE, 0.95f), landNextToWater,
                "land next to water must take the slope shade, not the coast line");
        assertNotEquals(NoiseTerrainOverlay.blend(BASE, 0xFF071018, 0.65f), landNextToWater);
    }

    @Test
    void coastLineSeparatesWithoutGoingBlack() {
        // Regression: at the original mix the line was 77% ink and read as a
        // black border around every pond. Pin the actual value so neither a
        // lighter nor a darker mix can slip through.
        int shore = apply(BASE, 60, 60, LAND, 60, 60, 60, LAND, 60, LAND);
        assertEquals(0xFF31373C, shore, "coast line colour must stay at the tuned mix");

        // And the design point behind it: the line must stay lighter than the
        // deepest water it borders, otherwise the shore reads as a hole.
        assertTrue(luminance(shore) > luminance(openWater(BASE, -40)),
                "coast line must stay lighter than the darkest water: line=" + luminance(shore)
                        + " deep water=" + luminance(openWater(BASE, -40)));
        assertTrue(luminance(shore) < luminance(BASE), "coast line must still be darker than the field");
    }

    @Test
    void isolatedPondKeepsTheFieldColor() {
        // Every neighbour is land: a one-column dip in the terrain, not water.
        int pond = apply(BASE, 60, LAND, LAND, LAND, LAND, LAND, LAND, LAND, LAND);
        assertEquals(BASE, pond, "a lone water column surrounded by land must not be tinted");
    }

    @Test
    void singleLandNeighbourIsStillADip() {
        // Lower boundary of the dip guard: one known land neighbour and seven
        // un-sampled columns. A `land >= 2` style guard would tint this column
        // as open water; the dip guard must not.
        int dip = apply(BASE, 60,
                LAND, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        assertEquals(BASE, dip, "one land neighbour and no water is still a dip");
    }

    @Test
    void waterColumnWithOnlyUnknownNeighboursStaysTinted() {
        // Zero water neighbours but also zero land neighbours: nothing is known
        // about the surroundings, so the plain tint is kept rather than dropped.
        int column = apply(BASE, 60,
                UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        assertEquals(openWater(BASE, 60), column, "an isolated column with no known neighbour stays tinted");
    }

    @Test
    void oneWaterNeighbourKeepsTheTintAndSkipsTheLine() {
        // A lake interior next to the sampling frontier: one known water
        // neighbour, seven unknown. No land is known, so no line; the tint must
        // survive.
        int lake = apply(BASE, 60,
                60, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        assertEquals(openWater(BASE, 60), lake, "one water neighbour must keep the tint");
    }

    @Test
    void singleColumnIslandDoesNotGetOutlined() {
        // Every orthogonal neighbour of a one-column island sees exactly one
        // land column; drawing a line there puts four ink pixels around a
        // single water pixel, which reads as a black dot.
        int besideIsland = apply(BASE, 60, LAND, 60, 60, 60, 60, 60, 60, 60);
        assertEquals(openWater(BASE, 60), besideIsland,
                "the water around a one-column island must not be outlined");
    }

    @Test
    void twoAdjacentLandNeighboursDrawALine() {
        // Corner of a shoreline: one orthogonal plus one diagonal land column.
        // This also pins that the diagonals are counted — a 4-neighbour
        // implementation sees only one land column here and would draw nothing.
        int corner = apply(BASE, 60, LAND, 60, 60, 60, LAND, 60, 60, 60);
        assertTrue(luminance(corner) < luminance(openWater(BASE, 60)),
                "a diagonal land column must count toward the coast line");
    }

    @Test
    void twoOppositeLandNeighboursDrawALine() {
        // A one-column-wide channel between two shores: land on both sides and
        // nothing else. The line must still be drawn, so the guard cannot be
        // implemented as "two *adjacent* land neighbours".
        int channel = apply(BASE, 60, LAND, LAND, 60, 60, 60, 60, 60, 60);
        assertTrue(luminance(channel) < luminance(openWater(BASE, 60)),
                "two opposite land neighbours must still draw the coast line");
    }

    @Test
    void twoKnownLandNeighboursDrawALineDespiteUnknowns() {
        // Un-sampled neighbours must neither veto the line nor fabricate one:
        // two known land columns plus one known water column are enough, the
        // remaining five unknowns are ignored. (The water neighbour is needed
        // so the column is not classified as a lone dip first.)
        int shore = apply(BASE, 60,
                LAND, LAND, 60, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        assertTrue(luminance(shore) < luminance(openWater(BASE, 60)),
                "unknown neighbours must not suppress a real shore");
    }

    @Test
    void seaLevelBoundaryIsExclusive() {
        // height == seaLevel is land, height == seaLevel - 1 is water. Flipping
        // the comparison to <= would turn every shoreline column into water.
        assertEquals(BASE, apply(BASE, SEA_LEVEL, SEA_LEVEL, SEA_LEVEL, SEA_LEVEL, SEA_LEVEL,
                SEA_LEVEL, SEA_LEVEL, SEA_LEVEL, SEA_LEVEL));
        assertEquals(openWater(BASE, SEA_LEVEL - 1),
                apply(BASE, SEA_LEVEL - 1, SEA_LEVEL - 1, SEA_LEVEL - 1, SEA_LEVEL - 1, SEA_LEVEL - 1,
                        SEA_LEVEL - 1, SEA_LEVEL - 1, SEA_LEVEL - 1, SEA_LEVEL - 1));
    }

    @Test
    void nonOverworldSeaLevelIsHonoured() {
        // The Nether's sea level is 32: y=40 is land there, y=20 is water.
        assertEquals(BASE, apply(BASE, 40, 32, 40, 40, 40, 40, 40, 40, 40, 40));
        assertNotEquals(BASE, apply(BASE, 20, 32, 20, 20, 20, 20, 20, 20, 20, 20));
    }

    @Test
    void unknownHeightSkipsOverlay() {
        assertEquals(BASE, apply(BASE, UNKNOWN, 10, 10, 10, 10, 10, 10, 10, 10));
    }

    @Test
    void unknownNeighboursCannotCreateCoastLineOrShade() {
        assertEquals(BASE, apply(BASE, LAND,
                        UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN),
                "unknown neighbours must not fabricate a slope");
    }

    @Test
    void landIsShadedBySlopeDirection() {
        // Rising toward east+south: faces the north-west light, comes out bright.
        int lit = apply(BASE, LAND, 80, 60, 80, 60, 70, 70, 90, 70);
        // Rising toward west+north: faces away, comes out dark.
        int shaded = apply(BASE, LAND, 60, 80, 60, 80, 70, 70, 70, 90);
        assertTrue(luminance(lit) > luminance(BASE), "light-facing slope must brighten");
        assertTrue(luminance(shaded) < luminance(BASE), "shadowed slope must darken");
    }

    @Test
    void oneSidedSlopeNeighboursAreUsed() {
        // Each axis with a single known neighbour falls back to a one-sided
        // difference; every one of those four branches must reach the shade.
        assertTrue(luminance(apply(BASE, LAND, 80, UNKNOWN, UNKNOWN, UNKNOWN,
                UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN)) > luminance(BASE), "east-only must brighten");
        assertTrue(luminance(apply(BASE, LAND, UNKNOWN, 60, UNKNOWN, UNKNOWN,
                UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN)) > luminance(BASE), "west-only must brighten");
        assertTrue(luminance(apply(BASE, LAND, UNKNOWN, UNKNOWN, 80, UNKNOWN,
                UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN)) > luminance(BASE), "south-only must brighten");
        assertTrue(luminance(apply(BASE, LAND, UNKNOWN, UNKNOWN, UNKNOWN, 60,
                UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN)) > luminance(BASE), "north-only must brighten");
    }

    @Test
    void landShadeStaysWithinItsAmplitude() {
        // An extreme cliff must not blow out to white or black, and the amplitude
        // itself must stay at the tuned value (a 0.7 amplitude would still pass a
        // loose "not white" bound).
        int extreme = apply(BASE, LAND, 250, -60, 250, -60, 250, 250, 250, 250);
        assertEquals(NoiseTerrainOverlay.scale(BASE, 1.12f), extreme,
                "full-lit slope must land exactly on the tuned amplitude");
        int lum = luminance(extreme);
        assertTrue(lum > 30 && lum < 230, "relief shade must stay subtle, got luminance=" + lum);
    }

    @Test
    void blendHonoursEndpoints() {
        assertEquals(0xFF102030, NoiseTerrainOverlay.blend(0xFF102030, 0xFFA0B0C0, 0f));
        assertEquals(0xFFA0B0C0, NoiseTerrainOverlay.blend(0xFF102030, 0xFFA0B0C0, 1f));
        // Midpoint truncates rather than rounds: 0x10+(0xA0-0x10)/2 = 0x58,
        // 0x20+(0xB0-0x20)/2 = 0x68, 0x30+(0xC0-0x30)/2 = 0x78.
        assertEquals(0xFF586878, NoiseTerrainOverlay.blend(0xFF102030, 0xFFA0B0C0, 0.5f));
    }

    @Test
    void scalePreservesAlphaAndClamps() {
        assertEquals(0xFF808080, NoiseTerrainOverlay.scale(0xFF808080, 1.0f));
        assertEquals(0x7F000000, NoiseTerrainOverlay.scale(0x7F808080, 0.0f));
        assertEquals(0xFFFFFFFF, NoiseTerrainOverlay.scale(0xFFFFFFFF, 4.0f));
    }

    @Test
    void lumaUsesRec601WeightsAndGreyIsOpaque() {
        assertEquals(0, NoiseTerrainOverlay.luma(0xFF000000));
        assertEquals(255, NoiseTerrainOverlay.luma(0xFFFFFFFF));
        assertEquals(0xFF808080, NoiseTerrainOverlay.grey(128));
        assertEquals(0xFFFFFFFF, NoiseTerrainOverlay.grey(999));
        assertEquals(0xFF000000, NoiseTerrainOverlay.grey(-5));
    }

    private static int max(int color) {
        return Math.max((color >>> 16) & 0xFF, Math.max((color >>> 8) & 0xFF, color & 0xFF));
    }

    private static int min(int color) {
        return Math.min((color >>> 16) & 0xFF, Math.min((color >>> 8) & 0xFF, color & 0xFF));
    }

    private static int channelDistance(int a, int b) {
        int d = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            d += Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF));
        }
        return d;
    }

    private static int luminance(int argb) {
        return (((argb >> 16) & 0xFF) * 299 + ((argb >> 8) & 0xFF) * 587 + (argb & 0xFF) * 114) / 1000;
    }
}
