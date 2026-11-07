package me.majhrs16.suite.api.sync;

import java.util.Map;
import java.util.Objects;

/**
 * One action invocation against a sync backend.
 *
 * <p>The canonical action name is {@code backend + "." + action}. Arguments use
 * the neutral {@link SyncValueType} vocabulary and never carry SDK objects.</p>
 *
 * @param backend        backend namespace
 * @param action         local action id
 * @param args           neutral argument map
 * @param principal      caller origin, never ambient authority
 * @param idempotencyKey stable key for safe retries; may be blank when the
 *                       action is not idempotent
 * @param reason         human-readable audit reason
 */
public record SyncCall(
        String backend,
        String action,
        Map<String, Object> args,
        Principal principal,
        String idempotencyKey,
        String reason
) {

    public SyncCall {
        if (backend == null || backend.isBlank()) {
            throw new IllegalArgumentException("backend must not be blank");
        }
        if (!backend.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "backend must be a single namespace segment without dots or spaces: '"
                            + backend + "'");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        if (action.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "action must not contain whitespace: '" + action + "'");
        }
        args = Map.copyOf(Objects.requireNonNull(args, "args"));
        Objects.requireNonNull(principal, "principal");
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        reason = reason == null ? "" : reason;
    }

    /** @return the canonical {@code <backend>.<action>} name */
    public String canonicalName() {
        return backend + "." + action;
    }
}
