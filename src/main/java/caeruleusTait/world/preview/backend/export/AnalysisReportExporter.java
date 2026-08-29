package caeruleusTait.world.preview.backend.export;

import caeruleusTait.world.preview.util.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Builds and writes region analysis reports (CSV biome table + JSON summary).
 * <p>
 * Pure output formatting: no Minecraft types, safe to unit test.
 * CSV uses LF line endings without BOM; JSON is written with the Gson
 * instance supplied by the caller (expected to use pretty printing).
 * </p>
 */
public final class AnalysisReportExporter {

    /** Immutable snapshot of everything that goes into a report. */
    public record ReportInput(
            String seed,
            String dimension,
            String regionDescription,
            long expectedSamples,
            long presentSamples,
            double coverage,
            LinkedHashMap<String, long[]> biomeTable,
            OptionalInt minHeight,
            OptionalInt maxHeight,
            OptionalDouble meanHeight,
            OptionalDouble medianHeight,
            OptionalDouble standardDeviation,
            OptionalDouble meanSlope,
            OptionalDouble maxSlope,
            double flatRatio) {

        public ReportInput {
            seed = seed == null ? "unknown" : seed;
            dimension = dimension == null ? "unknown" : dimension;
            regionDescription = regionDescription == null ? "" : regionDescription;
            biomeTable = copyBiomeTable(biomeTable);
            minHeight = minHeight == null ? OptionalInt.empty() : minHeight;
            maxHeight = maxHeight == null ? OptionalInt.empty() : maxHeight;
            meanHeight = meanHeight == null ? OptionalDouble.empty() : meanHeight;
            medianHeight = medianHeight == null ? OptionalDouble.empty() : medianHeight;
            standardDeviation = standardDeviation == null ? OptionalDouble.empty() : standardDeviation;
            meanSlope = meanSlope == null ? OptionalDouble.empty() : meanSlope;
            maxSlope = maxSlope == null ? OptionalDouble.empty() : maxSlope;
        }

        private static LinkedHashMap<String, long[]> copyBiomeTable(LinkedHashMap<String, long[]> source) {
            LinkedHashMap<String, long[]> copy = new LinkedHashMap<>();
            if (source != null) {
                for (Map.Entry<String, long[]> e : source.entrySet()) {
                    copy.put(e.getKey(), e.getValue() == null ? new long[]{0L} : e.getValue().clone());
                }
            }
            return copy;
        }

        /** Biome rows sorted by descending count; shared by CSV and JSON output. */
        private List<BiomeRow> sortedRows() {
            List<Map.Entry<String, long[]>> entries = new ArrayList<>(biomeTable.entrySet());
            entries.sort(Map.Entry.comparingByValue(Comparator.comparingLong((long[] counts) -> counts[0]).reversed()));
            List<BiomeRow> rows = new ArrayList<>(entries.size());
            for (Map.Entry<String, long[]> e : entries) {
                rows.add(new BiomeRow(e.getKey(), e.getValue()[0], share(e.getValue()[0], presentSamples)));
            }
            return rows;
        }
    }

    /** One formatted biome row; count desc order, share in percent. */
    private record BiomeRow(String name, long count, double sharePercent) {}

    public String buildCsv(ReportInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("biome,count,share_percent\n");
        if (input.presentSamples() <= 0) {
            return sb.toString();
        }
        for (BiomeRow row : input.sortedRows()) {
            sb.append(escapeCsv(row.name())).append(',')
                    .append(row.count()).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", row.sharePercent())).append('\n');
        }
        return sb.toString();
    }

    public String buildJson(ReportInput input, Gson gson) {
        JsonObject root = new JsonObject();
        root.addProperty("seed", input.seed());
        root.addProperty("dimension", input.dimension());
        root.addProperty("region", input.regionDescription());
        root.addProperty("coverage", input.coverage());

        JsonObject sampleCounts = new JsonObject();
        sampleCounts.addProperty("expected", input.expectedSamples());
        sampleCounts.addProperty("present", input.presentSamples());
        root.add("sampleCounts", sampleCounts);

        JsonObject heightStats = new JsonObject();
        heightStats.addProperty("min", boxed(input.minHeight()));
        heightStats.addProperty("max", boxed(input.maxHeight()));
        heightStats.addProperty("mean", boxed(input.meanHeight()));
        heightStats.addProperty("median", boxed(input.medianHeight()));
        heightStats.addProperty("stddev", boxed(input.standardDeviation()));
        root.add("heightStats", heightStats);

        JsonObject slopeStats = new JsonObject();
        slopeStats.addProperty("mean", boxed(input.meanSlope()));
        slopeStats.addProperty("max", boxed(input.maxSlope()));
        root.add("slopeStats", slopeStats);

        root.addProperty("flatRatio", input.flatRatio());

        JsonArray biomes = new JsonArray();
        for (BiomeRow row : input.sortedRows()) {
            JsonObject biome = new JsonObject();
            biome.addProperty("name", row.name());
            biome.addProperty("count", row.count());
            biome.addProperty("sharePercent", row.sharePercent());
            biomes.add(biome);
        }
        root.add("biomes", biomes);

        // Null-valued members (absent stats) must survive serialization even though
        // Gson drops them by default; deriving keeps the caller's formatting settings.
        return gson.newBuilder().serializeNulls().create().toJson(root);
    }

    public List<Path> write(ReportInput input, Path outputDir, String baseName, Gson gson) throws IOException {
        Files.createDirectories(outputDir);
        Path csvPath = outputDir.resolve(baseName + ".csv");
        Path jsonPath = outputDir.resolve(baseName + ".json");
        AtomicFiles.writeStringAtomic(csvPath, buildCsv(input));
        AtomicFiles.writeStringAtomic(jsonPath, buildJson(input, gson));
        return List.of(csvPath, jsonPath);
    }

    private static double share(long count, long presentSamples) {
        if (presentSamples <= 0) {
            return 0.0;
        }
        return count * 100.0 / presentSamples;
    }

    private static Integer boxed(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    private static Double boxed(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }

    /**
     * Minimal CSV field escaping: quote fields containing separators, quotes or newlines.
     */
    private static String escapeCsv(String field) {
        if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0 || field.indexOf('\n') >= 0
                || field.indexOf('\r') >= 0) {
            return '"' + field.replace("\"", "\"\"") + '"';
        }
        return field;
    }
}
