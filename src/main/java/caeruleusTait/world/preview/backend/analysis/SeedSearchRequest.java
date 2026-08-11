package caeruleusTait.world.preview.backend.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

/**
 * Immutable seed search request snapshot.
 * Created by the main thread when clicking "Find this Biome". Records all parameters needed for the search.
 * Player dragging/scrolling/settings changes during search do not affect the current search scope.
 */
public record SeedSearchRequest(
    /** Target biome Identifier */
    @NotNull Identifier targetBiome,
    /** Current dimension */
    @NotNull String dimension,
    /** Current center coordinates */
    @NotNull BlockPos center,
    /** Current Y layer (block coordinates) */
    int yLevel,
    /** Min X bound of visible screen area (block coords) */
    int viewMinX,
    /** Max X bound of visible screen area (block coords) */
    int viewMaxX,
    /** Min Z bound of visible screen area (block coords) */
    int viewMinZ,
    /** Max Z bound of visible screen area (block coords) */
    int viewMaxZ,
    /** Sampling step (block coords, consistent with PreviewDisplay quartStride) */
    int sampleStep,
    /** Worldgen config fingerprint for validating context on search result callback */
    @NotNull String contextFingerprint,
    /** Maximum number of attempts */
    int maxAttempts,
    /** Search min area percentage (0-100), minimum screen coverage required */
    int minAreaPercent,
    /** Search max distance (block coords), minimum distance from screen center required */
    int maxDistance
) {
    /** Default max attempts */
    public static final int DEFAULT_MAX_ATTEMPTS = 100;

    public SeedSearchRequest {
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be > 0");
        if (sampleStep <= 0) throw new IllegalArgumentException("sampleStep must be > 0");
        if (viewMinX > viewMaxX) throw new IllegalArgumentException("viewMinX must be <= viewMaxX");
        if (viewMinZ > viewMaxZ) throw new IllegalArgumentException("viewMinZ must be <= viewMaxZ");
        if (minAreaPercent < 0 || minAreaPercent > 100) throw new IllegalArgumentException("minAreaPercent must be 0-100");
        if (maxDistance < 0) throw new IllegalArgumentException("maxDistance must be >= 0");
    }
}