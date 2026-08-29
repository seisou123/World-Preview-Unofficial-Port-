// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets.lists;

import caeruleusTait.world.preview.client.WorldPreviewComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Single-pick structure selection list used by the structure picker screen:
 * the first row is always a "None" option, followed by one row per structure
 * showing its item icon and name, with a green check mark on the current
 * selection. Rows are filtered by a case-insensitive text filter (display
 * name or identifier path); the "None" row always stays visible at the top.
 * Picking a row is reported to the owning screen via
 * {@link #setOnPick(Consumer)}.
 */
public class StructurePickList extends BaseObjectSelectionList<StructurePickList.Row> {

    /** The structures offered by the picker, in display order. */
    private final List<StructuresList.StructureEntry> entries;

    /** Identifier of the currently selected structure (null = the "None" row). */
    @Nullable private final Identifier currentId;

    @Nullable private String filter = null;

    /** Called with the picked option when a row is clicked. */
    @Nullable private Consumer<Pick> onPick;

    public StructurePickList(Minecraft minecraft, List<StructuresList.StructureEntry> entries, @Nullable Identifier currentId) {
        super(minecraft, 100, 100, 0, 0, 20);
        this.entries = List.copyOf(entries);
        this.currentId = currentId;
        rebuild();
    }

    // ===== Configuration =====

    /** Sets a case-insensitive filter matched against the display name and the identifier path. */
    public void setFilter(@Nullable String filter) {
        String normalized = filter == null ? null : filter.strip().toLowerCase(Locale.ROOT);
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        this.filter = normalized;
        rebuild();
    }

    /** Callback invoked when a row is picked (id and name are null for the "None" row). */
    public void setOnPick(@Nullable Consumer<Pick> listener) {
        this.onPick = listener;
    }

    // ===== Row management =====

    /** Rebuilds the visible rows from the filter; the "None" row always stays on top. */
    private void rebuild() {
        List<Row> rows = new ArrayList<>();
        rows.add(createRow(null));
        for (StructuresList.StructureEntry entry : entries) {
            if (filter != null
                    && !entry.name().toLowerCase(Locale.ROOT).contains(filter)
                    && !entry.structureId().getPath().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            rows.add(createRow(entry));
        }
        replaceEntries(rows);
    }

    /** Factory for rows: inner-class rows need an enclosing instance, so they are built here. */
    private Row createRow(@Nullable StructuresList.StructureEntry entry) {
        return new Row(entry);
    }

    public class Row extends BaseObjectSelectionList.Entry<Row> {
        @Nullable private final StructuresList.StructureEntry entry;

        private Row(@Nullable StructuresList.StructureEntry entry) {
            this.entry = entry;
        }

        @Nullable
        public Identifier id() {
            return entry != null ? entry.structureId() : null;
        }

        private boolean isSelected() {
            return Objects.equals(id(), currentId);
        }

        private String rowText() {
            String text = isSelected() ? "§a✔ §r" : "";
            text += entry != null ? entry.name() : WorldPreviewComponents.SEARCH_STRUCTURE_NONE.getString();
            return text;
        }

        @Override
        public @NotNull Component getNarration() {
            return Component.translatable("narrator.select", rowText());
        }

        @Override
        public void extractContent(@NotNull GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int top = getContentY();
            int left = getContentX();
            if (entry != null && entry.displayItem() != null) {
                guiGraphics.item(entry.displayItem(), left + 2, top + 2);
            }
            guiGraphics.text(StructurePickList.this.minecraft.font, rowText(), left + 20, top + 6, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != 0) {
                return false;
            }
            StructurePickList.this.minecraft.getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            if (onPick != null) {
                onPick.accept(new Pick(id(), entry != null ? entry.name() : null));
            }
            return true;
        }
    }

    /** One picked option: a structure (id + name), or the "None" row (both null). */
    public record Pick(@Nullable Identifier id, @Nullable String name) {
    }
}
