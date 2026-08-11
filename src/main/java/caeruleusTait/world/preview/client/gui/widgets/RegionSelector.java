package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.backend.analysis.Region;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class RegionSelector extends AbstractWidget {
    public static final int MAX_DIMENSION = 4096;

    private final List<EditBox> fields = new ArrayList<>();
    private final Consumer<Region> onRegionChanged;
    private Region lastValidRegion;

    public RegionSelector(Font font, int x, int y, int width, int height,
                          Region initial, Consumer<Region> onRegionChanged) {
        super(x, y, width, height, Component.translatable("world_preview.analysis.region"));
        this.onRegionChanged = onRegionChanged == null ? ignored -> {} : onRegionChanged;
        this.lastValidRegion = initial;
        String[] values = {String.valueOf(initial.minX()), String.valueOf(initial.minZ()),
                String.valueOf(initial.maxX()), String.valueOf(initial.maxZ())};
        for (int i = 0; i < 4; i++) {
            EditBox field = new EditBox(font, 0, 0, 70, 20, Component.translatable("world_preview.analysis.coordinate"));
            field.setValue(values[i]);
            field.setResponder(ignored -> updateRegion());
            fields.add(field);
        }
    }

    public static Optional<Region> normalize(String minX, String minZ, String maxX, String maxZ) {
        try {
            long x1 = Long.parseLong(minX.trim());
            long z1 = Long.parseLong(minZ.trim());
            long x2 = Long.parseLong(maxX.trim());
            long z2 = Long.parseLong(maxZ.trim());
            long width = Math.abs(x2 - x1) + 1L;
            long depth = Math.abs(z2 - z1) + 1L;
            if (width < 1 || depth < 1 || width > MAX_DIMENSION || depth > MAX_DIMENSION
                    || x1 < Integer.MIN_VALUE || x1 > Integer.MAX_VALUE
                    || x2 < Integer.MIN_VALUE || x2 > Integer.MAX_VALUE
                    || z1 < Integer.MIN_VALUE || z1 > Integer.MAX_VALUE
                    || z2 < Integer.MIN_VALUE || z2 > Integer.MAX_VALUE) {
                return Optional.empty();
            }
            return Optional.of(Region.of((int) x1, (int) z1, (int) x2, (int) z2));
        } catch (NumberFormatException | ArithmeticException ex) {
            return Optional.empty();
        }
    }

    public Optional<Region> currentRegion() {
        return normalize(fields.get(0).getValue(), fields.get(1).getValue(),
                fields.get(2).getValue(), fields.get(3).getValue());
    }

    public Region lastValidRegion() {
        return lastValidRegion;
    }

    public List<EditBox> fields() {
        return List.copyOf(fields);
    }

    public void setRegion(Region region) {
        lastValidRegion = region;
        String[] values = {String.valueOf(region.minX()), String.valueOf(region.minZ()),
                String.valueOf(region.maxX()), String.valueOf(region.maxZ())};
        for (int i = 0; i < fields.size(); i++) fields.get(i).setValue(values[i]);
    }

    private void updateRegion() {
        currentRegion().ifPresent(region -> {
            lastValidRegion = region;
            onRegionChanged.accept(region);
        });
    }

    @Override
    protected void extractWidgetRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.text(Minecraft.getInstance().font, Component.translatable("world_preview.analysis.region"), getX(), getY(), 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // Label-only widget; coordinate EditBoxes handle their own clicks.
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }

    public void layout(ScreenRectangle area) {
        setX(area.left());
        setY(area.top());
        setWidth(area.width());
        setHeight(Math.max(16, area.height()));
        int column = Math.max(1, (area.width() - 8) / 4);
        for (int i = 0; i < fields.size(); i++) {
            fields.get(i).setX(area.left() + i * column);
            fields.get(i).setY(area.top() + 18);
            fields.get(i).setWidth(Math.min(70, column - 2));
        }
    }
}
