package caeruleusTait.world.preview.backend.color;

import it.unimi.dsi.fastutil.objects.Object2ShortMap;
import it.unimi.dsi.fastutil.objects.Object2ShortOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeIdLookupTest {
    private Object2ShortMap<String> biome2Id;

    @BeforeEach
    void setUp() {
        biome2Id = new Object2ShortOpenHashMap<>();
        biome2Id.defaultReturnValue((short) -1);
        biome2Id.put("minecraft:plains", (short) 3);
        biome2Id.put("minecraft:ocean", (short) 7);
    }

    @Test
    void idFromMapAndStringReturnsMappedId() {
        assertEquals((short) 3, BiomeIdLookup.idFrom(biome2Id, "minecraft:plains"));
        assertEquals((short) 7, BiomeIdLookup.idFrom(biome2Id, "minecraft:ocean"));
    }

    @Test
    void idFromMapAndStringReturnsDefaultForUnknown() {
        assertEquals((short) -1, BiomeIdLookup.idFrom(biome2Id, "minecraft:missing"));
    }

    @Test
    void idFromMapAndIdentifierReturnsMappedId() {
        assertEquals((short) 3, BiomeIdLookup.idFrom(biome2Id, Identifier.parse("minecraft:plains")));
    }

    @Test
    void idFromMapAndResourceKeyReturnsMappedId() {
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:ocean"));
        assertEquals((short) 7, BiomeIdLookup.idFrom(biome2Id, key));
    }

    @Test
    void idFromPreviewDataDelegatesToBiome2IdMap() {
        PreviewData previewData = new PreviewData(
                new PreviewData.BiomeData[0],
                new PreviewData.StructureData[0],
                biome2Id,
                new Object2ShortOpenHashMap<>(),
                List.of(),
                Map.of()
        );

        assertEquals((short) 3, BiomeIdLookup.idFrom(previewData, "minecraft:plains"));
        assertEquals((short) 7, BiomeIdLookup.idFrom(previewData, Identifier.parse("minecraft:ocean")));
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:plains"));
        assertEquals((short) 3, BiomeIdLookup.idFrom(previewData, key));
    }

    @Test
    void rejectsNullMap() {
        assertThrows(NullPointerException.class, () -> BiomeIdLookup.idFrom((Object2ShortMap<String>) null, "minecraft:plains"));
    }

    @Test
    void rejectsNullPreviewData() {
        assertThrows(NullPointerException.class, () -> BiomeIdLookup.idFrom((PreviewData) null, "minecraft:plains"));
        assertThrows(NullPointerException.class, () -> BiomeIdLookup.idFrom(null, testHolder("minecraft:plains"), null));
    }

    // ==== Holder identity-cache path ====

    private PreviewData previewData() {
        return new PreviewData(
                new PreviewData.BiomeData[0],
                new PreviewData.StructureData[0],
                biome2Id,
                new Object2ShortOpenHashMap<>(),
                List.of(),
                Map.of()
        );
    }

    /**
     * Stand-alone registry-free reference holder. Holder.Reference inherits
     * identity equals/hashCode, matching how live preview workers receive the
     * registry's single holder instance per biome.
     */
    private static Holder<Biome> testHolder(String id) {
        return Holder.Reference.createStandAlone(
                new HolderOwner<Biome>() {},
                ResourceKey.create(Registries.BIOME, Identifier.parse(id)));
    }

    @Test
    void idFromHolderRepeatedSameInstanceHitsCache() {
        PreviewData previewData = previewData();
        Holder<Biome> plains = testHolder("minecraft:plains");
        Map<Holder<Biome>, Short> cache = new HashMap<>();

        // First lookup resolves through the string path and backfills.
        assertEquals((short) 3, BiomeIdLookup.idFrom(previewData, plains, cache));
        assertEquals((short) 3, cache.get(plains));

        // Cache hit proof: mutate the underlying map; the cached id must win
        // for the SAME holder instance.
        biome2Id.put("minecraft:plains", (short) 99);
        assertEquals((short) 3, BiomeIdLookup.idFrom(previewData, plains, cache));
        assertEquals((short) 3, cache.get(plains));

        // A different instance for the same biome is not a cache hit: it
        // resolves through the string path and backfills its own entry.
        Holder<Biome> plainsAgain = testHolder("minecraft:plains");
        assertEquals((short) 99, BiomeIdLookup.idFrom(previewData, plainsAgain, cache));
        assertEquals((short) 99, cache.get(plainsAgain));
        assertEquals(2, cache.size());
    }

    @Test
    void idFromHolderNullCacheFallsBackToStringPathEveryTime() {
        PreviewData previewData = previewData();
        Holder<Biome> ocean = testHolder("minecraft:ocean");

        assertEquals((short) 7, BiomeIdLookup.idFrom(previewData, ocean));
        biome2Id.put("minecraft:ocean", (short) 42);
        // No cache: each lookup re-reads the current map value.
        assertEquals((short) 42, BiomeIdLookup.idFrom(previewData, ocean));
    }

    @Test
    void idFromHolderUnknownBiomeReturnsMinusOneWithoutThrowing() {
        PreviewData previewData = previewData();
        Holder<Biome> missing = testHolder("minecraft:missing_void_biome");

        assertEquals((short) -1, BiomeIdLookup.idFrom(previewData, missing));
        Map<Holder<Biome>, Short> cache = new HashMap<>();
        assertEquals((short) -1, BiomeIdLookup.idFrom(previewData, missing, cache));
        // Misses are backfilled too, so repeat lookups stay cache hits.
        assertEquals((short) -1, cache.get(missing));
        assertEquals((short) -1, BiomeIdLookup.idFrom(previewData, missing, cache));
    }

    @Test
    void idFromHolderNullHolderReturnsMinusOne() {
        PreviewData previewData = previewData();
        assertEquals((short) -1, BiomeIdLookup.idFrom(previewData, (Holder<Biome>) null));
        Map<Holder<Biome>, Short> cache = new HashMap<>();
        assertEquals((short) -1, BiomeIdLookup.idFrom(previewData, (Holder<Biome>) null, cache));
        assertNull(cache.get(null));
        assertTrue(cache.isEmpty());
    }
}
