package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import caeruleusTait.world.preview.client.gui.widgets.lists.SeedsList;
import caeruleusTait.world.preview.client.gui.widgets.lists.StructuresList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.BTN_SWITCH_STRUCT_DISABLED;

/**
 * Owns sidebar tab state (biomes / structures / seeds) for {@link PreviewContainer}.
 */
public final class PreviewContainerTabManager {
    public enum DisplayType {
        BIOMES,
        STRUCTURES,
        SEEDS,
        ;

        public Component component() {
            return toComponent(this);
        }

        public static Component toComponent(DisplayType x) {
            return Component.translatable("world_preview.preview.btn-cycle." + x.name());
        }
    }

    private final WorldPreviewConfig cfg;
    private final BiomesList biomesList;
    private final StructuresList structuresList;
    private final SeedsList seedsList;
    private final Button switchBiomes;
    private final Button switchStructures;
    private final Button switchSeeds;
    private final Button resetDefaultStructureVisibility;

    private DisplayType currentDisplayType = DisplayType.BIOMES;

    public PreviewContainerTabManager(
            WorldPreviewConfig cfg,
            BiomesList biomesList,
            StructuresList structuresList,
            SeedsList seedsList,
            Button switchBiomes,
            Button switchStructures,
            Button switchSeeds,
            Button resetDefaultStructureVisibility
    ) {
        this.cfg = cfg;
        this.biomesList = biomesList;
        this.structuresList = structuresList;
        this.seedsList = seedsList;
        this.switchBiomes = switchBiomes;
        this.switchStructures = switchStructures;
        this.switchSeeds = switchSeeds;
        this.resetDefaultStructureVisibility = resetDefaultStructureVisibility;
    }

    public DisplayType currentDisplayType() {
        return currentDisplayType;
    }

    private static void markSelected(Button button, boolean selected) {
        if (button instanceof caeruleusTait.world.preview.client.gui.widgets.TranslucentButton tb) {
            tb.setSelected(selected);
        }
    }

    public void resetTabs() {
        onTabButtonChange(switchBiomes, DisplayType.BIOMES);
    }

    public void reapplyCurrentTab() {
        if (currentDisplayType == null) {
            return;
        }
        Button btn = switch (currentDisplayType) {
            case BIOMES -> switchBiomes;
            case STRUCTURES -> switchStructures;
            case SEEDS -> switchSeeds;
        };
        onTabButtonChange(btn, currentDisplayType);
    }

    public void onTabButtonChange(Button btn, DisplayType type) {
        currentDisplayType = type;
        biomesList.visible = false;
        biomesList.active = false;
        structuresList.visible = false;
        structuresList.active = false;
        seedsList.visible = false;
        seedsList.active = false;

        switchBiomes.active = true;
        switchStructures.active = true;
        switchSeeds.active = true;

        resetDefaultStructureVisibility.visible = false;

        if (cfg.sampleStructures) {
            switchStructures.setTooltip(null);
        } else {
            switchStructures.setTooltip(Tooltip.create(BTN_SWITCH_STRUCT_DISABLED));
            switchStructures.active = false;
        }

        btn.active = false;
        // Drive the accent selected-state of the rail buttons (TranslucentButton).
        markSelected(switchBiomes, type == DisplayType.BIOMES);
        markSelected(switchStructures, type == DisplayType.STRUCTURES);
        markSelected(switchSeeds, type == DisplayType.SEEDS);
        switch (type) {
            case BIOMES -> {
                biomesList.visible = true;
                biomesList.active = true;
            }
            case STRUCTURES -> {
                resetDefaultStructureVisibility.visible = true;
                structuresList.visible = true;
                structuresList.active = true;
            }
            case SEEDS -> {
                seedsList.visible = true;
                seedsList.active = true;
            }
        }
    }
}
