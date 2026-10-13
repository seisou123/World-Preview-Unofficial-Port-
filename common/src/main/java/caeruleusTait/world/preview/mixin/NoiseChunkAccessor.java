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
}
