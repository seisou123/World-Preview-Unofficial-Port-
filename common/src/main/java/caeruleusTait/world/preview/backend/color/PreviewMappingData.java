// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.color;

import it.unimi.dsi.fastutil.objects.Object2ShortOpenHashMap;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

public class PreviewMappingData {
    private final Map<String, ColorEntry> resourceOnlyColorMappingData = new HashMap<>();
    private final Map<String, ColorEntry> colorMappingData = new HashMap<>();

    private final Map<String, StructureEntry> structMappingData = new HashMap<>();

    private final List<PreviewData.HeightmapPresetData> heightmapPresets = new ArrayList<>();
    private final List<ColorMap> colorMaps = new ArrayList<>();

    private static final MessageDigest sha1;

    static {
        try {
            sha1 = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Generate a deterministic color from a biome identifier string.
     * Uses a ThreadLocal MessageDigest to avoid contention on the shared
     * static sha1 instance, which is not thread-safe.
     */
    private static int hashColor(String biome) {
        final MessageDigest md = THREAD_LOCAL_SHA1.get();
        md.reset();
        byte[] hash = md.digest(biome.getBytes(StandardCharsets.UTF_8));
        int color = 0;
        for (int i = 0; i < Integer.BYTES && i < hash.length; ++i) {
            color |= (hash[i] & 0xFF) << (i * 8);
        }
        return color & 0xFFFFFF;
    }

    private static final ThreadLocal<MessageDigest> THREAD_LOCAL_SHA1 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    });

    void clearBiomes() {
        colorMappingData.clear();
        resourceOnlyColorMappingData.clear();
    }

    void clearStructures() {
        structMappingData.clear();
    }

    void clearColorMappings() {
        colorMaps.clear();
    }

    void clearHeightmapPresets() {
        heightmapPresets.clear();
    }

    public void makeBiomeResourceOnlyBackup() {
        resourceOnlyColorMappingData.putAll(colorMappingData);
    }

    public void update(Map<Identifier, ColorEntry> newData) {
        colorMappingData.putAll(
                newData.entrySet()
                        .stream()
                        .collect(Collectors.toMap(x -> x.getKey().toString(), Map.Entry::getValue))
        );
    }

    public void updateStruct(Map<Identifier, StructureEntry> newData) {
        structMappingData.putAll(
                newData.entrySet()
                        .stream()
                        .collect(Collectors.toMap(x -> x.getKey().toString(), Map.Entry::getValue))
        );
    }

    public void addHeightmapPreset(PreviewData.HeightmapPresetData presetData) {
        heightmapPresets.add(presetData);
    }

    public void addColormap(ColorMap colorMap) {
        colorMaps.add(colorMap);
    }

    public PreviewData generateMapData(
            Set<Identifier> biomesSet,
            Set<Identifier> caveBiomesSet,
            Set<Identifier> structuresSet,
            Set<Identifier> displayByDefaultStructuresSet
    ) {
        List<String> biomes = biomesSet.stream().map(Identifier::toString).sorted().toList();
        List<String> structures = structuresSet.stream().map(Identifier::toString).sorted().toList();

        final Object2ShortOpenHashMap<String> biome2IdMap = new Object2ShortOpenHashMap<>();
        // Use -1 as the default return value so that "not found" can be
        // distinguished from a valid ID of 0.  The rendering code already
        // guards with `if (rawData >= 0)`, so -1 is rendered as black and
        // skipped in visibility tracking — preventing modded biomes that
        // are missing from the map from being silently mis-attributed to
        // biome ID 0.
        biome2IdMap.defaultReturnValue((short) -1);

        // Mirror biome2Id: unknown structure keys must not silently map to ID 0
        // (first registered structure), which would show the wrong icon/name.
        final Object2ShortOpenHashMap<String> struct2IdMap = new Object2ShortOpenHashMap<>();
        struct2IdMap.defaultReturnValue((short) -1);

        final PreviewData res = new PreviewData(
                new PreviewData.BiomeData[biomes.size()],
                new PreviewData.StructureData[structures.size()],
                biome2IdMap,
                struct2IdMap,
                heightmapPresets,
                colorMaps.stream().collect(Collectors.toMap(x -> x.key().toString(), x -> x))
        );

                for (short id = 0; id < biomes.size(); ++id) {
            final String biome = biomes.get(id);
            res.biome2Id().put(biome, id);
            final Identifier biomeRes = Identifier.parse(biome);

            ColorEntry color = colorMappingData.get(biome);
            if (color == null) {
                color = new ColorEntry();
                color.dataSource = PreviewData.DataSource.MISSING;
                // Use heuristic color resolver: categorises biomes by name
                // patterns (desert, snow, nether, etc.) and uses a controlled
                // HSV fallback, producing visually appropriate colors instead
                // of the previous SHA-1 hash which yielded random noise.
                color.color = BiomeColorResolver.resolveColor(biomeRes) & 0xFFFFFF;
                // Generate a human-readable display name from the biome ID path
                // (e.g. "snowy_taiga" -> "Snowy Taiga") instead of leaving null.
                color.name = BiomeColorResolver.resolveDisplayName(biomeRes);
            }

            ColorEntry resourceOnlyColor = resourceOnlyColorMappingData.get(biome);
            if (resourceOnlyColor == null) {
                resourceOnlyColor = color;
            }
            res.biomeId2BiomeData()[id] = new PreviewData.BiomeData(
                    id,
                    biomeRes,
                    color.color,
                    resourceOnlyColor.color,
                    color.cave.orElse(caveBiomesSet.contains(biomeRes)),
                    resourceOnlyColor.cave.orElse(caveBiomesSet.contains(biomeRes)),
                    color.name,
                    resourceOnlyColor.name,
                    color.dataSource
            );
        }

        for (short id = 0; id < structures.size(); ++id) {
            final String structTag = structures.get(id);
            res.struct2Id().put(structTag, id);

            StructureEntry structure = structMappingData.get(structTag);
            if (structure == null) {
                structure = new StructureEntry();
                structure.dataSource = PreviewData.DataSource.MISSING;
                structure.texture = "world_preview:textures/structure/unknown.png";
                structure.name = structTag;
                structure.showByDefault = Optional.empty();
            }

            Identifier structureRes = Identifier.parse(structTag);
            res.structId2StructData()[id] = new PreviewData.StructureData(
                    id,
                    structureRes,
                    structure.name,
                    structure.texture == null ? null : Identifier.parse(structure.texture),
                    structure.item == null ? null : Identifier.parse(structure.item),
                    structure.showByDefault.orElse(displayByDefaultStructuresSet.contains(structureRes)),
                    structure.dataSource
            );
        }

        return res;
    }

    public static class ColorEntry {
        public PreviewData.DataSource dataSource;
        public int color;
        public Optional<Boolean> cave = Optional.empty();
        public String name = null;

        public ColorEntry() {
        }

        public ColorEntry(PreviewData.DataSource dataSource, int color, boolean cave, String name) {
            this.dataSource = dataSource;
            this.color = color;
            this.cave = Optional.of(cave);
            this.name = name;
        }
    }

    public static class StructureEntry {
        public PreviewData.DataSource dataSource;
        public String name = null;
        public String texture = null;
        public String item = null;
        public Optional<Boolean> showByDefault = Optional.empty();
    }
}
