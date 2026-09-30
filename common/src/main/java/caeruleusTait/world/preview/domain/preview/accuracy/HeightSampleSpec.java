package caeruleusTait.world.preview.domain.preview.accuracy;

import java.util.ArrayList;
import java.util.List;

/**
 * X/Z lattice for height sampling within a chunk. Fast and slow height paths
 * should use the same lattice for a given blockStride.
 */
public final class HeightSampleSpec {
    private HeightSampleSpec() {
    }

    /**
     * @return list of {@code int[]{blockX, blockZ}} on the stride lattice
     */
    public static List<int[]> blockPositionsInChunk(int chunkX, int chunkZ, int blockStride) {
        if (blockStride < 1) {
            throw new IllegalArgumentException("blockStride");
        }
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        List<int[]> out = new ArrayList<>();
        for (int x = 0; x < 16; x += blockStride) {
            for (int z = 0; z < 16; z += blockStride) {
                out.add(new int[]{minX + x, minZ + z});
            }
        }
        return out;
    }
}
