package caeruleusTait.world.preview.backend.export;

import caeruleusTait.world.preview.backend.export.AnalysisReportExporter.ReportInput;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisReportExporterTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final AnalysisReportExporter exporter = new AnalysisReportExporter();

    @TempDir
    Path tempDir;

    private static ReportInput sampleInput() {
        LinkedHashMap<String, long[]> biomes = new LinkedHashMap<>();
        // Deliberately out of count order: output must be sorted desc by count.
        biomes.put("Plains", new long[]{40});
        biomes.put("Mountains", new long[]{60});
        biomes.put("River", new long[]{0});
        return new ReportInput(
                "12345",
                "minecraft:overworld",
                "0,0 -> 127,127",
                100, 100, 1.0,
                biomes,
                OptionalInt.of(40),
                OptionalInt.of(180),
                OptionalDouble.of(90.5),
                OptionalDouble.of(88.0),
                OptionalDouble.of(12.25),
                OptionalDouble.of(0.5),
                OptionalDouble.of(3.75),
                0.25);
    }

    @Test
    void legacyConstructorDefaultsContextIdToUnknown() {
        String json = exporter.buildJson(sampleInput(), GSON);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("unknown", root.get("contextId").getAsString());
    }

    @Test
    void jsonCarriesProvidedContextId() {
        LinkedHashMap<String, long[]> biomes = new LinkedHashMap<>();
        biomes.put("Plains", new long[]{10});
        ReportInput input = new ReportInput(
                "777", "minecraft:overworld", "0,0 -> 15,15",
                16, 16, 1.0, biomes,
                OptionalInt.of(40), OptionalInt.of(60),
                OptionalDouble.of(50), OptionalDouble.of(50),
                OptionalDouble.of(1), OptionalDouble.of(0.5), OptionalDouble.of(2),
                0.5,
                "a1b2c3d4e5f6");
        String json = exporter.buildJson(input, GSON);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("a1b2c3d4e5f6", root.get("contextId").getAsString());
    }

    @Test
    void csvHasHeaderAndDescendingRowsWithFormattedShare() {
        String csv = exporter.buildCsv(sampleInput());
        String[] lines = csv.split("\n", -1);
        assertEquals("biome,count,share_percent", lines[0]);
        assertEquals("Mountains,60,60.00", lines[1]);
        assertEquals("Plains,40,40.00", lines[2]);
        assertEquals("River,0,0.00", lines[3]);
        // LF line endings, no BOM, trailing newline
        assertTrue(csv.endsWith("\n"));
        assertFalse(csv.endsWith("\r\n"));
        assertFalse(csv.startsWith("\uFEFF"));
        assertEquals(5, lines.length); // header + 3 rows + trailing empty element
    }

    @Test
    void csvWithNoPresentSamplesIsHeaderOnly() {
        ReportInput input = new ReportInput(
                "1", "minecraft:the_nether", "0,0 -> 1,1",
                50, 0, 0.0,
                new LinkedHashMap<>(),
                OptionalInt.empty(), OptionalInt.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), 0.0);
        assertEquals("biome,count,share_percent\n", exporter.buildCsv(input));
    }

    @Test
    void csvEscapesCommasInBiomeNames() {
        LinkedHashMap<String, long[]> biomes = new LinkedHashMap<>();
        biomes.put("Weird, biome", new long[]{10});
        ReportInput input = new ReportInput(
                "1", "minecraft:overworld", "0,0 -> 1,1",
                10, 10, 1.0, biomes,
                OptionalInt.empty(), OptionalInt.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), 0.0);
        assertEquals("biome,count,share_percent\n\"Weird, biome\",10,100.00\n",
                exporter.buildCsv(input));
    }

    @Test
    void jsonContainsStatsAndSortedBiomeShares() {
        String json = exporter.buildJson(sampleInput(), GSON);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("12345", root.get("seed").getAsString());
        assertEquals("minecraft:overworld", root.get("dimension").getAsString());
        assertEquals("0,0 -> 127,127", root.get("region").getAsString());
        assertEquals(1.0, root.get("coverage").getAsDouble(), 1e-9);
        assertEquals(100L, root.get("sampleCounts").getAsJsonObject().get("expected").getAsLong());
        assertEquals(100L, root.get("sampleCounts").getAsJsonObject().get("present").getAsLong());

        JsonObject heightStats = root.get("heightStats").getAsJsonObject();
        assertEquals(40, heightStats.get("min").getAsInt());
        assertEquals(180, heightStats.get("max").getAsInt());
        assertEquals(90.5, heightStats.get("mean").getAsDouble(), 1e-9);
        assertEquals(88.0, heightStats.get("median").getAsDouble(), 1e-9);
        assertEquals(12.25, heightStats.get("stddev").getAsDouble(), 1e-9);

        JsonObject slopeStats = root.get("slopeStats").getAsJsonObject();
        assertEquals(0.5, slopeStats.get("mean").getAsDouble(), 1e-9);
        assertEquals(3.75, slopeStats.get("max").getAsDouble(), 1e-9);

        assertEquals(0.25, root.get("flatRatio").getAsDouble(), 1e-9);

        var biomes = root.get("biomes").getAsJsonArray();
        assertEquals(3, biomes.size());
        assertEquals("Mountains", biomes.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(60L, biomes.get(0).getAsJsonObject().get("count").getAsLong());
        assertEquals(60.0, biomes.get(0).getAsJsonObject().get("sharePercent").getAsDouble(), 1e-9);
        assertEquals("Plains", biomes.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals(40.0, biomes.get(1).getAsJsonObject().get("sharePercent").getAsDouble(), 1e-9);
        assertEquals("River", biomes.get(2).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void jsonHeightStatsAreNullWhenAbsent() {
        ReportInput input = new ReportInput(
                "1", "minecraft:overworld", "0,0 -> 1,1",
                10, 10, 1.0, new LinkedHashMap<>(),
                OptionalInt.empty(), OptionalInt.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), 0.0);
        JsonObject root = JsonParser.parseString(exporter.buildJson(input, GSON)).getAsJsonObject();
        JsonObject heightStats = root.get("heightStats").getAsJsonObject();
        assertTrue(heightStats.get("min").isJsonNull());
        assertTrue(heightStats.get("stddev").isJsonNull());
        assertTrue(root.get("slopeStats").getAsJsonObject().get("mean").isJsonNull());
        assertTrue(root.get("biomes").getAsJsonArray().isEmpty());
    }

    @Test
    void writeCreatesCsvAndJsonMatchingBuiltContent() throws Exception {
        ReportInput input = sampleInput();
        List<Path> written = exporter.write(input, tempDir.resolve("nested/reports"), "analysis_test", GSON);

        assertEquals(2, written.size());
        Path csv = tempDir.resolve("nested/reports/analysis_test.csv");
        Path json = tempDir.resolve("nested/reports/analysis_test.json");
        assertEquals(csv, written.get(0));
        assertEquals(json, written.get(1));
        assertTrue(Files.exists(csv));
        assertTrue(Files.exists(json));

        assertEquals(exporter.buildCsv(input), Files.readString(csv));
        assertEquals(exporter.buildJson(input, GSON), Files.readString(json));
        // Atomic writes must not leave temp files behind
        try (var files = Files.list(tempDir.resolve("nested/reports"))) {
            assertEquals(2, files.count());
        }
    }
}
