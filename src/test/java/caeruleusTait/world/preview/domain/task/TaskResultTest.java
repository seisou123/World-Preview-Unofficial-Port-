package caeruleusTait.world.preview.domain.task;

import org.junit.jupiter.api.Test;

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
