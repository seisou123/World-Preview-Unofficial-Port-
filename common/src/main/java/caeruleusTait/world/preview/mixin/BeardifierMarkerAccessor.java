package caeruleusTait.world.preview.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker")
public interface BeardifierMarkerAccessor {
    @Accessor("INSTANCE")
    static Object getInstance() {
        throw new AssertionError();
    }
}
