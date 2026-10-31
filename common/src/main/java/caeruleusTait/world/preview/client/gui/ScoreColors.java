package caeruleusTait.world.preview.client.gui;

/**
 * Spawn-score coloring shared by the analysis overview panel and the seed
 * comparison screen. The same three colors also mark analysis status
 * (running/paused/failed) and error lines.
 */
public final class ScoreColors {
    public static final int GOOD = 0xFF55FF55;
    public static final int MEDIUM = 0xFFFFFF55;
    public static final int BAD = 0xFFFF5555;

    private ScoreColors() {
    }

    /** Score coloring: green >= 70, yellow >= 40, red below. */
    public static int scoreColor(int score) {
        if (score >= 70) {
            return GOOD;
        }
        if (score >= 40) {
            return MEDIUM;
        }
        return BAD;
    }
}
