package caeruleusTait.world.preview.backend.analysis;

public record ProfileRequest(int x1, int z1, int x2, int z2,
                             int yMin, int yMax, int step, boolean vertical) {
}
