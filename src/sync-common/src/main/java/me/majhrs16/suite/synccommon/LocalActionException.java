package me.majhrs16.suite.synccommon;

/**
 * Explicit failure raised by a {@link LocalEnvironment} operation.
 *
 * <p>The kind lets the local sink map a platform failure to the matching
 * {@link me.majhrs16.suite.api.sync.SyncResult} without parsing messages.
 * Platform adapters must never swallow a failure or return a fabricated
 * success: a missing player, an unsupported operation and a broken host are
 * distinct outcomes.</p>
 */
public final class LocalActionException extends RuntimeException {

    /** Failure family, mapped one-to-one by the local sink. */
    public enum Kind {
        /** The player, objective or event does not exist right now. */
        NOT_FOUND,
        /** The platform cannot perform the operation. */
        UNSUPPORTED,
        /** The argument is invalid for the operation. */
        INVALID_ARGUMENT,
        /** The platform failed while executing an otherwise valid operation. */
        HOST_FAILURE
    }

    private final Kind kind;

    public LocalActionException(Kind kind, String message) {
        super(message);
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        this.kind = kind;
    }

    public LocalActionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        this.kind = kind;
    }

    /** @return the failure family of this exception */
    public Kind kind() {
        return kind;
    }
}
