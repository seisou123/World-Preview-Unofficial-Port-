// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.backend.storage;

import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

public class PreviewLevel implements WorldGenLevel {

    private final RegistryAccess registryAccess;
    private final LevelHeightAccessor levelHeightAccessor;

    /**
     * Real level backing this preview level, or {@code null} while none has been attached.
     *
     * <p>This class is a throwaway stand-in: it serves generated chunks from
     * thin air and stubs out everything it does not need. That works until some
     * third-party code treats every {@link WorldGenLevel} as a
     * {@link net.minecraft.world.level.ServerLevelAccessor} and calls
     * {@link #getLevel()} on it — structure generators hooked by mods such as
     * Goety: Revelation do exactly that, which used to fail every structure
     * work unit with a {@link NotImplementedException}.
     *
     * <p>Only the two accessors below delegate. The rest of the class stays a
     * stub on purpose: this is a compatibility bridge for code that probes
     * {@code getLevel()}, not a claim that the preview level is the real one.
     */
    @Nullable
    private volatile ServerLevel backingLevel;

    public PreviewLevel(RegistryAccess registryAccess, LevelHeightAccessor levelHeightAccessor) {
        this.registryAccess = registryAccess;
        this.levelHeightAccessor = levelHeightAccessor;
    }

    @Nullable
    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus requiredStatus, boolean nonnull) {
        // Storage would take up too much space, so every request builds a throwaway
        // ProtoChunk instead of caching one per position.
        return new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, levelHeightAccessor, PalettedContainerFactory.create(registryAccess), null);
    }

    @Override
    public RegistryAccess registryAccess() {
        return registryAccess;
    }

    /**
     * Attaches the level that {@link #getLevel()} and {@link #getServer()} report.
     *
     * <p>Passing {@code null} detaches it again; call that before the backing
     * level is closed so a late worker cannot hand out a closed level.
     *
     * @param backingLevel the real level to expose, or {@code null} to detach
     */
    public void attachLevel(@Nullable ServerLevel backingLevel) {
        this.backingLevel = backingLevel;
    }

    // Stuff we don't need but still need to implement:

    @Override
    public long getSeed() {
        throw new NotImplementedException("Not implemented");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to the attached level, because third-party structure code
     * reaches for the owning level through the {@link net.minecraft.world.level.ServerLevelAccessor}
     * contract that {@link WorldGenLevel} extends. Throws when nothing is
     * attached, so the failure stays visible instead of turning into a null
     * dereference somewhere inside the caller.
     */
    @Override
    public ServerLevel getLevel() {
        final ServerLevel level = backingLevel;
        if (level == null) {
            throw new NotImplementedException("No level attached to the preview level");
        }
        return level;
    }

    @Override
    public long nextSubTickCount() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public LevelData getLevelData() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        throw new NotImplementedException("Not implemented");
    }

    @Nullable
    @Override
    public MinecraftServer getServer() {
        // Same bridge as getLevel(): callers that ask for the server through
        // the level accessor contract get the real one when it is attached.
        final ServerLevel level = backingLevel;
        if (level == null) {
            throw new NotImplementedException("Not implemented");
        }
        return level.getServer();
    }

    @Override
    public ChunkSource getChunkSource() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public RandomSource getRandom() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public void playSound(@Nullable Entity entity, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public void addParticle(ParticleOptions particleData, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public void levelEvent(@Nullable Entity entity, int type, BlockPos pos, int data) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public void gameEvent(Holder<GameEvent> holder, Vec3 vec3, GameEvent.Context context) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public LevelLightEngine getLightEngine() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public WorldBorder getWorldBorder() {
        throw new NotImplementedException("Not implemented");
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public List<Entity> getEntities(@Nullable Entity entity, AABB area, Predicate<? super Entity> predicate) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> entityTypeTest, AABB bounds, Predicate<? super T> predicate) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public List<? extends Player> players() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public int getHeight(Heightmap.Types heightmapType, int x, int z) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public int getSkyDarken() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public BiomeManager getBiomeManager() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public boolean isClientSide() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public int getSeaLevel() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public DimensionType dimensionType() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public EnvironmentAttributeReader environmentAttributes() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> state) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean isMoving) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
        throw new NotImplementedException("Not implemented");
    }
}
