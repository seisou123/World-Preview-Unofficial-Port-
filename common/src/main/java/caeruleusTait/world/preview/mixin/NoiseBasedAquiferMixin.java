// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.mixin;

import caeruleusTait.world.preview.backend.WorkManager;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Aquifer.NoiseBasedAquifer.class)
public abstract class NoiseBasedAquiferMixin {

    @Final
    @Shadow
    private NoiseChunk noiseChunk;

    @ModifyVariable(
            method = "<init>",
            at = @At(value = "STORE"),
            ordinal = 2
    )
    private int fixMaxPosX(int k) {
        if (!WorkManager.isPreviewGeneration()) {
            return k;
        }
        return k + invokeGridX(((NoiseChunkAccessor)noiseChunk).getCellCountXZ() * cellWidth());
    }

    @ModifyVariable(
            method = "<init>",
            at = @At(value = "STORE"),
            ordinal = 5
    )
    private int fixMaxPosZ(int k) {
        if (!WorkManager.isPreviewGeneration()) {
            return k;
        }
        return k + invokeGridZ(((NoiseChunkAccessor)noiseChunk).getCellCountXZ() * cellWidth());
    }

    @Invoker
    abstract int invokeGridX(int x);

    @Invoker
    abstract int invokeGridZ(int z);

    private int cellWidth() {
        return ((NoiseChunkAccessor) noiseChunk).getCellWidth();
    }
}
