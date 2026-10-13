package caeruleusTait.world.preview.domain.session;

/**
 * Thrown when an illegal state transition is attempted on a session.
 */
public class SessionTransitionException extends RuntimeException {

    private final SessionState from;
    private final SessionState to;

    public SessionTransitionException(SessionState from, SessionState to) {
        super("Illegal session state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public SessionState from() {
        return from;
    }

    public SessionState to() {
        return to;
    }
}
