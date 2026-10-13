package caeruleusTait.world.preview.domain.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskStateTest {

    @Test
    void queuedCanTransitionToPendingStartRunningCancelledAndFailed() {
        assertTrue(TaskState.QUEUED.canTransitionTo(TaskState.PENDING_START));
        assertTrue(TaskState.QUEUED.canTransitionTo(TaskState.RUNNING));
        assertTrue(TaskState.QUEUED.canTransitionTo(TaskState.CANCELLED));
        assertTrue(TaskState.QUEUED.canTransitionTo(TaskState.FAILED));
    }

    @Test
    void runningCanTransitionToPausedCompletedCancelledAndFailed() {
        assertTrue(TaskState.RUNNING.canTransitionTo(TaskState.PAUSED));
        assertTrue(TaskState.RUNNING.canTransitionTo(TaskState.COMPLETED));
        assertTrue(TaskState.RUNNING.canTransitionTo(TaskState.CANCELLED));
        assertTrue(TaskState.RUNNING.canTransitionTo(TaskState.FAILED));
    }

    @Test
    void pausedCanTransitionToRunningCancelledAndFailed() {
        assertTrue(TaskState.PAUSED.canTransitionTo(TaskState.RUNNING));
        assertTrue(TaskState.PAUSED.canTransitionTo(TaskState.CANCELLED));
        assertTrue(TaskState.PAUSED.canTransitionTo(TaskState.FAILED));
    }

    @Test
    void terminalStatesCannotTransitionToAnything() {
        for (TaskState terminal : new TaskState[]{TaskState.COMPLETED, TaskState.CANCELLED, TaskState.FAILED}) {
            for (TaskState target : TaskState.values()) {
                if (target != terminal) {
                    assertFalse(terminal.canTransitionTo(target),
                            "Terminal state " + terminal + " should not transition to " + target);
                }
            }
            assertTrue(terminal.isTerminal());
        }
    }

    @Test
    void requireTransitionToThrowsOnIllegalTransition() {
        assertThrows(TaskTransitionException.class,
                () -> TaskState.COMPLETED.requireTransitionTo(TaskState.RUNNING));
        assertThrows(TaskTransitionException.class,
                () -> TaskState.PAUSED.requireTransitionTo(TaskState.COMPLETED));
    }

    @Test
    void requireTransitionToReturnsTargetOnLegalTransition() {
        assertEquals(TaskState.RUNNING, TaskState.QUEUED.requireTransitionTo(TaskState.RUNNING));
        assertEquals(TaskState.PAUSED, TaskState.RUNNING.requireTransitionTo(TaskState.PAUSED));
    }

    @Test
    void isActiveReturnsTrueForQueuedPendingStartAndRunning() {
        assertTrue(TaskState.QUEUED.isActive());
        assertTrue(TaskState.PENDING_START.isActive());
        assertTrue(TaskState.RUNNING.isActive());
        assertFalse(TaskState.PAUSED.isActive());
        assertFalse(TaskState.COMPLETED.isActive());
    }
}
