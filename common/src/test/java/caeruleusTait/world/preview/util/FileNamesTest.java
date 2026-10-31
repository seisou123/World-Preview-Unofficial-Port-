package caeruleusTait.world.preview.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileNamesTest {

    @Test
    void keepsSafeCharacters() {
        assertEquals("overworld", FileNames.sanitizeFileToken("overworld"));
        assertEquals("a-b.c_d0", FileNames.sanitizeFileToken("a-b.c_d0"));
    }

    @Test
    void replacesUnsafeCharactersWithUnderscores() {
        assertEquals("a_b__c", FileNames.sanitizeFileToken("a/b :c"));
        assertEquals("_____", FileNames.sanitizeFileToken("/////"));
        assertEquals("_123", FileNames.sanitizeFileToken(" 123"));
    }

    @Test
    void emptyInputFallsBackToUnknown() {
        assertEquals("unknown", FileNames.sanitizeFileToken(""));
    }
}
