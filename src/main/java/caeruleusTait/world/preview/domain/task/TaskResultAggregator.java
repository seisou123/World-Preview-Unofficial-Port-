package caeruleusTait.world.preview.domain.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Collects, groups, sorts and filters {@link TaskResult} instances.
 *
 * <p>Supports aggregation by result type and by task state.
 */
public final class TaskResultAggregator<T> {

    private final List<Entry<T>> entries = new ArrayList<>();

    /** Adds a result associated with a task id. */
    public TaskResultAggregator<T> add(TaskId taskId, TaskResult<T> result) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(result, "result");
        entries.add(new Entry<>(taskId, result));
        return this;
    }

    /** Adds all results from another aggregator. */
    public TaskResultAggregator<T> addAll(TaskResultAggregator<T> other) {
        Objects.requireNonNull(other, "other");
        entries.addAll(other.entries);
        return this;
    }

    /** Returns the total number of collected results. */
    public int size() {
        return entries.size();
    }

    /** Returns all successful results. */
    public List<T> successes() {
        return entries.stream()
                .filter(e -> e.result.isSuccess())
                .map(e -> e.result.value().orElseThrow())
                .toList();
    }

    /** Returns all errors. */
    public List<Throwable> errors() {
        return entries.stream()
                .filter(e -> e.result.isError())
                .map(e -> e.result.error().orElseThrow())
                .toList();
    }

    /** Returns all partial results. */
    public List<T> partials() {
        return entries.stream()
                .filter(e -> e.result.isPartial())
                .map(e -> e.result.value().orElseThrow())
                .toList();
    }

    /** Returns the count of each result type. */
    public ResultCounts counts() {
        int success = 0, error = 0, partial = 0, skipped = 0;
        for (Entry<T> entry : entries) {
            if (entry.result.isSuccess()) success++;
            else if (entry.result.isError()) error++;
            else if (entry.result.isPartial()) partial++;
            else if (entry.result.isSkipped()) skipped++;
        }
        return new ResultCounts(success, error, partial, skipped);
    }

    /**
     * Groups results by a classifier function.
     */
    public <K> Map<K, List<Entry<T>>> groupBy(java.util.function.Function<Entry<T>, K> classifier) {
        Objects.requireNonNull(classifier, "classifier");
        return entries.stream().collect(Collectors.groupingBy(classifier));
    }

    /** Filters results by a predicate. */
    public List<Entry<T>> filter(Predicate<Entry<T>> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return entries.stream().filter(predicate).toList();
    }

    /** Returns all entries. */
    public List<Entry<T>> entries() {
        return List.copyOf(entries);
    }

    // ---- Inner types ----

    /** An entry in the aggregator: a task id paired with its result. */
    public record Entry<T>(TaskId taskId, TaskResult<T> result) {
        public Entry {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(result, "result");
        }
    }

    /** Counts of each result type. */
    public record ResultCounts(int success, int error, int partial, int skipped) {
        public int total() {
            return success + error + partial + skipped;
        }
    }
}
