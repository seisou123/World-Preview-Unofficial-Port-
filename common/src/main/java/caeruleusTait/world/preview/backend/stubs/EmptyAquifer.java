package caeruleusTait.world.preview.backend.stubs;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import org.jetbrains.annotations.Nullable;

public class EmptyAquifer implements Aquifer {
    @Nullable
    @Override
    public BlockState computeSubstance(int blockX, int blockY, int blockZ, double density) {
        return density > 0.0 ? null : Blocks.AIR.defaultBlockState();
    }

    @Override
    public boolean shouldScheduleFluidUpdate() {
        return false;
    }
}
