package caeruleusTait.world.preview.domain.task;

/**
 * Immutable progress snapshot for a task.
 *
 * @param completedUnits number of completed work units
 * @param totalUnits     total number of work units (0 if unknown)
 * @param sampledPoints  number of sampled data points (domain-specific metric)
 * @param stage          human-readable stage name
 */
public record TaskProgress(
        long completedUnits,
        long totalUnits,
        long sampledPoints,
        String stage
) {

    public TaskProgress {
        if (completedUnits < 0) completedUnits = 0;
        if (totalUnits < 0) totalUnits = 0;
        if (sampledPoints < 0) sampledPoints = 0;
        stage = stage == null ? "" : stage;
    }

    /** Creates an initial progress snapshot for a task with the given total. */
    public static TaskProgress initial(long totalUnits, String stage) {
        return new TaskProgress(0, totalUnits, 0, stage);
    }

    /** Creates a progress snapshot representing a not-started task. */
    public static TaskProgress notStarted(long totalUnits) {
        return new TaskProgress(0, totalUnits, 0, "not started");
    }

    /** Returns the percentage of completion (0..1), or 0 if total is unknown. */
    public double percentage() {
        if (totalUnits <= 0) return 0.0;
        return Math.min(1.0, (double) completedUnits / (double) totalUnits);
    }

    /** Returns the number of pending units. */
    public long pendingUnits() {
        return Math.max(0, totalUnits - completedUnits);
    }

    /**
     * Merges this progress with another, producing a combined snapshot.
     * Useful when aggregating progress from sub-tasks.
     */
    public TaskProgress merge(TaskProgress other) {
        if (other == null) return this;
        return new TaskProgress(
                completedUnits + other.completedUnits,
                totalUnits + other.totalUnits,
                sampledPoints + other.sampledPoints,
                stage.isEmpty() ? other.stage : stage
        );
    }

    /** Returns a new progress with additional completed units. */
    public TaskProgress withCompleted(long delta) {
        return new TaskProgress(completedUnits + delta, totalUnits, sampledPoints, stage);
    }

    /** Returns a new progress with additional sampled points. */
    public TaskProgress withSampled(long delta) {
        return new TaskProgress(completedUnits, totalUnits, sampledPoints + delta, stage);
    }

    /** Returns a new progress with an updated stage. */
    public TaskProgress withStage(String newStage) {
        return new TaskProgress(completedUnits, totalUnits, sampledPoints, newStage);
    }
}
