package caeruleusTait.world.preview.domain.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A group of tasks that supports batch operations: cancel all, wait for all,
 * and parent/child relationships.
 *
 * <p>Replaces the batch operation logic previously duplicated in
 * {@code WorkBatch}.
 */
public final class TaskGroup {

    private final TaskId groupId;
    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private final TaskGroup parent;
    private final List<TaskGroup> children = new CopyOnWriteArrayList<>();

    public TaskGroup() {
        this(null);
    }

    public TaskGroup(TaskGroup parent) {
        this.groupId = TaskId.generate();
        this.parent = parent;
        if (parent != null) {
            parent.addChild(this);
        }
    }

    /** Returns the unique id of this group. */
    public TaskId id() {
        return groupId;
    }

    /** Adds a task to this group. */
    public TaskGroup add(Task task) {
        Objects.requireNonNull(task, "task");
        tasks.add(task);
        return this;
    }

    /** Adds multiple tasks to this group. */
    public TaskGroup addAll(Collection<? extends Task> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        this.tasks.addAll(tasks);
        return this;
    }

    /** Returns the tasks in this group. */
    public List<Task> tasks() {
        return List.copyOf(tasks);
    }

    /** Cancels all tasks in this group and all child groups. */
    public void cancelAll() {
        tasks.forEach(Task::cancel);
        children.forEach(TaskGroup::cancelAll);
    }

    /** Pauses all tasks in this group and all child groups. */
    public void pauseAll() {
        tasks.forEach(Task::pause);
        children.forEach(TaskGroup::pauseAll);
    }

    /** Resumes all tasks in this group and all child groups. */
    public void resumeAll() {
        tasks.forEach(Task::resume);
        children.forEach(TaskGroup::resumeAll);
    }

    /** Returns {@code true} if all tasks in this group (and children) are in a terminal state. */
    public boolean isComplete() {
        boolean allDone = tasks.stream().allMatch(t -> {
            TaskState state = t.state();
            return state != null && state.isTerminal();
        });
        if (!allDone) return false;
        return children.stream().allMatch(TaskGroup::isComplete);
    }

    /** Returns the number of tasks in this group (excluding children). */
    public int size() {
        return tasks.size();
    }

    /** Returns the parent group, or {@code null} if this is a root group. */
    public TaskGroup parent() {
        return parent;
    }

    /** Returns the child groups. */
    public List<TaskGroup> children() {
        return List.copyOf(children);
    }

    /** Returns all tasks recursively (this group + all children). */
    public List<Task> allTasksRecursive() {
        List<Task> all = new ArrayList<>(tasks);
        for (TaskGroup child : children) {
            all.addAll(child.allTasksRecursive());
        }
        return all;
    }

    private void addChild(TaskGroup child) {
        children.add(child);
    }
}
