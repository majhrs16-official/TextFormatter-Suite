package me.majhrs16.suite.api.sync;

/**
 * Outcome of one sync action invocation.
 *
 * <p>Every failure is explicit. There is no silent skip and no fabricated
 * success: an action the backend does not publish returns {@link Unsupported},
 * a denied caller returns {@link Denied}, and a backend crash returns
 * {@link HostFailure}.</p>
 */
public sealed interface SyncResult {

    /** Action completed. {@code value} may be {@code null}. */
    record Ok(Object value) implements SyncResult {
    }

    /** The principal is not authorized for this action. */
    record Denied(String reason) implements SyncResult {
        public Denied {
            reason = reason == null || reason.isBlank() ? "denied" : reason;
        }
    }

    /** The backend does not publish this action. */
    record Unsupported(String reason) implements SyncResult {
        public Unsupported {
            reason = reason == null || reason.isBlank() ? "unsupported" : reason;
        }
    }

    /** The arguments do not match the published parameter specs. */
    record InvalidArgument(String reason) implements SyncResult {
        public InvalidArgument {
            reason = reason == null || reason.isBlank() ? "invalid argument" : reason;
        }
    }

    /** The referenced resource does not exist. */
    record NotFound(String reason) implements SyncResult {
        public NotFound {
            reason = reason == null || reason.isBlank() ? "not found" : reason;
        }
    }

    /** The action conflicts with the current state. */
    record Conflict(String reason) implements SyncResult {
        public Conflict {
            reason = reason == null || reason.isBlank() ? "conflict" : reason;
        }
    }

    /** A quota or rate limit was exhausted. */
    record RateLimited(long retryAfterMillis) implements SyncResult {
        public RateLimited {
            if (retryAfterMillis < 0) {
                throw new IllegalArgumentException("retryAfterMillis must not be negative");
            }
        }
    }

    /** The action exceeded its declared timeout. */
    record Timeout(String reason) implements SyncResult {
        public Timeout {
            reason = reason == null || reason.isBlank() ? "timeout" : reason;
        }
    }

    /** The backend failed while executing the action. */
    record HostFailure(String reason) implements SyncResult {
        public HostFailure {
            reason = reason == null || reason.isBlank() ? "host failure" : reason;
        }
    }

    /** @return whether this result represents a successful action */
    default boolean isOk() {
        return this instanceof Ok;
    }
}
