package me.majhrs16.suite.api.discord;

import java.util.Map;

/**
 * Explicit outcome of one native {@link DiscordOperation}.
 *
 * <p>Expected platform failures are values, not exceptions, so the sink can
 * map them to the corresponding {@link me.majhrs16.suite.api.sync.SyncResult}
 * without losing the cause. A forbidden platform response is not a principal
 * denial: the sink reports it as a host failure because the bus already
 * authorized the caller.</p>
 */
public sealed interface DiscordResult {

    /**
     * Operation completed.
     *
     * @param value neutral result map; empty when the operation returns no value
     */
    record Ok(Map<String, Object> value) implements DiscordResult {
        public Ok {
            value = Map.copyOf(value == null ? Map.of() : value);
        }
    }

    /** The referenced guild, channel, message, member or role does not exist. */
    record NotFound(String reason) implements DiscordResult {
        public NotFound {
            reason = reason == null || reason.isBlank() ? "not found" : reason;
        }
    }

    /** Discord refused the bot action (missing permissions or access). */
    record Forbidden(String reason) implements DiscordResult {
        public Forbidden {
            reason = reason == null || reason.isBlank() ? "forbidden" : reason;
        }
    }

    /** The operation conflicts with the current platform state. */
    record Conflict(String reason) implements DiscordResult {
        public Conflict {
            reason = reason == null || reason.isBlank() ? "conflict" : reason;
        }
    }

    /** Discord rate-limited the operation. */
    record RateLimited(long retryAfterMillis) implements DiscordResult {
        public RateLimited {
            if (retryAfterMillis < 0) {
                throw new IllegalArgumentException("retryAfterMillis must not be negative");
            }
        }
    }

    /** The adapter could not complete the operation. */
    record Failed(String reason) implements DiscordResult {
        public Failed {
            reason = reason == null || reason.isBlank() ? "discord failure" : reason;
        }
    }
}
