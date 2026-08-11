package caeruleusTait.world.preview.compat;

import caeruleusTait.world.preview.backend.analysis.WorldgenContext;
import caeruleusTait.world.preview.backend.worker.SampleUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Default adapter for vanilla Minecraft ChunkGenerators.
 *
 * <p>This adapter serves as the fallback when no mod-specific adapter
 * is available. It delegates sampling operations to {@link SampleUtils}
 * which is lazily created via {@link WorldgenContext#createSampleUtils()}.
 *
 * <p>Mod-specific adapters should extend this class to override sampling
 * methods for their custom ChunkGenerator implementations.
 *
 * <p>Note: This adapter does not create SampleUtils directly; it delegates
 * to the WorldgenContext which manages SampleUtils lifecycle.
 */
public class VanillaChunkGeneratorAdapter implements ChunkGeneratorAdapter {

    /** Factory instance that creates a new adapter per WorldgenContext. */
    public static final Factory FACTORY = (ctx, compat) -> new VanillaChunkGeneratorAdapter();

    @Override
    public Class<? extends ChunkGenerator> supportedType() {
        return ChunkGenerator.class;
    }

    @Override
    public boolean isApplicable(ChunkGenerator chunkGenerator) {
        return chunkGenerator != null;
    }

    @Override
    public int minY(LevelStem levelStem) {
        return levelStem.type().value().minY();
    }

    @Override
    public int maxY(LevelStem levelStem) {
        return levelStem.type().value().minY() + levelStem.type().value().height();
    }

    @Override
    public short[][] generateBiomes(WorldgenContext ctx, int chunkX, int chunkZ) {
        if (ctx == null) return new short[4][4];
        try {
            SampleUtils utils = ctx.createSampleUtils();
            if (utils == null) return new short[4][4];

            short[][] result = new short[4][4];
            for (int localX = 0; localX < 4; localX++) {
                for (int localZ = 0; localZ < 4; localZ++) {
                    int blockX = chunkX * 16 + localX * 4;
                    int blockZ = chunkZ * 16 + localZ * 4;
                    int blockY = 64; // Default Y level for biome sampling

                    var result2 = utils.doSample(new BlockPos(blockX, blockY, blockZ));
                    if (result2 != null && result2.biome() != null) {
                        ResourceKey<Biome> biomeKey = result2.biome();
                        // Map biome key to a short ID (simplified)
                        result[localX][localZ] = (short) (biomeKey.hashCode() & 0xFFFF);
                    }
                }
            }
            return result;
        } catch (IOException | RuntimeException e) {
            return new short[4][4];
        }
    }

    @Override
    public Set<String> structureStarts(WorldgenContext ctx, int chunkX, int chunkZ) {
        Set<String> result = new HashSet<>();
        if (ctx == null) return result;
        try {
            SampleUtils utils = ctx.createSampleUtils();
            if (utils == null) return result;

            var structures = utils.doStructures(new ChunkPos(chunkX, chunkZ));
            if (structures != null) {
                structures.forEach(pair -> result.add(pair.getFirst().toString()));
            }
        } catch (IOException | RuntimeException e) {
            // Return empty set on failure
        }
        return result;
    }

    @Override
    public int surfaceHeight(WorldgenContext ctx, int x, int z) {
        if (ctx == null) return 0;
        try {
            SampleUtils utils = ctx.createSampleUtils();
            if (utils == null) return 0;
            return (int) utils.doHeightSlow(new BlockPos(x, 64, z));
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    @Override
    public boolean supportsHeightmap() {
        return true;
    }

    @Override
    public boolean supportsStructures() {
        return true;
    }
}
