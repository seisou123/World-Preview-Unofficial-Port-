// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.mixin.client;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.client.gui.screens.PreviewTab;
import caeruleusTait.world.preview.SpawnOverrideManager;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Shadow private @Nullable MenuTabBar tabNavigationBar;

    private PreviewTab previewTab;
    private boolean wasPreviewTabCurrent = false;

    @Inject(method = "init", at = @At("HEAD"))
    private void recordPreviewTabState(CallbackInfo ci) {
        if (previewTab != null && tabNavigationBar != null) {
            TabManager tm = ((TabNavigationBarAccessor) tabNavigationBar).getTabManager();
            wasPreviewTabCurrent = (tm.getCurrentTab() == previewTab);
            WorldPreview.LOGGER.info("[WP-Mixin] recordPreviewTabState: wasPreviewTabCurrent={}", wasPreviewTabCurrent);
        }
    }

    @Inject(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;addRenderableWidget(Lnet/minecraft/client/gui/components/events/GuiEventListener;)Lnet/minecraft/client/gui/components/events/GuiEventListener;",
                    shift = At.Shift.BEFORE
            ),
            slice = @Slice(
                    from = @At("HEAD"),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToFooter(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;")
            )
    )
    private void appendPreviewTab(CallbackInfo ci) {
        if (previewTab == null) {
            previewTab = new PreviewTab((CreateWorldScreen) (Object) this, ((ScreenAccessor) this).getMinecraft());
            WorldPreview.LOGGER.info("[WP-Mixin] Created new PreviewTab");
        } else {
            WorldPreview.LOGGER.info("[WP-Mixin] Reusing existing PreviewTab");
        }

        final MenuTabBar original = tabNavigationBar;
        final TabNavigationBarAccessor accessor = (TabNavigationBarAccessor) original;

        // Filter out any old PreviewTab entries from the tab list to prevent
        // duplicate "Preview" tab buttons after screen re-initialisation.
        Tab[] vanillaTabs = accessor.getTabs().stream()
                .filter(t -> !(t instanceof PreviewTab))
                .toArray(Tab[]::new);

        tabNavigationBar = MenuTabBar
                .builder(accessor.getTabManager(), original.getWidth())
                .addTabs(vanillaTabs)
                .addTab(previewTab)
                .build();

        previewTab.mainScreenWidget().onScreenReentry();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void forceReaddPreviewWidgets(CallbackInfo ci) {
        if (previewTab != null && tabNavigationBar != null && wasPreviewTabCurrent) {
            TabManager tm = ((TabNavigationBarAccessor) tabNavigationBar).getTabManager();
            // CRITICAL FIX: When returning from a sub-screen (e.g. TerrainExportScreen),
            // clearWidgets() in init() has emptied the screen's children/renderables lists.
            // If previewTab is still the current tab (vanilla's init() didn't switch
            // away), TabManager.setCurrentTab() short-circuits (tab == currentTab) and
            // does NOT re-add the preview tab's widgets.  The preview display would
            // render via the tab bar's own rendering path but would NOT receive mouse
            // events -- causing the "can't drag, no tooltip, biome clicks don't work"
            // bug.  Fix: null out currentTab so setCurrentTab() does not short-circuit,
            // then use doLayout=true so PreviewTab.doLayout() repositions all widgets
            // and calls onScreenReentry().
            if (tm.getCurrentTab() == previewTab) {
                WorldPreview.LOGGER.info("[WP-Mixin] forceReaddPreviewWidgets: nulling currentTab to force re-registration");
                ((TabManagerAccessor) tm).setCurrentTabField(null);
            }
            tm.setCurrentTab(previewTab, true);
            wasPreviewTabCurrent = false;
            WorldPreview.LOGGER.info("[WP-Mixin] forceReaddPreviewWidgets: completed re-registration");
        }
    }

    @Inject(method = "popScreen", at = @At("HEAD"))
    private void saveConfigOnClose(CallbackInfo ci) {
        if (previewTab != null) {
            previewTab.close();
            previewTab = null;
        }
        WorldPreview.get().saveConfig();
    }

    @Inject(method = "onCreate", at = @At("HEAD"))
    private void saveConfigOnCreate(CallbackInfo ci) {
        if (previewTab != null) {
            previewTab.close();
            previewTab = null;
        }
        SpawnOverrideManager.reset();
        WorldPreview.get().saveConfig();
    }

}
