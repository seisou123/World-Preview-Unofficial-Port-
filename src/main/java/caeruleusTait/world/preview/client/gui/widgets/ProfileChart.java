package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.backend.analysis.AnalysisDataState;
import caeruleusTait.world.preview.backend.analysis.ProfilePoint;
import caeruleusTait.world.preview.backend.analysis.ProfileResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class ProfileChart extends AbstractWidget {
    private ProfileResult result;
    private String hoverText = "";

    public ProfileChart(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("world_preview.analysis.profile"));
    }

    public void setResult(ProfileResult result) {
        this.result = result;
    }

    public ProfileResult result() {
        return result;
    }

    public String hoverText() {
        return hoverText;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xAA101418);
        graphics.text(Minecraft.getInstance().font, Component.translatable("world_preview.analysis.profile"), getX() + 6, getY() + 6, 0xFFFFFFFF);
        if (result == null) {
            graphics.text(Minecraft.getInstance().font, Component.translatable("world_preview.analysis.pending"), getX() + 6, getY() + 22, 0xFFE0E4E8);
            return;
        }
        List<ProfilePoint> points = result.points();
        List<ProfilePoint> sampled = points.stream().filter(p -> p.state() == AnalysisDataState.SAMPLED).toList();
        if (sampled.size() >= 2) {
            int left = getX() + 8;
            int right = getX() + width - 8;
            int top = getY() + 24;
            int bottom = getY() + height - 12;
            int min = sampled.stream().mapToInt(ProfilePoint::height).min().orElse(0);
            int max = sampled.stream().mapToInt(ProfilePoint::height).max().orElse(1);
            if (max == min) max++;
            for (int i = 1; i < points.size(); i++) {
                ProfilePoint a = points.get(i - 1);
                ProfilePoint b = points.get(i);
                if (a.state() != AnalysisDataState.SAMPLED || b.state() != AnalysisDataState.SAMPLED) {
                    continue;
                }
                graphics.horizontalLine(xFor(i - 1, points.size(), left, right),
                        xFor(i, points.size(), left, right), yFor(a.height(), min, max, top, bottom), 0xFF63B3ED);
                graphics.verticalLine(xFor(i, points.size(), left, right),
                        yFor(a.height(), min, max, top, bottom),
                        yFor(b.height(), min, max, top, bottom), 0xFF63B3ED);
                graphics.fill(xFor(i, points.size(), left, right), yFor(b.height(), min, max, top, bottom),
                        xFor(i, points.size(), left, right) + 1, yFor(b.height(), min, max, top, bottom) + 1, 0xFF63B3ED);
            }

            if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
                int index = Math.min(points.size() - 1, Math.max(0,
                        Math.round((mouseX - left) * (points.size() - 1f) / Math.max(1, right - left))));
                ProfilePoint point = points.get(index);
                hoverText = Component.translatable("world_preview.analysis.profile.point", point.x(), point.y(), point.z(), point.height()).getString();
                int tooltipX = mouseX + 6;
                int tooltipY = mouseY - 12;
                int textWidth = Minecraft.getInstance().font.width(Component.literal(hoverText));
                if (tooltipX + textWidth > getX() + getWidth()) tooltipX = mouseX - textWidth - 6;
                if (tooltipY < getY()) tooltipY = mouseY + 12;
                if (tooltipY + 9 < getY()) tooltipY = getY();
                graphics.text(Minecraft.getInstance().font, Component.literal(hoverText), tooltipX, tooltipY, 0xFFFFFFFF);
            }
        } else {
            hoverText = "";
            graphics.text(Minecraft.getInstance().font,
                    result.state() == AnalysisDataState.UNAVAILABLE
                            ? Component.translatable("world_preview.analysis.unavailable")
                            : Component.translatable("world_preview.analysis.pending"),
                    getX() + 6, getY() + 24, 0xFFE0E4E8);
        }
    }

    private static int xFor(int index, int size, int left, int right) {
        return left + (int) ((long) (right - left) * index / Math.max(1, size - 1));
    }

    private static int yFor(int value, int min, int max, int top, int bottom) {
        return bottom - (int) ((long) (bottom - top) * (value - min) / Math.max(1, max - min));
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // Display-only: never consume clicks that should reach the back button.
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
