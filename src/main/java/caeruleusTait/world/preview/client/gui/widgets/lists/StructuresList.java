// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets.lists;

import caeruleusTait.world.preview.client.WorldPreviewClient;
import caeruleusTait.world.preview.client.gui.widgets.ToggleButton;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;

import static caeruleusTait.world.preview.client.gui.screens.PreviewContainer.BUTTONS_TEXTURE;
import static caeruleusTait.world.preview.client.gui.screens.PreviewContainer.BUTTONS_TEX_HEIGHT;
import static caeruleusTait.world.preview.client.gui.screens.PreviewContainer.BUTTONS_TEX_WIDTH;
import caeruleusTait.world.preview.client.gui.PreviewDisplayDataProvider.StructureRenderInfo;

public class StructuresList extends BaseObjectSelectionList<StructuresList.StructureEntry> {

    @Nullable private java.util.function.Consumer<StructureEntry> onRightClick;
    @Nullable private java.util.function.Consumer<StructureEntry> onDoubleClick;
    private boolean searchActive = false;
    @Nullable private Component searchStatus = null;

    public StructuresList(Minecraft minecraft, int width, int height, int x, int y) {
        super(minecraft, width, height, x, y, 24);
    }

    /** Set right-click callback (used to open the seed search for this structure). */
    public void setRightClickListener(java.util.function.Consumer<StructureEntry> listener) {
        this.onRightClick = listener;
    }

    /** Set double-click callback (used to locate the structure on the map). */
    public void setDoubleClickListener(java.util.function.Consumer<StructureEntry> listener) {
        this.onDoubleClick = listener;
    }

    /** Update search status (called from PreviewContainer). */
    public void setSearchActive(boolean active, @Nullable Component status) {
        this.searchActive = active;
        this.searchStatus = status;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
        // Show search status row at the top of the list
        if (searchActive && searchStatus != null) {
            int statusY = getY() + 2;
            guiGraphics.fill(getX(), statusY - 1, getX() + getWidth(), statusY + minecraft.font.lineHeight + 1, 0xAA000000);
            guiGraphics.drawString(minecraft.font, searchStatus, getX() + 4, statusY, 0xFFFFFF00);
        }
    }

    public StructureEntry createEntry(short id, Identifier Identifier, NativeImage icon, Item item, String name, boolean show, boolean showByDefault) {
        return new StructureEntry(id, Identifier, icon, item, name, show, showByDefault);
    }

    @Override
    public void replaceEntries(Collection<StructureEntry> entryList) {
        // Release GPU/registry resources held by outgoing entries before swap.
        // CRITICAL: only close textures for entries that are NOT in the new list.
        // StructureEntry objects are reused across calls (they come from
        // allStructures[]), so closing a texture that will be re-added causes
        // "Texture view does not exist" crashes when the entry is rendered.
        final java.util.Set<StructureEntry> newSet = new java.util.HashSet<>(entryList);
        for (StructureEntry entry : children()) {
            if (!newSet.contains(entry)) {
                entry.closeIconTexture();
            }
        }
        super.replaceEntries(entryList);

        // If we have more than one page, make sure we don't let the scrollbar run away
        double maxScroll = Math.max(0.0, super.getItemCount() * super.defaultEntryHeight - super.height);
        if(super.scrollAmount() > maxScroll) {
            // Make sure that the top entry is visible
            super.setScrollAmount(maxScroll);
        }
    }

    public class StructureEntry extends BaseObjectSelectionList.Entry<StructuresList.StructureEntry> implements StructureRenderInfo {
        private final short id;
        private final Identifier structureKey;
        private final NativeImage icon;
        private final Item item;
        private final ItemStack itemStack;
        private final DynamicTexture iconTexture;
        private final int iconWidth;
        private final int iconHeight;
        private final String name;
        private final Tooltip tooltip;
        private final boolean showByDefault;
        private final boolean isPrimaryNamespace;

        private boolean show;
        public final ToggleButton toggleVisible;

        // Set to true when closeIconTexture() is called, so extractContent()
        // can skip rendering a closed/invalid DynamicTexture.
        private volatile boolean textureClosed = false;

        public StructureEntry(short id, Identifier Identifier, @NotNull NativeImage icon, @Nullable Item item, String name, boolean show, boolean showByDefault) {
            this.id = id;
            this.structureKey = Identifier;
            this.item = item;
            this.itemStack = this.item == null ? null : new ItemStack(this.item, 1);
            this.icon = icon;
            this.iconTexture = new DynamicTexture(() -> "wp_structure_icon", this.icon);
            this.iconWidth = this.icon.getWidth();
            this.iconHeight = this.icon.getHeight();
            this.showByDefault = showByDefault;
            this.show = show;
            this.toggleVisible = new ToggleButton(
                    0, 0, 20, 20, /* x, y, width, height */
                    140, 20, 20, 20, /* xTexStart, yTexStart, xDiffTex, yDiffTex */
                    BUTTONS_TEXTURE, BUTTONS_TEX_WIDTH, BUTTONS_TEX_HEIGHT, /* Identifier, textureWidth, textureHeight*/
                    this::toggleVisible
            );

            this.iconTexture.upload();
            this.toggleVisible.selected = show;

            this.isPrimaryNamespace = Identifier.getNamespace().equals("minecraft");
            if (Objects.equals(Identifier.toString(), name) || name == null) {
                this.name = WorldPreviewClient.toTitleCase(Identifier.getPath().replace("_", " "));
            } else {
                this.name = name;
            }

            String tag = "§5§o" + Identifier.getNamespace() + "§r\n§9" + Identifier.getPath() + "§r";
            this.tooltip = Tooltip.create(Component.literal(this.name + "\n\n" + tag));
        }

        /** Unregister and close the list-owned DynamicTexture (NativeImage is owned elsewhere). */
        public void closeIconTexture() {
            if (iconTexture != null && !textureClosed) {
                textureClosed = true;
                WorldPreviewClient.unregisterTexture(iconTexture);
                iconTexture.close();
            }
        }

        public void reset() {
            show = showByDefault;
            toggleVisible.selected = show;
        }

        private void toggleVisible(Button btn) {
            show = toggleVisible.selected;
        }

        public void setVisible(boolean show) {
            this.show = show;
        }

        @Override
        public Tooltip tooltip() {
            return tooltip;
        }

        @Override
        public @NotNull Component getNarration() {
            return Component.empty();
        }

                @Override
        public void renderContent(
                GuiGraphics guiGraphics,
                int mouseX,
                int mouseY,
                boolean hovered,
                float partialTick
        ) {
            int top = getContentY();
            int left = getContentX();
            final int xMin = left + 2;
            final int yMin = top + 2;
            final int xMax = xMin + iconWidth;
            final int yMax = yMin + iconHeight;

            if (item != null) {
                guiGraphics.renderItem(itemStack, xMin, yMin);
            } else if (iconTexture != null && !textureClosed) {
                WorldPreviewClient.renderTexture(guiGraphics, iconTexture, xMin, yMin, xMax, yMax);
            }
            String formatName = isPrimaryNamespace ? name : "§o" + name;
            guiGraphics.drawString(minecraft.font, formatName, left + 16 + 4, top + 6, 0xFFFFFFFF);
            toggleVisible.setPosition(getRowRight() - 22, top);
            toggleVisible.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 1 && onRightClick != null) {
                // Right-click: open the seed search pre-filled with this structure
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                onRightClick.accept(this);
                return true;
            }
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            if (toggleVisible.isMouseOver(event.x(), event.y())) {
                toggleVisible.onClick(event, doubleClick);
                return true;
            }
            if (doubleClick && event.button() == 0 && onDoubleClick != null) {
                onDoubleClick.accept(this);
            }
            return true;
        }

        public Identifier structureId() {
            return structureKey;
        }

        public String name() {
            return name;
        }

        public boolean showByDefault() {
            return showByDefault;
        }

        public boolean show() {
            return show;
        }

        public short id() {
            return id;
        }

        public Item item() {
            return item;
        }

        public ItemStack itemStack() {
            return itemStack;
        }
    }

}
