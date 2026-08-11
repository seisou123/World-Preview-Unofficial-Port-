package caeruleusTait.world.preview.domain.ui;

/**
 * Describes a layout for UI controls.
 *
 * <p>Replaces hand-written coordinate layouts with a declarative description.
 */
public record ControlLayout(
        Orientation orientation,
        int rows,
        int columns,
        int horizontalGap,
        int verticalGap,
        int padding
) {

    public ControlLayout {
        Objects.requireNonNull(orientation, "orientation");
        if (rows < 0) rows = 1;
        if (columns < 0) columns = 1;
        if (horizontalGap < 0) horizontalGap = 0;
        if (verticalGap < 0) verticalGap = 0;
        if (padding < 0) padding = 0;
    }

    /** Layout orientation. */
    public enum Orientation {
        ROW,
        COLUMN,
        GRID
    }

    /** Creates a row layout with the given number of columns. */
    public static ControlLayout row(int columns, int gap) {
        return new ControlLayout(Orientation.ROW, 1, columns, gap, gap, 0);
    }

    /** Creates a column layout with the given number of rows. */
    public static ControlLayout column(int rows, int gap) {
        return new ControlLayout(Orientation.COLUMN, rows, 1, gap, gap, 0);
    }

    /** Creates a grid layout. */
    public static ControlLayout grid(int rows, int columns, int gap) {
        return new ControlLayout(Orientation.GRID, rows, columns, gap, gap, 0);
    }

    private static class Objects {
        static <T> T requireNonNull(T obj, String name) {
            if (obj == null) throw new NullPointerException(name + " must not be null");
            return obj;
        }
    }
}
