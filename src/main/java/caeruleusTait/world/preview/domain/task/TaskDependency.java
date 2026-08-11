package caeruleusTait.world.preview.domain.task;

import java.util.*;

/**
 * Declares that one task depends on another: task B should not start
 * until task A has completed.
 *
 * <p>This is the foundation for DAG (Directed Acyclic Graph) scheduling.
 */
public final class TaskDependency {

    private final TaskId predecessor;
    private final TaskId successor;

    public TaskDependency(TaskId predecessor, TaskId successor) {
        this.predecessor = Objects.requireNonNull(predecessor, "predecessor");
        this.successor = Objects.requireNonNull(successor, "successor");
        if (predecessor.equals(successor)) {
            throw new IllegalArgumentException("a task cannot depend on itself");
        }
    }

    public TaskId predecessor() {
        return predecessor;
    }

    public TaskId successor() {
        return successor;
    }

    /**
     * Builds a dependency graph from a list of dependencies and validates
     * that the graph is acyclic (no circular dependencies).
     */
    public static DependencyGraph buildGraph(List<TaskDependency> dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        Map<TaskId, Set<TaskId>> adjacency = new HashMap<>();
        Set<TaskId> allIds = new HashSet<>();
        for (TaskDependency dep : dependencies) {
            adjacency.computeIfAbsent(dep.predecessor, k -> new HashSet<>()).add(dep.successor);
            allIds.add(dep.predecessor);
            allIds.add(dep.successor);
        }
        // Detect cycles using DFS
        Set<TaskId> visited = new HashSet<>();
        Set<TaskId> inStack = new HashSet<>();
        for (TaskId id : allIds) {
            if (hasCycle(id, adjacency, visited, inStack)) {
                throw new IllegalStateException("circular dependency detected involving task " + id);
            }
        }
        return new DependencyGraph(adjacency, allIds);
    }

    private static boolean hasCycle(TaskId node, Map<TaskId, Set<TaskId>> adjacency,
                                     Set<TaskId> visited, Set<TaskId> inStack) {
        if (inStack.contains(node)) return true;
        if (visited.contains(node)) return false;
        visited.add(node);
        inStack.add(node);
        Set<TaskId> neighbors = adjacency.get(node);
        if (neighbors != null) {
            for (TaskId neighbor : neighbors) {
                if (hasCycle(neighbor, adjacency, visited, inStack)) return true;
            }
        }
        inStack.remove(node);
        return false;
    }

    // ---- Dependency Graph ----

    /**
     * An immutable dependency graph that can answer topological-order queries.
     */
    public static final class DependencyGraph {
        private final Map<TaskId, Set<TaskId>> adjacency;
        private final Set<TaskId> allIds;

        DependencyGraph(Map<TaskId, Set<TaskId>> adjacency, Set<TaskId> allIds) {
            this.adjacency = Map.copyOf(adjacency);
            this.allIds = Set.copyOf(allIds);
        }

        /** Returns all task ids in the graph. */
        public Set<TaskId> allTaskIds() {
            return allIds;
        }

        /** Returns the direct successors of a task (tasks that depend on it). */
        public Set<TaskId> successors(TaskId id) {
            return adjacency.getOrDefault(id, Set.of());
        }

        /** Returns the direct predecessors of a task (tasks it depends on). */
        public Set<TaskId> predecessors(TaskId id, Map<TaskId, Set<TaskId>> reverseAdjacency) {
            return reverseAdjacency.getOrDefault(id, Set.of());
        }

        /**
         * Returns tasks in topological order: a task appears before all tasks
         * that depend on it.
         */
        public List<TaskId> topologicalOrder() {
            Map<TaskId, Integer> inDegree = new HashMap<>();
            for (TaskId id : allIds) inDegree.put(id, 0);
            for (Set<TaskId> successors : adjacency.values()) {
                for (TaskId succ : successors) {
                    inDegree.merge(succ, 1, Integer::sum);
                }
            }
            Queue<TaskId> queue = new ArrayDeque<>();
            for (Map.Entry<TaskId, Integer> entry : inDegree.entrySet()) {
                if (entry.getValue() == 0) queue.add(entry.getKey());
            }
            List<TaskId> result = new ArrayList<>();
            while (!queue.isEmpty()) {
                TaskId current = queue.poll();
                result.add(current);
                Set<TaskId> successors = adjacency.getOrDefault(current, Set.of());
                for (TaskId succ : successors) {
                    int newDegree = inDegree.merge(succ, -1, Integer::sum);
                    if (newDegree == 0) queue.add(succ);
                }
            }
            return result;
        }
    }
}
