package caeruleusTait.world.preview.backend.stubs;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.jetbrains.annotations.Nullable;

/**
 * Wraps the real {@link Aquifer} to disambiguate {@code null} results.
 * <p>
 * In MC 1.21.6+, {@code NoiseBasedAquifer.computeSubstance()} returns {@code null}
 * for BOTH solid blocks (density &gt; 0) and air (density ≤ 0, non-fluid).
 * This makes it impossible for callers of {@code getInterpolatedState()} to
 * distinguish air from stone.
 * <p>
 * This wrapper uses the {@code substance} parameter (which IS the final density
 * value computed by the NoiseChunk's internal mapped density function) to convert
 * {@code null} results into distinguishable block states.
 * <p>
 * The Minecraft density convention is:
 * <ul>
 *   <li>density &gt; 0 → SOLID (below the surface, e.g. stone) → return {@code null}
 *       so that the composite {@code BlockStateSampler} can try ore-vein samplers;
 *       the caller ({@code IntersectionWorkUnit}) converts a final {@code null}
 *       to {@code defaultBlock} (stone).</li>
 *   <li>density ≤ 0 → AIR (above the surface) → return {@code Blocks.AIR}
 *       ({@code mapColor.id = 0}, triggers the "see through one layer of air"
 *       rendering logic).</li>
 * </ul>
 * Non-null results (water, lava) are passed through unchanged.
 */
public class IntersectionAquifer implements Aquifer {
    private final Aquifer delegate;
    private final BlockState defaultBlock;

    public IntersectionAquifer(Aquifer delegate, BlockState defaultBlock) {
        this.delegate = delegate;
        this.defaultBlock = defaultBlock;
    }

    @Override
    @Nullable
    public BlockState computeSubstance(DensityFunction.FunctionContext context, double substance) {
        BlockState result = delegate.computeSubstance(context, substance);
        if (result == null) {
            // Minecraft density convention: substance > 0 = SOLID, substance <= 0 = AIR.
            // For solid blocks, return null so the composite BlockStateSampler can
            // try ore-vein samplers before defaulting to stone (handled by the caller).
            // For air, return AIR so the composite short-circuits.
            return substance > 0.0
                    ? null
                    : Blocks.AIR.defaultBlockState();
        }
        return result;
    }

    @Override
    public boolean shouldScheduleFluidUpdate() {
        return delegate.shouldScheduleFluidUpdate();
    }
}
