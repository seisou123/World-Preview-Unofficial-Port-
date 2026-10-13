package caeruleusTait.world.preview.backend.lang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Language parity guard over the shipped lang files: every language must
 * cover exactly the en_us key set in the same order, and every value must
 * carry the same format placeholders, so no locale can ship a half-
 * translated screen or a value whose placeholder count crashes
 * {@code String.format} when the hover/tooltip renders it.
 * Pure resource-file test: no Minecraft classes involved.
 */
class LangParityTest {

    /** Placeholder tokens that must survive translation verbatim: %s %d %f %1$s %% ... */
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[sdf]|%%");

    private static final String LANG_DIR = "/assets/world_preview/lang/";
    private static final String[] TRANSLATED = {"zh_cn", "ru_ru", "pt_pt"};

    private static JsonObject load(String lang) {
        InputStream in = LangParityTest.class.getResourceAsStream(LANG_DIR + lang + ".json");
        assertNotNull(in, lang + ".json must ship with the mod resources");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            assertTrue(parsed.isJsonObject(), lang + ".json must be a JSON object");
            return parsed.getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError("failed to load " + lang + ".json", e);
        }
    }

    private static List<String> placeholders(String value) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    @Test
    void shippedLanguagesMatchEnUs() {
        JsonObject en = load("en_us");
        List<String> enKeys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : en.entrySet()) {
            assertTrue(entry.getValue().isJsonPrimitive(), "en_us." + entry.getKey() + " must be a string");
            enKeys.add(entry.getKey());
        }
        assertTrue(enKeys.size() >= 400, "en_us looks truncated: " + enKeys.size() + " keys");

        for (String lang : TRANSLATED) {
            JsonObject other = load(lang);
            List<String> otherKeys = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : other.entrySet()) {
                otherKeys.add(entry.getKey());
            }
            assertEquals(enKeys, otherKeys, lang + " key set/order must match en_us exactly");

            List<String> placeholderDrift = new ArrayList<>();
            List<String> formatDrift = new ArrayList<>();
            for (String key : enKeys) {
                String enValue = en.get(key).getAsString();
                String value = other.get(key).getAsString();
                if (!placeholders(enValue).equals(placeholders(value))) {
                    placeholderDrift.add(key);
                }
                if (count(enValue, '\u00A7') != count(value, '\u00A7') || count(enValue, '\n') != count(value, '\n')) {
                    formatDrift.add(key);
                }
            }
            assertTrue(placeholderDrift.isEmpty(), lang + " placeholder drift in " + placeholderDrift);
            assertTrue(formatDrift.isEmpty(), lang + " section-code/newline drift in " + formatDrift);
        }
    }

    private static int count(String value, char ch) {
        int n = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == ch) n++;
        }
        return n;
    }
}
