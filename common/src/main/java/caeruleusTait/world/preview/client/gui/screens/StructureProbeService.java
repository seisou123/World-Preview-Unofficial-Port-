// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.analysis.LightweightSeedSampler;
import caeruleusTait.world.preview.backend.analysis.WorldgenContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

/**
 * Analytic structure probe owned by the {@link PreviewContainer}: a
 * {@link LightweightSeedSampler} built lazily on the render thread and
 * cached per worldgen epoch, so world switches never reuse a stale
 * sampler.
 */
final class StructureProbeService {
    private final WorkManager workManager;
    @Nullable private volatile LightweightSeedSampler sampler;
    private volatile long samplerEpoch = -1;

    StructureProbeService(WorkManager workManager) {
        this.workManager = workManager;
    }

    /**
     * Analytic nearest-structure probe for the current preview seed: pure
     * worldgen math (the vanilla /locate placement pipeline), no storage and
     * no server infrastructure, so it is safe to call on the render thread
     * when the analysis screen opens.
     * <p>
     * The backing {@link LightweightSeedSampler} is built lazily on first use
     * (at most one build attempt per worldgen epoch) and cached until
     * {@link #invalidate()} drops it on a world switch. Only render-thread
     * callers are expected; the fields are volatile so an accidental
     * cross-thread use stays memory-safe without locking.
     *
     * @return found structure id to its located position (ids that could not
     *         be located within the distance cap are simply omitted), or
     *         {@code null} when probing is unavailable — no live worldgen
     *         context, or the probe sampler could not be built
     */
    public @Nullable Map<ResourceLocation, BlockPos> probeNearest(Set<ResourceLocation> types, BlockPos anchor, int maxDistanceBlocks) {
        var context = workManager.worldgenContext();
        if (context == null) return null;
        long epoch = workManager.epoch();
        if (samplerEpoch != epoch) {
            sampler = buildSampler(context);
            samplerEpoch = epoch;
        }
        if (sampler == null) return null;
        Map<ResourceLocation, BlockPos> out = new LinkedHashMap<>();
        for (ResourceLocation id : types) {
            try {
                BlockPos found = sampler.nearestStructure(Set.of(id), anchor, maxDistanceBlocks);
                if (found != null) out.put(id, found);
            } catch (Exception e) {
                LOGGER.debug("structure probe failed for {}", id, e);
            }
        }
        return out;
    }

    /** Drops the cached analytic structure probe so the next probe rebuilds it for the new epoch. */
    void invalidate() {
        LightweightSeedSampler stale = sampler;
        sampler = null;
        samplerEpoch = -1;
        if (stale != null) {
            try {
                // close() is a no-op today (see LightweightSeedSampler), but
                // staying AutoCloseable-clean keeps that free to change.
                stale.close();
            } catch (Throwable ignored) {
                // Never block world switches on probe cleanup.
            }
        }
    }

    /**
     * Builds the analytic structure probe sampler for the given worldgen
     * context, following the same assembly recipe as the container's search
     * context setup (shared biome source / chunk generator / composite
     * registry access, a per-seed {@link RandomState}, and the height
     * accessor + template manager from the context's sample utilities) but
     * fixed to the context's current seed instead of a per-seed factory.
     *
     * @return the sampler, or {@code null} when probing is unavailable — most
     *         notably when the structure template manager cannot be obtained
     */
    @Nullable
    private LightweightSeedSampler buildSampler(WorldgenContext worldgenContext) {
        try {
            final var biomeSource = worldgenContext.biomeSource();
            final var chunkGenerator = worldgenContext.chunkGenerator();
            // compositeAccess() returns RegistryAccess.Frozen, supports lookupOrThrow
            final var compositeRegistryAccess = worldgenContext.registryAccess().compositeAccess();
            final var probeRegistries = new LightweightSeedSampler.RegistryAccessBundle(
                    compositeRegistryAccess,
                    compositeRegistryAccess.lookupOrThrow(Registries.STRUCTURE),
                    compositeRegistryAccess.lookupOrThrow(Registries.STRUCTURE_SET));
            final LevelHeightAccessor probeHeight;
            final StructureTemplateManager probeTemplates;
            try {
                // Reuse the WorkManager-owned SampleUtils: it already carries the
                // template manager and height accessor for this worldgen context.
                var sampleUtils = worldgenContext.createSampleUtils();
                probeHeight = sampleUtils.levelHeightAccessor();
                probeTemplates = sampleUtils.structureTemplateManager();
            } catch (Exception e) {
                LOGGER.warn("Structure probing disabled: failed to obtain structure template manager", e);
                return null;
            }
            final long seed = worldgenContext.seed();
            RandomState randomState;
            if (chunkGenerator instanceof NoiseBasedChunkGenerator noiseBasedChunkGenerator) {
                randomState = RandomState.create(
                    noiseBasedChunkGenerator.generatorSettings().value(),
                    compositeRegistryAccess.lookupOrThrow(Registries.NOISE),
                    seed
                );
            } else {
                randomState = RandomState.create(
                    NoiseGeneratorSettings.dummy(),
                    compositeRegistryAccess.lookupOrThrow(Registries.NOISE),
                    seed
                );
            }
            return new LightweightSeedSampler(
                    biomeSource, chunkGenerator, probeRegistries, randomState, seed, probeHeight, probeTemplates);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to build structure probe sampler", e);
            return null;
        }
    }
}
