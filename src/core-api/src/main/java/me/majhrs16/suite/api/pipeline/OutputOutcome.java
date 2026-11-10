package me.majhrs16.suite.api.pipeline;

import me.majhrs16.suite.api.sync.SyncResult;

import java.util.Objects;

/**
 * Result of one selected delivery target.
 *
 * <p>The target keeps the declared {@code sync.<backend>} form and the
 * order of the effective {@link OutputPlan}. A failed target is reported as an
 * explicit {@link SyncResult}; the aggregate never turns a partial delivery
 * into a total success.</p>
 *
 * @param target declared output target, e.g. {@code sync.discord}
 * @param result explicit outcome reported by the backend
 */
public record OutputOutcome(String target, SyncResult result) {

    public OutputOutcome {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("output target must not be blank");
        }
        Objects.requireNonNull(result, "result");
    }

    /** @return whether the target completed successfully */
    public boolean isOk() {
        return result.isOk();
    }
}
