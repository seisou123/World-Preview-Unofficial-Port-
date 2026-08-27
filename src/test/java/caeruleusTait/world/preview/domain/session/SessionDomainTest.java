package caeruleusTait.world.preview.domain.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionDomainTest {

    @Test
    void sessionStateTransitionsAreValidated() {
        assertTrue(SessionState.CREATED.canTransitionTo(SessionState.RUNNING));
        assertTrue(SessionState.CREATED.canTransitionTo(SessionState.PAUSED));
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.PAUSED));
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.COMPLETED));
        assertTrue(SessionState.PAUSED.canTransitionTo(SessionState.RUNNING));

        assertFalse(SessionState.COMPLETED.canTransitionTo(SessionState.RUNNING));
        assertFalse(SessionState.CANCELLED.canTransitionTo(SessionState.RUNNING));

        assertThrows(SessionTransitionException.class,
                () -> SessionState.COMPLETED.requireTransitionTo(SessionState.RUNNING));
    }

    @Test
    void sessionFingerprintStableKeyIsDeterministic() {
        SessionFingerprint fp1 = SessionFingerprint.context(
                "1.21", "fabric", 1, "abc", "default", "minecraft:overworld");
        SessionFingerprint fp2 = SessionFingerprint.context(
                "1.21", "fabric", 1, "abc", "default", "minecraft:overworld");
        assertEquals(fp1.stableKey(), fp2.stableKey());
        assertEquals(fp1.contextStableKey(), fp2.contextStableKey());
    }

    @Test
    void sessionFingerprintContextMatchesIgnoresSamplingParams() {
        SessionFingerprint context = SessionFingerprint.context(
                "1.21", "fabric", 1, "abc", "default", "minecraft:overworld");
        SessionFingerprint full = SessionFingerprint.full(
                "1.21", "fabric", 1, "abc", "default", "minecraft:overworld",
                4, 1, 0, 256, true, false, false);
        assertTrue(context.contextMatches(full));
        assertEquals(context.contextStableKey(), full.contextStableKey());
        assertNotEquals(context.stableKey(), full.stableKey());
    }

    @Test
    void sessionFingerprintRejectsNulls() {
        assertThrows(NullPointerException.class, () ->
                SessionFingerprint.context(null, "fabric", 1, "abc", "default", "minecraft:overworld"));
        assertThrows(NullPointerException.class, () ->
                SessionFingerprint.context("1.21", null, 1, "abc", "default", "minecraft:overworld"));
    }

    @Test
    void sessionCheckpointRecordsProgress() {
        SessionCheckpoint cp = SessionCheckpoint.initial("s1", 100);
        assertEquals("s1", cp.sessionId());
        assertEquals(SessionState.CREATED, cp.state());
        assertEquals(0, cp.cursor());
        assertEquals(100, cp.totalCount());
        assertTrue(cp.errors().isEmpty());

        SessionCheckpoint updated = cp.withProgress(50, SessionState.RUNNING, "processing");
        assertEquals(50, updated.cursor());
        assertEquals(SessionState.RUNNING, updated.state());
        assertEquals("processing", updated.stage());

        SessionCheckpoint withErr = updated.withError("something went wrong");
        assertEquals(1, withErr.errors().size());
        assertEquals("something went wrong", withErr.errors().get(0));
    }

    @Test
    void sessionLifecycleHooksAreInvoked() {
        TestSession session = new TestSession("s1", "test", null);
        TrackingHook hook = new TrackingHook();
        session.addHook(hook);

        session.start();
        assertEquals(1, hook.startCount);

        session.pause();
        assertEquals(1, hook.pauseCount);

        session.resume();
        assertEquals(1, hook.resumeCount);

        session.close();
        assertEquals(1, hook.closeCount);
    }

    // ---- Test helpers ----

    private static class TestSession extends Session {
        TestSession(String id, String type, SessionFingerprint fp) {
            super(id, type, fp);
        }

        @Override
        public SessionCheckpoint checkpoint() {
            return SessionCheckpoint.initial(id(), 0);
        }
    }

    private static class TrackingHook implements SessionHook {
        int startCount, pauseCount, resumeCount, completeCount, cancelCount, closeCount;

        @Override public void onStart(Session s) { startCount++; }
        @Override public void onPause(Session s) { pauseCount++; }
        @Override public void onResume(Session s) { resumeCount++; }
        @Override public void onComplete(Session s) { completeCount++; }
        @Override public void onCancel(Session s) { cancelCount++; }
        @Override public void onClose(Session s) { closeCount++; }
    }
}
