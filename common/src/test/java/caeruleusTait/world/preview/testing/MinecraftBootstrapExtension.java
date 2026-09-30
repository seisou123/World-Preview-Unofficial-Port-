package caeruleusTait.world.preview.testing;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Headless tests touch vanilla registry constants ({@code Registries},
 * {@code ResourceLocation} codecs, client widget statics) that on 1.20.1 live
 * behind {@code BuiltInRegistries}' bootstrapped class initialization —
 * without a bootstrap they fail with {@code Not bootstrapped}.  This global
 * extension bootstraps vanilla once per JVM, before any test class runs.
 */
public class MinecraftBootstrapExtension implements BeforeAllCallback {

    private static volatile boolean bootstrapped;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!bootstrapped) {
            synchronized (MinecraftBootstrapExtension.class) {
                if (!bootstrapped) {
                    SharedConstants.tryDetectVersion();
                    try {
                        Bootstrap.bootStrap();
                    } catch (Throwable t) {
                        // Forge patches bootStrap to initialise its network system
                        // (NetworkHooks.init) after the registries are built; that
                        // cannot run in a headless test JVM.  The registries are
                        // fully populated by then, so the failure is swallowable
                        // on Forge and never happens on Fabric/NeoForge.
                    }
                    bootstrapped = true;
                }
            }
        }
    }
}
