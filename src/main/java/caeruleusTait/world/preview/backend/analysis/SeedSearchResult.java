package caeruleusTait.world.preview.backend.analysis;

/**
 * Immutable search result representing the final state of a seed search.
 */
public sealed interface SeedSearchResult {

    /**
     * Hit: found a seed containing the target biome.
     */
    record Hit(long seed) implements SeedSearchResult {}

    /**
     * Miss: exhausted all candidate seeds without finding the target biome.
     */
    record Miss() implements SeedSearchResult {}

    /**
     * Cancelled: search was manually cancelled by the player.
     */
    record Cancelled() implements SeedSearchResult {}
}