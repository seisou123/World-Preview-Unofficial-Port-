// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.widgets.lists.StructurePickList;
import caeruleusTait.world.preview.client.gui.widgets.lists.StructuresList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Structure picker for the seed search screen: a filterable single-pick list
 * with a "None" row plus one row per structure (item icon + name). Picking a
 * row applies it to the parent seed search screen; closing without picking
 * (Esc, back button, E) changes nothing and never touches a running search.
 */
public final class StructureSelectScreen extends Screen {

    private final SeedSearchScreen parent;
    private final PreviewContainer container;
    @Nullable private final Identifier currentId;

    private EditBox filterBox;
    private StructurePickList pickList;

    public StructureSelectScreen(SeedSearchScreen parent, PreviewContainer container, @Nullable Identifier currentId) {
        super(WorldPreviewComponents.SEARCH_STRUCTURE_PICK);
        this.parent = parent;
        this.container = container;
        this.currentId = currentId;
    }

    @Override
    protected void init() {
        clearWidgets();

        pickList = new StructurePickList(minecraft, structureEntries(), currentId);
        pickList.setX(8);
        pickList.setY(48);
        pickList.setWidth(width - 16);
        pickList.setHeight((height - 36) - 48);
        pickList.setOnPick(pick -> {
            parent.structurePicked(pick.id(), pick.name());
            onClose();
        });

        filterBox = new EditBox(font, 8, 24, Math.min(200, width - 16), 20, WorldPreviewComponents.SEARCH_STRUCTURE_FILTER);
        filterBox.setHint(WorldPreviewComponents.SEARCH_STRUCTURE_FILTER);
        filterBox.setMaxLength(64);
        filterBox.setResponder(pickList::setFilter);

        addRenderableWidget(pickList);
        addRenderableWidget(filterBox);

        // Back button: closes without changing the picked structure.
        var backButton = Button.builder(CommonComponents.GUI_BACK, ignored -> onClose())
                .size(90, 20)
                .build();
        backButton.setX(width - 96);
        backButton.setY(height - 32);
        addRenderableWidget(backButton);
    }

    /** The structures offered by the picker (empty when the entries are not loaded yet). */
    private List<StructuresList.StructureEntry> structureEntries() {
        StructuresList.StructureEntry[] entries = container.structureEntries();
        if (entries == null) {
            return List.of();
        }
        return Arrays.stream(entries).filter(Objects::nonNull).toList();
    }

    @Override
    public void onClose() {
        // Return without applying a pick; a search, if any, keeps running.
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (super.keyPressed(event)) {
            return true;
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return false;
    }
}
