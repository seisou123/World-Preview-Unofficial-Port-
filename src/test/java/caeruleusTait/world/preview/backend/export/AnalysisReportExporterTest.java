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
        // First table unchanged: header + biome rows sorted desc by count.
        assertEquals("biome,count,share_percent", lines[0]);
        assertEquals("Mountains,60,60.00", lines[1]);
        assertEquals("Plains,40,40.00", lines[2]);
        assertEquals("River,0,0.00", lines[3]);
        // Summary metrics section appended after a blank separator line
        // (legacy inputs default the extended metrics to 0).
        assertEquals("", lines[4]);
        assertEquals("metric,value", lines[5]);
        assertEquals("water_share,0.0000", lines[6]);
        assertEquals("shannon_diversity,0.0000", lines[7]);
        assertEquals("effective_biome_count,0.0000", lines[8]);
        // LF line endings, no BOM, trailing newline
        assertTrue(csv.endsWith("\n"));
        assertFalse(csv.endsWith("\r\n"));
        assertFalse(csv.startsWith("\uFEFF"));
        assertEquals(10, lines.length); // header + 3 rows + blank + metric header + 3 metrics + trailing empty
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
        assertEquals("biome,count,share_percent\n"
                        + "\"Weird, biome\",10,100.00\n"
                        + "\n"
                        + "metric,value\n"
                        + "water_share,0.0000\n"
                        + "shannon_diversity,0.0000\n"
                        + "effective_biome_count,0.0000\n",
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

    @Test
    void jsonContainsExtendedMetrics() {
        LinkedHashMap<String, long[]> biomes = new LinkedHashMap<>();
        biomes.put("plains", new long[]{10});
        LinkedHashMap<String, Long> terrain = new LinkedHashMap<>();
        terrain.put("PLAINS", 10L);
        LinkedHashMap<String, Long> structs = new LinkedHashMap<>();
        structs.put("village", 350L);
        ReportInput input = new ReportInput(
                "123", "minecraft:overworld", "-1000,-1000 -> 1000,1000",
                100, 100, 1.0, biomes,
                OptionalInt.of(60), OptionalInt.of(90),
                OptionalDouble.of(70), OptionalDouble.of(70),
                OptionalDouble.of(5), OptionalDouble.of(0.5), OptionalDouble.of(9),
                0.4, "ctx",
                0.25, 0.56, 1.75, terrain, structs,
                new int[]{1, 2, 3}, 60);
        String json = exporter.buildJson(input, GSON);
        assertTrue(json.contains("\"waterShare\": 0.25"));
        assertTrue(json.contains("shannonDiversity"));
        assertTrue(json.contains("village"));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(0.25, root.get("waterShare").getAsDouble(), 1e-9);
        JsonObject insights = root.get("insights").getAsJsonObject();
        assertEquals(0.56, insights.get("shannonDiversity").getAsDouble(), 1e-9);
        assertEquals(1.75, insights.get("effectiveBiomeCount").getAsDouble(), 1e-9);
        JsonObject terrainEntry = root.get("terrain").getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("PLAINS", terrainEntry.get("category").getAsString());
        assertEquals(10L, terrainEntry.get("count").getAsLong());
        JsonObject structEntry = root.get("nearestStructures").getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("village", structEntry.get("structure").getAsString());
        assertEquals(350L, structEntry.get("distanceBlocks").getAsLong());
        JsonObject hist = root.get("heightHistogram").getAsJsonObject();
        assertEquals(60, hist.get("minY").getAsInt());
        assertEquals(3, hist.get("counts").getAsJsonArray().size());
        assertEquals(1, hist.get("counts").getAsJsonArray().get(0).getAsInt());

        String csv = exporter.buildCsv(input);
        assertTrue(csv.contains("water_share"));
    }

    @Test
    void csvAppendsSummarySectionAfterBiomeTable() {
        LinkedHashMap<String, long[]> biomes = new LinkedHashMap<>();
        biomes.put("plains", new long[]{10});
        LinkedHashMap<String, Long> terrain = new LinkedHashMap<>();
        terrain.put("PLAINS", 10L);
        ReportInput input = new ReportInput(
                "123", "minecraft:overworld", "-1000,-1000 -> 1000,1000",
                100, 100, 1.0, biomes,
                OptionalInt.of(60), OptionalInt.of(90),
                OptionalDouble.of(70), OptionalDouble.of(70),
                OptionalDouble.of(5), OptionalDouble.of(0.5), OptionalDouble.of(9),
                0.4, "ctx",
                0.25, 0.56, 1.75, terrain, new LinkedHashMap<>(),
                new int[]{1, 2, 3}, 60);
        assertEquals("biome,count,share_percent\n"
                + "plains,10,10.00\n"
                + "\n"
                + "metric,value\n"
                + "water_share,0.2500\n"
                + "shannon_diversity,0.5600\n"
                + "effective_biome_count,1.7500\n", exporter.buildCsv(input));
    }

    @Test
    void legacyConstructorDefaultsExtendedMetrics() {
        String json = exporter.buildJson(sampleInput(), GSON);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(0.0, root.get("waterShare").getAsDouble(), 1e-9);
        JsonObject insights = root.get("insights").getAsJsonObject();
        assertEquals(0.0, insights.get("shannonDiversity").getAsDouble(), 1e-9);
        assertEquals(0.0, insights.get("effectiveBiomeCount").getAsDouble(), 1e-9);
        assertTrue(root.get("terrain").getAsJsonArray().isEmpty());
        assertTrue(root.get("nearestStructures").getAsJsonArray().isEmpty());
        JsonObject hist = root.get("heightHistogram").getAsJsonObject();
        assertEquals(0, hist.get("minY").getAsInt());
        assertTrue(hist.get("counts").getAsJsonArray().isEmpty());
    }

    @Test
    void nonFiniteAndNullExtendedInputsNormalize() {
        LinkedHashMap<String, long[]> biomes = new LinkedHashMap<>();
        biomes.put("plains", new long[]{10});
        ReportInput input = new ReportInput(
                "1", "minecraft:overworld", "0,0 -> 1,1",
                10, 10, 1.0, biomes,
                OptionalInt.empty(), OptionalInt.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), 0.0, null,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                null, null, null, 0);
        String json = exporter.buildJson(input, GSON);
        assertTrue(json.contains("\"waterShare\": 0.0"));
        assertFalse(json.contains("NaN"));
        assertFalse(json.contains("Infinity"));
        String csv = exporter.buildCsv(input);
        assertTrue(csv.contains("water_share,0.0000"));
        assertTrue(csv.contains("shannon_diversity,0.0000"));
        assertTrue(csv.contains("effective_biome_count,0.0000"));
    }
}
