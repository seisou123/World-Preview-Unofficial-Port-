// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets.lists;

import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.client.WorldPreviewClient;
import caeruleusTait.world.preview.client.gui.screens.PreviewContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.function.Consumer;

import static caeruleusTait.world.preview.WorldPreview.nativeColor;

public class BiomesList extends BaseObjectSelectionList<BiomesList.BiomeEntry> {
        private Consumer<BiomeEntry> onBiomeSelected;
    private final boolean allowDeselecting;
    private final PreviewContainer previewContainer;

    @Nullable private Consumer<BiomeEntry> onRightClick;

    public BiomesList(PreviewContainer previewContainer, Minecraft minecraft, int width, int height, int x, int y, boolean allowDeselecting) {
        super(minecraft, width, height, x, y, 16);
        this.allowDeselecting = allowDeselecting;
        this.previewContainer = previewContainer;
    }

    public BiomeEntry createEntry(Holder.Reference<Biome> entry, short id, int color, int initialColor, boolean isCave, boolean initialIsCave, String explicitName, PreviewData.DataSource dataSource) {
        return new BiomeEntry(entry, id, color, initialColor, isCave, initialIsCave, explicitName, dataSource);
    }

    public void setSelected(@Nullable BiomesList.BiomeEntry entry)
    {
        setSelected(entry, false);
    }

    public void setSelected(@Nullable BiomesList.BiomeEntry entry, boolean centerScroll) {
        super.setSelected(entry);
        if(centerScroll == true) {
            super.centerScrollOn(entry);
        }
        onBiomeSelected.accept(entry);
    }



    /**
     * On deselect, {@code null} will be sent!
     */
        public void setBiomeChangeListener(Consumer<BiomeEntry> onBiomeSelected) {
        this.onBiomeSelected = onBiomeSelected;
    }

    /** Set right-click callback */
    public void setRightClickListener(Consumer<BiomeEntry> listener) {
        this.onRightClick = listener;
    }

        @Override
    public void replaceEntries(Collection<BiomeEntry> entryList) {
        final BiomeEntry oldEntry = getSelected();
        super.replaceEntries(entryList);

        if (entryList.contains(oldEntry)) {
            setSelected(oldEntry);
        }

        // If we have more than one page, make sure we don't let the scrollbar run away
        double maxScroll = Math.max(0.0, super.getItemCount() * super.defaultEntryHeight - super.height);
        if(super.scrollAmount() > maxScroll) {
            // Make sure that the top entry is visible
            super.setScrollAmount(maxScroll);
        }
    }

    public class BiomeEntry extends BaseObjectSelectionList.Entry<BiomeEntry> {
        private final short id;
        private final String name;
        private int color;
        private boolean isCave;
        private final int initialColor;
        private final boolean initialIsCave;
        private final Holder.Reference<Biome> entry;
        private PreviewData.DataSource dataSource;
        private final Tooltip tooltip;
        private final PreviewData.DataSource initialDataSource;
        private final boolean isPrimaryNamespace;
        /** Pixel count of this biome on screen (0 = not visible) */
        private long visibleCount;

        public BiomeEntry(Holder.Reference<Biome> entry, short id, int color, int initialColor, boolean isCave, boolean initialIsCave, String explicitName, PreviewData.DataSource dataSource) {
            this.entry = entry;
            this.id = id;
            this.color = color;
            this.initialColor = initialColor;
            this.isCave = isCave;
            this.initialIsCave = initialIsCave;
            this.dataSource = dataSource;
            this.initialDataSource = dataSource;
            final Identifier Identifier = entry.key().identifier();
            final String langKey = Identifier.toLanguageKey("biome");
            if (Language.getInstance().has(langKey)) {
                this.name = Component.translatable(langKey).getString();
            } else if (explicitName != null && !explicitName.isBlank()) {
                this.name = explicitName;
            } else {
                this.name = WorldPreviewClient.toTitleCase(Identifier.getPath().replace("_", " "));
            }
            this.isPrimaryNamespace = Identifier.getNamespace().equals("minecraft");

            String tag = "§5§o" + Identifier.getNamespace() + "§r\n§9" + Identifier.getPath() + "§r";
            String sourceKey = "world_preview.settings.biomes.source." + dataSource.name();
            String sourceLabel = Component.translatable("world_preview.tooltip.biome.source").getString();
            String sourceValue = Component.translatable(sourceKey).getString();
            this.tooltip = Tooltip.create(Component.literal(this.name + "\n\n" + tag + "\n\n§7" + sourceLabel + ": " + sourceValue + "§r"));
        }

        public String name() {
            return name;
        }

        public Component statusComponent() {
            return Component.translatable("world_preview.settings.biomes.source." + dataSource.name());
        }

        public Holder.Reference<Biome> entry() {
            return entry;
        }

        public short id() {
            return id;
        }

        public int color() {
            return color;
        }

        public boolean isCave() {
            return isCave;
        }

        public PreviewData.DataSource dataSource() {
            return dataSource;
        }

        /** Capture the complete editable state so an enclosing screen can roll back. */
        public State state() {
            return new State(color, isCave, dataSource);
        }

        /** Restore a previously captured state without changing its data source. */
        public void restore(State state) {
            if (state == null) {
                return;
            }
            color = state.color();
            isCave = state.cave();
            dataSource = state.dataSource();
        }

        public record State(int color, boolean cave, PreviewData.DataSource dataSource) {}

        public PreviewContainer previewTab() {
            return previewContainer;
        }

        @Override
        public Tooltip tooltip() {
            return tooltip;
        }

        public void reset() {
            color = initialColor;
            isCave = initialIsCave;
            dataSource = initialDataSource == PreviewData.DataSource.CONFIG ? PreviewData.DataSource.RESOURCE : initialDataSource;
        }

        public void changeColor(int newColor) {
            color = newColor & 0x00FFFFFF;
            dataSource = PreviewData.DataSource.CONFIG;
        }

                public void setCave(boolean cave) {
            isCave = cave;
        }

        public void setVisibleCount(long count) {
            this.visibleCount = count;
        }

        public long visibleCount() {
            return visibleCount;
        }

        @Override
        public @NotNull Component getNarration() {
            return Component.translatable("narrator.select", this.name);
        }

        @Override
        public void renderContent(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int top = getContentY();
            int left = getContentX();
            guiGraphics.fill(left + 3, top + 1, left + 13, top + 11, nativeColor(color));
            String formatName = isPrimaryNamespace ? name : "§o" + name;
            // Invisible biomes use slightly darker gray for subtle contrast with visible ones
            int nameColor = visibleCount > 0 ? 0xFFFFFFFF : 0xFF777777;
            guiGraphics.drawString(BiomesList.this.minecraft.font, formatName, left + 16, top + 2, nameColor);
            // Show visible count badge (right side) - only when enabled in config
            if (previewContainer.worldPreview().cfg().showBiomeCounts) {
                String countStr = "×" + visibleCount;
                int countWidth = BiomesList.this.minecraft.font.width(countStr);
                int rowRight = BiomesList.this.getRowRight();
                int countColor = visibleCount > 0 ? 0xFF88FF88 : 0xFF666666;
                guiGraphics.drawString(BiomesList.this.minecraft.font, countStr, rowRight - countWidth - 4, top + 2, countColor);
            }
        }

                @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 1 && onRightClick != null) {
                // Right-click: trigger search/stop
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                onRightClick.accept(this);
                return true;
            }
            if (event.button() != 0) {
                return false;
            }

            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            boolean isSelected = getSelected() != null && id == getSelected().id;
            if (isSelected && allowDeselecting) {
                setSelected(null);
                return false;
            } else {
                // Explicitly select this entry so that the biome change
                // listener fires even if the parent list does not call
                // setSelected automatically.
                setSelected(this);
                return true;
            }
        }
    }
}
