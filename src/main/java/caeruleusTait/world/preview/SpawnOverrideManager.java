package caeruleusTait.world.preview;

/**
 * Manages the spawn override flag that coordinates between the world creation
 * screen (where the player sets the spawn pin) and the server tick mixin
 * (where the spawn point is actually applied).
 *
 * This is a separate non-Mixin class because Mixin classes cannot have
 * non-private static methods.
 */
public final class SpawnOverrideManager {

    private static boolean spawnOverrideApplied = false;

    private SpawnOverrideManager() {}

    /**
     * Returns true if the spawn override should be applied on the next
     * server tick. Returns false after the override has been applied once.
     */
    public static boolean shouldApply() {
        return !spawnOverrideApplied;
    }

    /**
     * Marks the spawn override as applied so it only runs once per world.
     */
    public static void markApplied() {
        spawnOverrideApplied = true;
    }

    /**
     * Resets the spawn override flag. Called when a new world creation
     * session starts (from CreateWorldScreenMixin.onCreate).
     */
    public static void reset() {
        spawnOverrideApplied = false;
    }
}
