package caeruleusTait.world.preview.domain.preview.accuracy;

/**
 * World-space axis-aligned box used for WorkManager.queueRange.
 */
public record QueueAabb(int minX, int y, int minZ, int maxX, int maxZ) {
    public static QueueAabb fromViewport(ViewportMapping map, int preloadBlocks) {
        int p = Math.max(0, preloadBlocks);
        return new QueueAabb(
                map.worldMinX() - p,
                map.centerY(),
                map.worldMinZ() - p,
                map.worldMaxX() + p,
                map.worldMaxZ() + p
        );
    }
}
