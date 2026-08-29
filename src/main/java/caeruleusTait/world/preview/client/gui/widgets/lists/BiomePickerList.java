// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets.lists;

import caeruleusTait.world.preview.client.WorldPreviewComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static caeruleusTait.world.preview.WorldPreview.nativeColor;

/**
 * Multi-select biome picker list used by the seed search screen: each row is
 * a biome with its color chip, a check mark when selected and a gray
 * {@code [cave]} badge for cave biomes. Rows are filtered by a case-insensitive
 * text filter (display name or identifier path) and cave biomes can be hidden.
 * <p>
 * Selection is tracked by identifier and survives filter/toggle rebuilds;
 * the maximum number of selected biomes is enforced here and reported to the
 * owning screen through {@link #setOnSelectionRejected(Consumer)}.
 * </p>
 */
public class BiomePickerList extends BaseObjectSelectionList<BiomePickerList.Row> {

    /** The biomes offered by the picker, in display order. */
    private final List<BiomesList.BiomeEntry> entries;

    /** Selected biome identifiers (insertion order not significant; output order follows {@link #entries}). */
    private final Set<Identifier> selected = new LinkedHashSet<>();

    @Nullable private String filter = null;
    private boolean showCaves = false;
    private int maxSelections = Integer.MAX_VALUE;

    /** Called instead of selecting when the maximum selection is already reached. */
    @Nullable private Consumer<Identifier> onSelectionRejected;

    public BiomePickerList(Minecraft minecraft, List<BiomesList.BiomeEntry> entries) {
        super(minecraft, 100, 100, 0, 0, 16);
        this.entries = List.copyOf(entries);
        rebuild();
    }

    // ===== Configuration =====

    /** Sets a case-insensitive filter matched against the display name and the identifier path. */
    public void setFilter(@Nullable String filter) {
        String normalized = filter == null ? null : filter.strip().toLowerCase(Locale.ROOT);
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        if (java.util.Objects.equals(normalized, this.filter)) {
            return;
        }
        this.filter = normalized;
        rebuild();
    }

    /** Whether cave biomes are listed (they are hidden by default). */
    public void setShowCaves(boolean showCaves) {
        if (showCaves == this.showCaves) {
            return;
        }
        this.showCaves = showCaves;
        rebuild();
    }

    /** Maximum number of biomes that can be selected at once. */
    public void setMaxSelections(int maxSelections) {
        this.maxSelections = Math.max(1, maxSelections);
    }

    /** Callback invoked (with the rejected biome id) when a selection would exceed {@link #maxSelections}. */
    public void setOnSelectionRejected(@Nullable Consumer<Identifier> onSelectionRejected) {
        this.onSelectionRejected = onSelectionRejected;
    }

    // ===== Selection state =====

    /** The selected biome identifiers in entry (display) order. */
    public List<Identifier> getSelectedIds() {
        List<Identifier> ids = new ArrayList<>();
        for (BiomesList.BiomeEntry entry : entries) {
            Identifier id = entry.entry().key().identifier();
            if (selected.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** The selected biome entries in entry (display) order. */
    public List<BiomesList.BiomeEntry> getSelectedEntries() {
        List<BiomesList.BiomeEntry> result = new ArrayList<>();
        for (BiomesList.BiomeEntry entry : entries) {
            if (selected.contains(entry.entry().key().identifier())) {
                result.add(entry);
            }
        }
        return result;
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public void clearSelection() {
        selected.clear();
    }

    /** Selects exactly the given biome (used to prefill from a right-clicked entry); null clears. */
    public void selectOnly(@Nullable Identifier id) {
        selected.clear();
        if (id != null) {
            selected.add(id);
        }
    }

    // ===== Row management =====

    /** Rebuilds the visible rows from the filter and cave toggle, preserving selection. */
    private void rebuild() {
        List<Row> rows = new ArrayList<>();
        for (BiomesList.BiomeEntry entry : entries) {
            if (!showCaves && entry.isCave()) {
                continue;
            }
            if (filter != null) {
                Identifier id = entry.entry().key().identifier();
                if (!entry.name().toLowerCase(Locale.ROOT).contains(filter)
                        && !id.getPath().toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
            }
            rows.add(new Row(entry));
        }
        replaceEntries(rows);
    }

    public class Row extends BaseObjectSelectionList.Entry<Row> {
        private final BiomesList.BiomeEntry entry;

        private Row(BiomesList.BiomeEntry entry) {
            this.entry = entry;
        }

        public Identifier id() {
            return entry.entry().key().identifier();
        }

        private boolean isSelected() {
            return selected.contains(id());
        }

        private String rowText() {
            String text = isSelected() ? "§a✔ §r" : "";
            text += entry.name();
            if (entry.isCave()) {
                text += " §7" + WorldPreviewComponents.SEARCH_BIOME_CAVE_TAG.getString() + "§r";
            }
            return text;
        }

        @Override
        public @NotNull Component getNarration() {
            return Component.translatable("narrator.select", rowText());
        }

        @Override
        public Tooltip tooltip() {
            return entry.tooltip();
        }

        @Override
        public void extractContent(@NotNull GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int top = getContentY();
            int left = getContentX();
            guiGraphics.fill(left + 3, top + 1, left + 13, top + 11, nativeColor(entry.color()));
            guiGraphics.text(BiomePickerList.this.minecraft.font, rowText(), left + 16, top + 2, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != 0) {
                return false;
            }
            BiomePickerList.this.minecraft.getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            Identifier id = id();
            if (selected.contains(id)) {
                selected.remove(id);
            } else if (selected.size() >= maxSelections) {
                if (onSelectionRejected != null) {
                    onSelectionRejected.accept(id);
                }
            } else {
                selected.add(id);
            }
            return true;
        }
    }
}
