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

    // Written from the client thread (reset) and the server tick thread
    // (markApplied), read from the server tick thread.  Starts as "applied"
    // so the override only arms when a world creation runs: opening a plain
    // old save must never rewrite its spawn point.
    private static volatile boolean spawnOverrideApplied = true;

    // Set on the first failed apply attempt of the current pending window so
    // the retry does not flood the log.
    private static volatile boolean failureLogged = false;

    // The apply is retried a bounded number of times per pending window (the
    // fabric mixin retries every server tick), so a persistently failing
    // command cannot keep retrying for the whole life of the world.
    private static final int MAX_ATTEMPTS = 20;
    private static volatile int failedAttempts = 0;

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
     * Re-arms the spawn override flag. Called when a new world creation
     * session starts (from CreateWorldScreenMixin.onCreate), so the pinned
     * spawn applies to the world being created and to no other world.
     */
    public static void reset() {
        spawnOverrideApplied = false;
        failureLogged = false;
        failedAttempts = 0;
    }

    /** True once a failed apply attempt of the current pending window was logged. */
    public static boolean hasLoggedFailure() {
        return failureLogged;
    }

    /** Marks the current pending window's failure as logged. */
    public static void markFailureLogged() {
        failureLogged = true;
    }

    /**
     * Records one failed apply attempt.  When the attempts for the current
     * pending window run out, the pending flag is consumed so the override
     * stops retrying; callers detect that via {@link #shouldApply()}.
     */
    public static void recordFailure() {
        failedAttempts += 1;
        if (failedAttempts >= MAX_ATTEMPTS) {
            spawnOverrideApplied = true;
            failedAttempts = 0;
        }
    }
}
