package caeruleusTait.world.preview.domain.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskResultTest {

    @Test
    void successHasValueAndNoError() {
        TaskResult<String> result = TaskResult.success("hello");
        assertTrue(result.isSuccess());
        assertFalse(result.isError());
        assertFalse(result.isPartial());
        assertFalse(result.isSkipped());
        assertEquals("hello", result.value().orElseThrow());
        assertTrue(result.error().isEmpty());
    }

    @Test
    void errorHasCauseAndNoValue() {
        TaskResult<String> result = TaskResult.error(new RuntimeException("oops"));
        assertFalse(result.isSuccess());
        assertTrue(result.isError());
        assertEquals("oops", result.error().orElseThrow().getMessage());
        assertTrue(result.value().isEmpty());
    }

    @Test
    void partialHasValueAndNoError() {
        TaskResult<String> result = TaskResult.partial("partial data");
        assertTrue(result.isPartial());
        assertEquals("partial data", result.value().orElseThrow());
        assertTrue(result.error().isEmpty());
    }

    @Test
    void skippedHasNoValueAndNoError() {
        TaskResult<String> result = TaskResult.skipped();
        assertTrue(result.isSkipped());
        assertTrue(result.value().isEmpty());
        assertTrue(result.error().isEmpty());
    }

    @Test
    void successRejectsNullValue() {
        assertThrows(NullPointerException.class, () -> TaskResult.success(null));
    }

    @Test
    void aggregatorCountsByResultType() {
        TaskResultAggregator<String> agg = new TaskResultAggregator<>();
        agg.add(TaskId.generate(), TaskResult.success("a"));
        agg.add(TaskId.generate(), TaskResult.success("b"));
        agg.add(TaskId.generate(), TaskResult.error(new RuntimeException("e")));
        agg.add(TaskId.generate(), TaskResult.partial("p"));
        agg.add(TaskId.generate(), TaskResult.skipped());

        TaskResultAggregator.ResultCounts counts = agg.counts();
        assertEquals(2, counts.success());
        assertEquals(1, counts.error());
        assertEquals(1, counts.partial());
        assertEquals(1, counts.skipped());
        assertEquals(5, counts.total());

        assertEquals(List.of("a", "b"), agg.successes());
        assertEquals(1, agg.errors().size());
        assertEquals(List.of("p"), agg.partials());
    }

    @Test
    void taskDependencyGraphDetectsCycles() {
        TaskId a = TaskId.generate();
        TaskId b = TaskId.generate();
        TaskId c = TaskId.generate();

        List<TaskDependency> acyclic = List.of(
                new TaskDependency(a, b),
                new TaskDependency(b, c)
        );
        TaskDependency.DependencyGraph graph = TaskDependency.buildGraph(acyclic);
        List<TaskId> order = graph.topologicalOrder();
        assertEquals(3, order.size());
        assertEquals(a, order.get(0));
        assertEquals(b, order.get(1));
        assertEquals(c, order.get(2));

        List<TaskDependency> cyclic = List.of(
                new TaskDependency(a, b),
                new TaskDependency(b, c),
                new TaskDependency(c, a)
        );
        assertThrows(IllegalStateException.class, () -> TaskDependency.buildGraph(cyclic));
    }

    @Test
    void taskDependencyRejectsSelfDependency() {
        TaskId a = TaskId.generate();
        assertThrows(IllegalArgumentException.class, () -> new TaskDependency(a, a));
    }

    @Test
    void taskProgressMergeCombinesCounts() {
        TaskProgress p1 = new TaskProgress(5, 10, 100, "stage1");
        TaskProgress p2 = new TaskProgress(3, 8, 50, "stage2");

        TaskProgress merged = p1.merge(p2);
        assertEquals(8, merged.completedUnits());
        assertEquals(18, merged.totalUnits());
        assertEquals(150, merged.sampledPoints());
        assertEquals("stage1", merged.stage());
    }

    @Test
    void taskProgressPercentageIsCorrect() {
        assertEquals(0.5, new TaskProgress(5, 10, 0, "").percentage());
        assertEquals(0.0, new TaskProgress(5, 0, 0, "").percentage());
        assertEquals(1.0, new TaskProgress(10, 10, 0, "").percentage());
        assertEquals(1.0, new TaskProgress(15, 10, 0, "").percentage());
    }
}
