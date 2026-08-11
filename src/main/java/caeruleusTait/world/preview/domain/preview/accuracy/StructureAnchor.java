package caeruleusTait.world.preview.domain.preview.accuracy;

/**
 * Structure icon / storage center from an inclusive bounding box.
 * Integer midpoint matches typical structure BB center used in preview.
 */
public final class StructureAnchor {
    private StructureAnchor() {
    }

    public static int centerX(int minX, int maxX) {
        return (minX + maxX) / 2;
    }

    public static int centerY(int minY, int maxY) {
        return (minY + maxY) / 2;
    }

    public static int centerZ(int minZ, int maxZ) {
        return (minZ + maxZ) / 2;
    }
}
