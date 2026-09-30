package caeruleusTait.world.preview.infra.minecraft;

import caeruleusTait.world.preview.compat.ChunkGeneratorAdapter;
import caeruleusTait.world.preview.backend.analysis.WorldgenContext;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.Set;

/**
 * Implementation of {@link MinecraftChunkGenerator} that delegates to
 * a {@link ChunkGeneratorAdapter}.
 *
 * <p>This bridges the infrastructure abstraction layer with the mod
 * compatibility system, allowing mod-specific adapters to provide
 * custom chunk generation sampling logic.
 */
public class MinecraftChunkGeneratorImpl implements MinecraftChunkGenerator {

    private final ChunkGeneratorAdapter adapter;
    private final WorldgenContext ctx;
    private final LevelStem levelStem;

    public MinecraftChunkGeneratorImpl(ChunkGeneratorAdapter adapter,
                                        WorldgenContext ctx,
                                        LevelStem levelStem) {
        this.adapter = adapter;
        this.ctx = ctx;
        this.levelStem = levelStem;
    }

    @Override
    public short[][] generateBiomes(int chunkX, int chunkZ) {
        return adapter.generateBiomes(ctx, chunkX, chunkZ);
    }

    @Override
    public boolean hasStructureStart(int chunkX, int chunkZ, String structureId) {
        return adapter.structureStarts(ctx, chunkX, chunkZ).contains(structureId);
    }

    @Override
    public Set<String> structureStarts(int chunkX, int chunkZ) {
        return adapter.structureStarts(ctx, chunkX, chunkZ);
    }

    @Override
    public int surfaceHeight(int x, int z) {
        return adapter.surfaceHeight(ctx, x, z);
    }

    @Override
    public int minY() {
        return adapter.minY(levelStem);
    }

    @Override
    public int maxY() {
        return adapter.maxY(levelStem);
    }

}
