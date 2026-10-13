package caeruleusTait.world.preview.domain.task;

/**
 * Thrown when an illegal state transition is attempted on a task.
 */
public class TaskTransitionException extends RuntimeException {

    private final TaskState from;
    private final TaskState to;

    public TaskTransitionException(TaskState from, TaskState to) {
        super("Illegal task state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public TaskState from() {
        return from;
    }

    public TaskState to() {
        return to;
    }
}
