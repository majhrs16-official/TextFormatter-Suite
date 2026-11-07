package me.majhrs16.suite.api.sync;

import java.util.List;
import java.util.Objects;

/**
 * Declarative contract for one action published by a sync backend.
 *
 * @param id           local action id, e.g. {@code message.send}
 * @param kind         authorization nature of the action
 * @param params       ordered parameter specs
 * @param result       result spec, or {@code null} when the action returns no value
 * @param idempotent   whether a retry with the same idempotency key is safe
 * @param timeoutMillis maximum execution time; must be positive
 */
public record SyncActionSpec(
        String id,
        ActionKind kind,
        List<SyncParamSpec> params,
        SyncParamSpec result,
        boolean idempotent,
        long timeoutMillis
) {

    public SyncActionSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("action id must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        params = List.copyOf(Objects.requireNonNull(params, "params"));
        long distinct = params.stream().map(SyncParamSpec::name).distinct().count();
        if (distinct != params.size()) {
            throw new IllegalArgumentException(
                    "action '" + id + "' declares a duplicate parameter name");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
    }
}
