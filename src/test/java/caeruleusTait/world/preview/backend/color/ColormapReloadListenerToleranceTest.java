package caeruleusTait.world.preview.backend.color;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-entry tolerance of the colormap reload (the 1.5.4 claim: invalid entries
 * are skipped and the rest of the file still loads).  A malformed entry must
 * not let the exception from {@link ColorMap}'s constructor escape and abort
 * the whole colormap reload.
 */
class ColormapReloadListenerToleranceTest {

    private static final Gson GSON = new GsonBuilder().create();

    private static boolean add(PreviewMappingData target, String key, String json) {
        return ColormapReloadListener.tryAddColormap(
                target, Identifier.parse(key), JsonParser.parseString(json), GSON);
    }

    private static int colormapCount(PreviewMappingData target) {
        return target.generateMapData(Set.of(), Set.of(), Set.of(), Set.of()).colorMaps().size();
    }

    @Test
    void validEntryIsAdded() {
        PreviewMappingData target = new PreviewMappingData();

        assertTrue(add(target, "minecraft:test", "{\"name\":\"t\",\"data\":[[0,0,0],[1,1,1]]}"),
                "a well-formed entry must be added");
        assertEquals(1, colormapCount(target));
    }

    @Test
    void nullDataIsSkippedWithoutThrowing() {
        PreviewMappingData target = new PreviewMappingData();

        assertDoesNotThrow(() -> assertFalse(add(target, "minecraft:test", "{\"name\":\"t\"}"),
                "data == null must be skipped (ColorMap would NPE on data.size())"));
        assertEquals(0, colormapCount(target));
    }

    @Test
    void rowWithTwoElementsIsSkipped() {
        PreviewMappingData target = new PreviewMappingData();

        assertDoesNotThrow(() -> assertFalse(add(target, "minecraft:test", "{\"name\":\"t\",\"data\":[[0,0],[1,1,1]]}"),
                "a row without exactly 3 elements must be skipped"));
        assertEquals(0, colormapCount(target));
    }

    @Test
    void outOfRangeComponentIsSkipped() {
        PreviewMappingData target = new PreviewMappingData();

        assertDoesNotThrow(() -> assertFalse(add(target, "minecraft:test", "{\"name\":\"t\",\"data\":[[0,0,0],[1.5,1,1]]}"),
                "components outside [0,1] must be skipped"));
        assertEquals(0, colormapCount(target));
    }

    @Test
    void fewerThanTwoRowsIsSkipped() {
        PreviewMappingData target = new PreviewMappingData();

        assertDoesNotThrow(() -> assertFalse(add(target, "minecraft:test", "{\"name\":\"t\",\"data\":[[0,0,0]]}"),
                "a map with fewer than 2 rows must be skipped"));
        assertEquals(0, colormapCount(target));
    }

    @Test
    void malformedTypeShapeIsSkipped() {
        PreviewMappingData target = new PreviewMappingData();

        assertDoesNotThrow(() -> assertFalse(add(target, "minecraft:test", "{\"name\":\"t\",\"data\":\"not-a-list\"}"),
                "a JSON shape Gson cannot map must be skipped"));
        assertEquals(0, colormapCount(target));
    }

    @Test
    void malformedEntriesDoNotAbortTheRemainingOnes() {
        PreviewMappingData target = new PreviewMappingData();

        assertTrue(add(target, "minecraft:a", "{\"name\":\"a\",\"data\":[[0,0,0],[1,1,1]]}"));
        assertDoesNotThrow(() -> {
            add(target, "minecraft:bad", "{\"name\":\"bad\"}");
            add(target, "minecraft:bad2", "{\"name\":\"bad2\",\"data\":[[0,0,0],[9,9,9]]}");
            add(target, "minecraft:bad3", "{\"name\":\"bad3\",\"data\":[[0,0]]}");
        });
        assertTrue(add(target, "minecraft:b", "{\"name\":\"b\",\"data\":[[1,0,0],[0,1,0]]}"));

        assertEquals(2, colormapCount(target), "the valid entries must survive");
    }
}
