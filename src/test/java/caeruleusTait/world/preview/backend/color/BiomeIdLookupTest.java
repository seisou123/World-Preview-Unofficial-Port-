package caeruleusTait.world.preview.backend.color;

import it.unimi.dsi.fastutil.objects.Object2ShortMap;
import it.unimi.dsi.fastutil.objects.Object2ShortOpenHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    }
}
