// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import caeruleusTait.world.preview.client.gui.widgets.lists.StructuresList;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.server.RegistryLayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Builds the sidebar list entries (biomes and structures) from the preview
 * data plus registries. Extracted from {@link PreviewContainer}; the
 * container keeps only the widget updates around the returned arrays.
 */
final class SidebarEntryFactory {

    private static final TagKey<Biome> C_CAVE = TagKey.create(Registries.BIOME, new ResourceLocation("c", "caves"));
    private static final TagKey<Biome> C_IS_CAVE = TagKey.create(Registries.BIOME, new ResourceLocation("c", "is_cave"));
    private static final TagKey<Biome> FORGE_CAVE = TagKey.create(Registries.BIOME, new ResourceLocation("forge", "caves"));
    private static final TagKey<Biome> FORGE_IS_CAVE = TagKey.create(Registries.BIOME, new ResourceLocation("forge", "is_cave"));

    static Set<ResourceLocation> collectCaveBiomes(Registry<Biome> biomeRegistry) {
        Set<ResourceLocation> caveBiomes = new HashSet<>();
        for (TagKey<Biome> tagKey : List.of(C_CAVE, C_IS_CAVE, FORGE_CAVE, FORGE_IS_CAVE)) {
            caveBiomes.addAll(
                    StreamSupport.stream(biomeRegistry.getTagOrEmpty(tagKey).spliterator(), false)
                            .map(x -> x.unwrapKey().orElseThrow().location())
                            .toList()
            );
        }
        return caveBiomes;
    }

    static Set<ResourceLocation> collectKnownBiomes(Registry<Biome> biomeRegistry, @Nullable LevelStem levelStem) {
        // Merge biomes from both the biome registry AND the biome source's
        // possibleBiomes().  Some mods use custom biome sources or register
        // biomes in a different registry context, so biomeRegistry.keySet()
        // alone may not include every biome the source can actually generate.
        Set<ResourceLocation> allKnownBiomes = new HashSet<>(biomeRegistry.keySet());
        if (levelStem != null) {
            levelStem.generator().getBiomeSource().possibleBiomes().forEach(holder -> {
                holder.unwrapKey().ifPresent(key -> allKnownBiomes.add(key.location()));
            });
        }
        return allKnownBiomes;
    }

    static BiomesList.BiomeEntry[] buildBiomeEntries(BiomesList biomesList, WorldPreview worldPreview,
                                                      PreviewData previewData, Registry<Biome> biomeRegistry) {
        List<String> missing = Arrays.stream(previewData.biomeId2BiomeData())
                .filter(x -> x.dataSource() == PreviewData.DataSource.MISSING)
                .map(PreviewData.BiomeData::tag)
                .map(ResourceLocation::toString)
                .toList();
        worldPreview.writeMissingColors(missing);

        // Build a lookup from ResourceLocation -> Holder.Reference<Biome> for fast access.
        // Some biomes may be in the biome source but not in the biome registry
        // (e.g. when a mod uses a custom biome source or a different registry
        // context).  For those, we create a standalone Holder.Reference so they
        // still appear in the biome list and sidebar.
        Map<ResourceLocation, Holder.Reference<Biome>> registryHolders = biomeRegistry.holders()
                .collect(Collectors.toMap(x -> x.key().location(), x -> x));

        final BiomesList.BiomeEntry[] entries = new BiomesList.BiomeEntry[previewData.biomeId2BiomeData().length];
        for (PreviewData.BiomeData biomeData : previewData.biomeId2BiomeData()) {
            final short id = (short) biomeData.id();
            Holder.Reference<Biome> holder = registryHolders.get(biomeData.tag());
            if (holder == null) {
                // Biome is in the biome source but not in the registry.
                // Create a standalone reference so it can still be displayed.
                holder = Holder.Reference.createStandAlone((HolderOwner<Biome>) biomeRegistry, ResourceKey.create(Registries.BIOME, biomeData.tag()));
            }
            entries[id] = biomesList.createEntry(
                    holder, id,
                    biomeData.color(), biomeData.resourceOnlyColor(),
                    biomeData.isCave(), biomeData.resourceOnlyIsCave(),
                    biomeData.name(), biomeData.dataSource()
            );
        }
        Arrays.sort(entries, Comparator.comparing(BiomesList.BiomeEntry::id));
        return entries;
    }

    static StructuresList.StructureEntry[] buildStructureEntries(StructuresList structuresList, WorldPreview worldPreview,
                                                               PreviewData previewData, NativeImage[] structureIcons,
                                                               Registry<Structure> strucutreRegistry,
                                                               LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess) {
        List<String> missing = Arrays.stream(previewData.structId2StructData())
                .filter(x -> x.dataSource() == PreviewData.DataSource.MISSING)
                .map(PreviewData.StructureData::tag)
                .map(ResourceLocation::toString)
                .toList();
        worldPreview.writeMissingStructures(missing);

        Registry<Item> itemRegistry = layeredRegistryAccess.compositeAccess().registryOrThrow(Registries.ITEM);
        return strucutreRegistry.holders()
                .map(x -> {
                    final short id = previewData.struct2Id().getShort(x.key().location().toString());
                    if (id < 0 || id >= previewData.structId2StructData().length) {
                        return null;
                    }
                    final PreviewData.StructureData structureData = previewData.structId2StructData()[id];
                    return structuresList.createEntry(
                            id,
                            x.key().location(),
                            structureIcons[id],
                            structureData.item() == null ? null : itemRegistry.get(structureData.item()),
                            structureData.name(),
                            structureData.showByDefault(),
                            structureData.showByDefault()
                    );
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(StructuresList.StructureEntry::id))
                .toArray(StructuresList.StructureEntry[]::new);

    }

}
