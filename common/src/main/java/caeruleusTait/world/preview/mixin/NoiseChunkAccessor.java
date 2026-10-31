// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(NoiseChunk.class)
public interface NoiseChunkAccessor {

    @Invoker
    BlockState invokeGetInterpolatedState();

    @Final
    @Mutable
    @Accessor
    void setAquifer(Aquifer aquifer);

    @Accessor
    Aquifer getAquifer();

    @Accessor
    int getCellCountXZ();

    @Accessor
    int getCellWidth();

    /**
     * Per-chunk list of cell interpolation caches.
     * <p>
     * Only the terrain export uses this: it reads a single column per noise cell,
     * so it wants to take the cell-wide fill out of the path. See
     * {@code NoiseHeightSampler#createNoiseChunk}.
     */
    @Accessor("cellCaches")
    List<?> getCellCaches();

    /** Interpolators wrapped around every {@code interpolated} density node. */
    @Accessor("interpolators")
    List<?> getInterpolators();

    /** Replaces the block-state rule, so the cell cache can leave the path. */
    @Final
    @Mutable
    @Accessor("blockStateRule")
    void setBlockStateRule(NoiseChunk.BlockStateFiller rule);
}
