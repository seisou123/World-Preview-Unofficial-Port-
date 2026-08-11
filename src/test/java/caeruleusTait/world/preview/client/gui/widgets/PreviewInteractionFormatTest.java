package caeruleusTait.world.preview.client.gui.widgets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure formatting helpers for preview clipboard / HUD (mirrors PreviewDisplay rules).
 */
class PreviewInteractionFormatTest {
    @Test
    void plainCoords_whenNoHeight() {
        assertEquals("10 ~ -20", formatCoords(10, Short.MIN_VALUE, -20, true));
    }

    @Test
    void tpCommand_whenHeightKnownAndNotPlain() {
        assertEquals("/tp @s 10 64 -20", formatCoords(10, (short) 64, -20, false));
    }

    @Test
    void plainCoords_whenForcedEvenWithHeight() {
        assertEquals("10 64 -20", formatCoords(10, (short) 64, -20, true));
    }

    @Test
    void panStep_scalesWithBlockScale() {
        assertTrue(panStep(1) >= 16);
        assertEquals(64, panStep(4));
    }

    /** Same rules as PreviewDisplay right-click copy. */
    static String formatCoords(int x, short height, int z, boolean plain) {
        String yPart = height == Short.MIN_VALUE ? "~" : Integer.toString(height);
        if (plain || height == Short.MIN_VALUE) {
            return String.format("%s %s %s", x, yPart, z);
        }
        return String.format("/tp @s %s %s %s", x, yPart, z);
    }

    static int panStep(int scaleBlockPos) {
        return Math.max(16, 16 * scaleBlockPos);
    }
}
