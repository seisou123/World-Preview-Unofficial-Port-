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
            if (server.isDedicatedServer()) {
                SpawnOverrideManager.markApplied();
                return;
            }

            var cfg = WorldPreview.get().cfg();
            if (cfg.spawnOverrideEnabled) {
                int x = cfg.spawnOverrideX;
                int z = cfg.spawnOverrideZ;
                String command = "setworldspawn " + x + " 100 " + z;
                int result;
                try {
                    // performPrefixedCommand reports command errors (a syntax
                    // or argument failure) through the command source instead
                    // of throwing; the return value is the only reliable
                    // success signal.
                    result = server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(),
                            command
                    );
                } catch (Exception e) {
                    result = 0;
                    if (!SpawnOverrideManager.hasLoggedFailure()) {
                        WorldPreview.LOGGER.error("Failed to apply spawn override", e);
                        SpawnOverrideManager.markFailureLogged();
                    }
                }
                if (result > 0) {
                    WorldPreview.LOGGER.info("Spawn override applied at X={}, Z={}", x, z);
                    SpawnOverrideManager.markApplied();
                } else {
                    // Keep the flag pending so the next server ticks retry, up to
                    // a bounded number of attempts; log only once per pending
                    // window so the retry cannot flood the log.
                    SpawnOverrideManager.recordFailure();
                    if (!SpawnOverrideManager.hasLoggedFailure()) {
                        WorldPreview.LOGGER.error("setworldspawn did not report success; the spawn override will retry");
                        SpawnOverrideManager.markFailureLogged();
                    }
                    if (!SpawnOverrideManager.shouldApply()) {
                        WorldPreview.LOGGER.error("Spawn override gave up; it was not applied to this world");
                    }
                }
            } else {
                // Override disabled: consume the pending flag so a later
                // enabling cannot rewrite a world that is already open.
                SpawnOverrideManager.markApplied();
            }
        }
    }
}
