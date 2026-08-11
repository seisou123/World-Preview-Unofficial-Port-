package caeruleusTait.world.preview.client.gui.screens.settings;

import caeruleusTait.world.preview.domain.ui.PageCategory;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Left sidebar with category buttons for SettingsScreen.
 *
 * <p>Each category is backed by a {@link PageCategory} from the domain layer,
 * providing type-safe category identification across the UI and domain layers.
 */
public class SidebarPanel {

    public record Category(PageCategory pageCategory, Component title, int index) {}

    public static final Category[] CATEGORIES = {
        new Category(PageCategory.GENERAL, Component.translatable("world_preview.settings.general.title"), 0),
        new Category(PageCategory.CACHE, Component.translatable("world_preview.settings.cache.title"), 1),
        new Category(PageCategory.RESOLUTION, Component.translatable("world_preview.settings.sample.title"), 2),
        new Category(PageCategory.HEIGHTMAP, Component.translatable("world_preview.settings.heightmap.title"), 3),
        new Category(PageCategory.DIMENSION, Component.translatable("world_preview.settings.dimensions.title"), 4),
        new Category(PageCategory.BIOME, Component.translatable("world_preview.settings.biomes.title"), 5),
    };

    private final List<Button> buttons = new ArrayList<>();
    private int selectedIndex = 0;
    private final Consumer<Integer> onSelect;

    public SidebarPanel(int x, int y, int width, int height, Consumer<Integer> onSelect) {
        this.onSelect = onSelect;

        for (int i = 0; i < CATEGORIES.length; i++) {
            final int idx = i;
            int btnY = y + i * SettingsTheme.CATEGORY_HEIGHT;
            Button btn = Button.builder(CATEGORIES[i].title(), b -> select(idx))
                    .bounds(x, btnY, width, SettingsTheme.CATEGORY_HEIGHT - 2)
                    .build();
            buttons.add(btn);
        }
    }

    public void select(int index) {
        if (index < 0 || index >= CATEGORIES.length) return;
        selectedIndex = index;
        updateButtonStates();
        onSelect.accept(index);
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    /** Returns the PageCategory of the currently selected sidebar entry. */
    public PageCategory selectedCategory() {
        if (selectedIndex < 0 || selectedIndex >= CATEGORIES.length) return null;
        return CATEGORIES[selectedIndex].pageCategory();
    }

    private void updateButtonStates() {
        // Visual distinction handled in renderBackground
    }

    public void renderBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = buttons.isEmpty() ? 0 : buttons.get(0).getX();
        int y = buttons.isEmpty() ? 0 : buttons.get(0).getY();
        int w = buttons.isEmpty() ? SettingsTheme.SIDEBAR_WIDTH : buttons.get(0).getWidth();
        int h = buttons.size() * SettingsTheme.CATEGORY_HEIGHT;

        // Sidebar background
        graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, SettingsTheme.SIDEBAR_BG);

        // Active category highlight
        int activeY = y + selectedIndex * SettingsTheme.CATEGORY_HEIGHT;
        graphics.fill(x - 1, activeY, x + w + 1, activeY + SettingsTheme.CATEGORY_HEIGHT - 2,
                SettingsTheme.PRIMARY_DARK);
        graphics.fill(x - 1, activeY, x, activeY + SettingsTheme.CATEGORY_HEIGHT - 2,
                SettingsTheme.PRIMARY);
    }

    public List<Button> buttons() {
        return buttons;
    }

    public List<AbstractWidget> allWidgets() {
        return new ArrayList<>(buttons);
    }
}
