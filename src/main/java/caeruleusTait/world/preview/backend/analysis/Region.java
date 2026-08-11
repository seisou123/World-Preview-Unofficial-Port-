package caeruleusTait.world.preview.backend.analysis;

public record Region(int minX, int minZ, int maxX, int maxZ) {
    public static Region of(int x1, int z1, int x2, int z2) {
        long minX = Math.min(x1, x2);
        long maxX = Math.max(x1, x2);
        long minZ = Math.min(z1, z2);
        long maxZ = Math.max(z1, z2);
        long width = maxX - minX + 1L;
        long depth = maxZ - minZ + 1L;
        if (width <= 0 || depth <= 0 || width > Integer.MAX_VALUE || depth > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Region is outside supported bounds");
        }
        return new Region((int) minX, (int) minZ, (int) maxX, (int) maxZ);
    }

    public long blockArea() {
        return ((long) maxX - minX + 1L) * ((long) maxZ - minZ + 1L);
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
