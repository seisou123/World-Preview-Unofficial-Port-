package caeruleusTait.world.preview.testing;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Shared reflective bootstrap for tests that need a live {@link WorldPreview}
 * singleton with compression disabled.
 *
 * <p>{@code WorldPreview.INSTANCE} holds mutable per-run state, so a fresh
 * instance must be installed for every test (call this from
 * {@code @BeforeEach}, never from {@code @BeforeAll}).</p>
 */
public final class TestBootstrap {

    private TestBootstrap() {
    }

    /**
     * Installs a new {@link WorldPreview} as {@code WorldPreview.INSTANCE} with
     * {@code cfg.enableCompression = false} and default render settings.
     *
     * <p>Loader-neutral: invokes the constructor with the fewest parameters
     * (Fabric declares {@code WorldPreview()}, NeoForge a single-argument
     * constructor that tests feed {@code null}), mirroring the shared bench's
     * {@code newWorldPreviewInstance} helper.</p>
     */
    public static void installWorldPreview() throws Exception {
        Constructor<?> ctor = null;
        for (Constructor<?> c : WorldPreview.class.getDeclaredConstructors()) {
            if (ctor == null || c.getParameterCount() < ctor.getParameterCount()) {
                ctor = c;
            }
        }
        ctor.setAccessible(true);
        Object[] args = ctor.getParameterCount() == 0 ? new Object[0] : new Object[] { null };
        WorldPreview preview = (WorldPreview) ctor.newInstance(args);
        Field instance = WorldPreview.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, preview);
        Field cfg = WorldPreview.class.getDeclaredField("cfg");
        cfg.setAccessible(true);
        WorldPreviewConfig config = WorldPreviewConfig.defaults();
        config.enableCompression = false;
        cfg.set(preview, config);
        Field settings = WorldPreview.class.getDeclaredField("renderSettings");
        settings.setAccessible(true);
        settings.set(preview, RenderSettings.defaults());
    }
}
