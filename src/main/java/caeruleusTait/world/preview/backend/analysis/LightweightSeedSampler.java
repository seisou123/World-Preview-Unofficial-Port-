package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight per-seed sampler backing the seed search service.
 * <p>
 * Biome checks go straight through {@link BiomeSource#getNoiseBiome} with a
 * per-seed {@link RandomState} (no dummy server, no chunks). Structure checks
 * replicate the core of the vanilla /locate pipeline without a level:
 * {@link ChunkGeneratorStructureState} placement math finds candidate chunks
 * ({@link RandomSpreadStructurePlacement#getPotentialStructureChunk}) and
 * {@link Structure#findValidGenerationPoint} verifies that the structure would
 * actually generate there (biome + terrain conditions).
 * </p>
 * <p>
 * Structures using concentric-ring placement (strongholds) are excluded from
 * probing: their ring positions are far too expensive to recompute per
 * candidate seed. Their criteria simply never match.
 * </p>
 */
public final class LightweightSeedSampler implements SeedSearchService.BiomeSampler, SeedSearchService.StructureProbe {

    /** Safety cap on locate rings regardless of the requested distance. */
    private static final int MAX_RING_RADIUS = 64;

    private final BiomeSource biomeSource;
    private final ChunkGenerator chunkGenerator;
    private final RegistryAccessBundle registries;
    private final RandomState randomState;
    private final long seed;
    private final LevelHeightAccessor heightAccessor;
    @Nullable private final StructureTemplateManager templateManager;

    /** Per-seed structure state: computes placement grids lazily and caches them. */
    private ChunkGeneratorStructureState structureState;

    /** Memoized structure holders resolved from identifiers. */
    private final Map<Identifier, Holder<Structure>> holderCache = new HashMap<>();

    /**
     * @param registries bundle of the registries needed for structure probing
     */
    public LightweightSeedSampler(
            BiomeSource biomeSource,
            ChunkGenerator chunkGenerator,
            RegistryAccessBundle registries,
            RandomState randomState,
            long seed,
            LevelHeightAccessor heightAccessor,
            @Nullable StructureTemplateManager templateManager
    ) {
        this.biomeSource = biomeSource;
        this.chunkGenerator = chunkGenerator;
        this.registries = registries;
        this.randomState = randomState;
        this.seed = seed;
        this.heightAccessor = heightAccessor;
        this.templateManager = templateManager;
    }

    @Override
    public boolean sampleContains(int x, int y, int z, Identifier targetBiome) {
        var biomeHolder = biomeSource.getNoiseBiome(
                QuartPos.fromBlock(x),
                QuartPos.fromBlock(y),
                QuartPos.fromBlock(z),
                randomState.sampler()
        );
        return biomeHolder.unwrapKey()
                .map(key -> key.identifier().equals(targetBiome))
                .orElse(false);
    }

    @Override
    @Nullable
    public BlockPos nearestStructure(Set<Identifier> structures, BlockPos anchor, int maxDistanceBlocks) {
        if (templateManager == null) {
            return null;
        }

        // Group the requested structures by their random-spread placement so
        // each placement grid is only scanned once per ring.
        Map<RandomSpreadStructurePlacement, Set<Holder<Structure>>> byPlacement = new HashMap<>();
        for (Identifier id : structures) {
            Holder<Structure> holder = resolveHolder(id);
            if (holder == null) {
                continue;
            }
            for (StructurePlacement placement : structureState().getPlacementsForStructure(holder)) {
                if (placement instanceof RandomSpreadStructurePlacement spread) {
                    byPlacement.computeIfAbsent(spread, p -> new HashSet<>()).add(holder);
                }
            }
        }
        if (byPlacement.isEmpty()) {
            return null;
        }

        // Outward rings of placement-grid cells around the anchor chunk. A
        // ring-k cell sits roughly spacing*k*16 blocks away, so enough rings
        // cover maxDistanceBlocks; border-only iteration matches vanilla and
        // avoids re-testing inner cells.
        int maxRings = 0;
        for (RandomSpreadStructurePlacement placement : byPlacement.keySet()) {
            int spacing = Math.max(1, placement.spacing());
            maxRings = Math.max(maxRings, (int) Math.ceil(maxDistanceBlocks / (16.0 * spacing)));
        }
        maxRings = Math.min(maxRings, MAX_RING_RADIUS);

        int anchorChunkX = SectionPos.blockToSectionCoord(anchor.getX());
        int anchorChunkZ = SectionPos.blockToSectionCoord(anchor.getZ());
        long maxDistanceSq = (long) maxDistanceBlocks * maxDistanceBlocks;

        for (int ring = 0; ring <= maxRings; ring++) {
            for (Map.Entry<RandomSpreadStructurePlacement, Set<Holder<Structure>>> entry : byPlacement.entrySet()) {
                RandomSpreadStructurePlacement placement = entry.getKey();
                int spacing = Math.max(1, placement.spacing());
                for (int n = -ring; n <= ring; n++) {
                    boolean borderN = n == -ring || n == ring;
                    for (int o = -ring; o <= ring; o++) {
                        boolean borderO = o == -ring || o == ring;
                        if (!borderN && !borderO) {
                            continue;
                        }
                        ChunkPos chunkPos = placement.getPotentialStructureChunk(
                                seed, anchorChunkX + spacing * n, anchorChunkZ + spacing * o);
                        for (Holder<Structure> holder : entry.getValue()) {
                            if (!isValidAt(holder.value(), chunkPos)) {
                                continue;
                            }
                            BlockPos locatePos = placement.getLocatePos(chunkPos);
                            long dx = (long) locatePos.getX() - anchor.getX();
                            long dz = (long) locatePos.getZ() - anchor.getZ();
                            if (dx * dx + dz * dz <= maxDistanceSq) {
                                return locatePos;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void close() {
        // RandomState and structure state need no explicit cleanup.
    }

    private boolean isValidAt(Structure structure, ChunkPos chunkPos) {
        var context = new Structure.GenerationContext(
                registries.registryAccess(),
                chunkGenerator,
                biomeSource,
                randomState,
                templateManager,
                seed,
                chunkPos,
                heightAccessor,
                holder -> structure.biomes().contains(holder)
        );
        return structure.findValidGenerationPoint(context).isPresent();
    }

    private ChunkGeneratorStructureState structureState() {
        var state = structureState;
        if (state == null) {
            state = ChunkGeneratorStructureState.createForNormal(
                    randomState, seed, biomeSource, registries.structureSetLookup());
            structureState = state;
        }
        return state;
    }

    @Nullable
    private Holder<Structure> resolveHolder(Identifier id) {
        Holder<Structure> cached = holderCache.get(id);
        if (cached != null) {
            return cached;
        }
        var key = ResourceKey.create(Registries.STRUCTURE, id);
        return registries.structureLookup().get(key)
                .map(reference -> {
                    holderCache.put(id, reference);
                    return reference;
                })
                .orElse(null);
    }

    /**
     * The registry bundle the sampler needs. Wrapping them in one record keeps
     * the constructor signature manageable; all lookups are read-only views of
     * the worldgen registry access for the dimension being searched.
     */
    public record RegistryAccessBundle(
            net.minecraft.core.RegistryAccess registryAccess,
            net.minecraft.core.HolderLookup.RegistryLookup<Structure> structureLookup,
            net.minecraft.core.HolderLookup.RegistryLookup<net.minecraft.world.level.levelgen.structure.StructureSet> structureSetLookup
    ) {
        public RegistryAccessBundle {
            java.util.Objects.requireNonNull(registryAccess, "registryAccess");
            java.util.Objects.requireNonNull(structureLookup, "structureLookup");
            java.util.Objects.requireNonNull(structureSetLookup, "structureSetLookup");
        }
    }
}
