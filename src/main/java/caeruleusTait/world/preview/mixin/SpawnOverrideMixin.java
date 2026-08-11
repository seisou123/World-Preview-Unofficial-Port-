package caeruleusTait.world.preview.mixin;

import caeruleusTait.world.preview.SpawnOverrideManager;
import caeruleusTait.world.preview.WorldPreview;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the spawn override (if set) when the integrated server starts ticking.
 * This lets the player set a custom spawn point from the world preview map
 * without needing to enable cheats.
 *
 * Uses the vanilla /setworldspawn command via the server's command manager
 * to avoid touching internal APIs that may change between MC versions.
 */
@Mixin(MinecraftServer.class)
public abstract class SpawnOverrideMixin {

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void applySpawnOverrideOnFirstTick(CallbackInfo ci) {
        if (SpawnOverrideManager.shouldApply()) {
            MinecraftServer server = (MinecraftServer) (Object) this;
            // Only apply on non-dedicated servers (singleplayer integrated servers)
            if (server.isDedicatedServer()) return;

            var cfg = WorldPreview.get().cfg();
            if (cfg.spawnOverrideEnabled) {
                int x = cfg.spawnOverrideX;
                int z = cfg.spawnOverrideZ;
                String command = "setworldspawn " + x + " 100 " + z;
                try {
                    server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(),
                            command
                    );
                    WorldPreview.LOGGER.info("Spawn override applied at X={}, Z={}", x, z);
                } catch (Exception e) {
                    WorldPreview.LOGGER.error("Failed to apply spawn override", e);
                }
            }
            SpawnOverrideManager.markApplied();
        }
    }
}
