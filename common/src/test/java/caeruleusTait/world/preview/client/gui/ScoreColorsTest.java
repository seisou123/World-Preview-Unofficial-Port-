package caeruleusTait.world.preview.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScoreColorsTest {

    @Test
    void goodAtAndAboveSeventy() {
        assertEquals(ScoreColors.GOOD, ScoreColors.scoreColor(70));
        assertEquals(ScoreColors.GOOD, ScoreColors.scoreColor(100));
    }

    @Test
    void mediumBetweenFortyAndSixtyNine() {
        assertEquals(ScoreColors.MEDIUM, ScoreColors.scoreColor(40));
        assertEquals(ScoreColors.MEDIUM, ScoreColors.scoreColor(69));
    }

    @Test
    void badBelowForty() {
        assertEquals(ScoreColors.BAD, ScoreColors.scoreColor(39));
        assertEquals(ScoreColors.BAD, ScoreColors.scoreColor(0));
    }
}
