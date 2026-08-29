// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.widgets.lists;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Flat result list shared by the seed search screen: shows search hits
 * (seed + score) and history entries (label + seed). Row interactions:
 * left-click applies the seed, right-click deletes (history rows) and
 * shift+right-click toggles the favorite star.
 */
public class SearchResultsList extends BaseObjectSelectionList<SearchResultsList.Row> {

    /** Callbacks from rows back to the owning screen. */
    public interface RowActions {
        void onApply(String seed);

        void onToggleFavorite(String seed);

        void onDelete(String seed);
    }

    private final RowActions actions;

    public SearchResultsList(Minecraft minecraft, RowActions actions) {
        super(minecraft, 100, 100, 0, 0, 20);
        this.actions = actions;
    }

    public void setRows(Collection<Row> rows) {
        replaceEntries(rows);
    }

    /** Creates a row bound to this list instance. */
    public Row createRow(String seed, @Nullable String label, double score, boolean favorite, boolean deletable) {
        return new Row(seed, label, score, favorite, deletable);
    }

    public class Row extends BaseObjectSelectionList.Entry<Row> {
        public final String seed;
        @Nullable public final String label;
        public final double score;
        public final boolean favorite;
        /** Whether right-click deletes this row (history entries). */
        public final boolean deletable;

        public Row(String seed, @Nullable String label, double score, boolean favorite, boolean deletable) {
            this.seed = seed;
            this.label = label;
            this.score = score;
            this.favorite = favorite;
            this.deletable = deletable;
        }

        private String primaryText() {
            String base = (label == null || label.isBlank()) ? seed : label;
            return (favorite ? "§6★ §r" : "") + base;
        }

        @Override
        public @NotNull Component getNarration() {
            return Component.translatable("narrator.select", primaryText());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int top = getContentY();
            int left = getContentX();
            guiGraphics.text(minecraft.font, primaryText(), left + 4, top + 3, 0xFFFFFFFF);
            String detail = (label == null || label.isBlank()) ? "" : seed;
            if (score > 0) {
                detail = (detail.isEmpty() ? "" : detail + "  ") + String.format("§7%.0f", score);
            }
            if (!detail.isEmpty()) {
                int detailWidth = minecraft.font.width(detail);
                int rowRight = SearchResultsList.this.getRowRight();
                guiGraphics.text(minecraft.font, detail, rowRight - detailWidth - 4, top + 3, 0xFFAAAAAA);
            }
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 0) {
                actions.onApply(seed);
                return true;
            }
            if (event.button() == 1) {
                if (event.hasShiftDown()) {
                    actions.onToggleFavorite(seed);
                } else if (deletable) {
                    actions.onDelete(seed);
                } else {
                    actions.onToggleFavorite(seed);
                }
                return true;
            }
            return false;
        }
    }
}
