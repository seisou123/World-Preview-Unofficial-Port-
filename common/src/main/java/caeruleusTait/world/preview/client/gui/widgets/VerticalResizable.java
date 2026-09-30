package caeruleusTait.world.preview.client.gui.widgets;

/** 1.20.1 {@code AbstractWidget} has no {@code setHeight}; widgets that support
 * post-construction height changes implement this so shared layout helpers can
 * resize them. */
public interface VerticalResizable {
    void setHeight(int height);
}
