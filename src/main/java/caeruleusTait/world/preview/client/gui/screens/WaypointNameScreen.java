// Modified from original World Preview (https://modrinth.com/mod/world-preview).
// See CHANGES.md for details.
package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.domain.waypoint.WaypointStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Small modal dialog that names a newly placed waypoint and lets the player
 * pick a pin color. Confirming stores the waypoint via the preview container.
 */
public final class WaypointNameScreen extends Screen {

    private static final int DEFAULT_COLOR_INDEX = 4; // blue

    private final Screen parent;
    private final PreviewContainer container;
    private final BlockPos pos;

    private EditBox nameBox;
    private CycleButton<Integer> colorCycle;
    private Button confirmButton;
    private int colorIndex = DEFAULT_COLOR_INDEX;

    public WaypointNameScreen(Screen parent, PreviewContainer container, BlockPos pos) {
        super(WorldPreviewComponents.WAYPOINT_TITLE);
        this.parent = parent;
        this.container = container;
        this.pos = pos;
    }

    @Override
    protected void init() {
        nameBox = new EditBox(font, width / 2 - 100, height / 2 - 30, 200, 20,
                WorldPreviewComponents.WAYPOINT_NAME);
        nameBox.setMaxLength(32);
        nameBox.setValue(WorldPreviewComponents.WAYPOINT_DEFAULT_NAME.getString());
        nameBox.setResponder(value -> confirmButton.active = !value.isBlank());
        setInitialFocus(nameBox);

        colorCycle = CycleButton.<Integer>builder(index -> Component.translatable(
                        "world_preview.waypoint.color." + index), DEFAULT_COLOR_INDEX)
                .withValues(java.util.List.of(0, 1, 2, 3, 4, 5, 6, 7))
                .create(width / 2 - 100, height / 2 - 4, 200, 20,
                        WorldPreviewComponents.WAYPOINT_COLOR, (btn, value) -> colorIndex = value);

        confirmButton = Button.builder(CommonComponents.GUI_DONE, ignored -> confirm())
                .bounds(width / 2 - 100, height / 2 + 22, 98, 20).build();
        Button cancelButton = Button.builder(CommonComponents.GUI_CANCEL, ignored -> goBack())
                .bounds(width / 2 + 2, height / 2 + 22, 98, 20).build();

        addRenderableWidget(nameBox);
        addRenderableWidget(colorCycle);
        addRenderableWidget(confirmButton);
        addRenderableWidget(cancelButton);
    }

    private void confirm() {
        String name = nameBox.getValue().isBlank()
                ? WorldPreviewComponents.WAYPOINT_DEFAULT_NAME.getString()
                : nameBox.getValue();
        int color = colorIndex >= 0 && colorIndex < WaypointStore.PALETTE.length
                ? WaypointStore.PALETTE[colorIndex]
                : WaypointStore.PALETTE[DEFAULT_COLOR_INDEX];
        container.addWaypoint(name, pos, color);
        goBack();
    }

    private void goBack() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x88101018);
        graphics.centeredText(font, title, width / 2, height / 2 - 52, 0xFFFFFFFF);
        String coords = String.format("§7[%d, %d, %d]§r", pos.getX(), pos.getY(), pos.getZ());
        graphics.centeredText(font, coords, width / 2, height / 2 - 38, 0xFFCCCCCC);

        // Color preview square next to the cycle button
        int previewX = width / 2 + 104;
        int previewY = height / 2 - 2;
        graphics.fill(previewX, previewY, previewX + 14, previewY + 14,
                WaypointStore.PALETTE[colorIndex]);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && confirmButton.active) {
            confirm();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        goBack();
    }
}
