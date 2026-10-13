package caeruleusTait.world.preview.mixin.client;

import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TabManager.class)
public interface TabManagerAccessor {

    @Accessor("currentTab")
    void setCurrentTabField(@Nullable Tab tab);

}
