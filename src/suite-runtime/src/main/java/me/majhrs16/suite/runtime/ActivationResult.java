package me.majhrs16.suite.runtime;

import me.majhrs16.suite.api.runtime.ModuleState;

/**
 * Outcome of an activation attempt.
 *
 * @param moduleId module id, or the source name when the descriptor could not
 *                 be read
 * @param state    resulting runtime state
 * @param message  human-readable explanation; {@code null} on success
 * @param cause    underlying failure; {@code null} on success
 */
public record ActivationResult(String moduleId, ModuleState state, String message, Throwable cause) {

    public static ActivationResult activated(String moduleId) {
        return new ActivationResult(moduleId, ModuleState.ACTIVE, null, null);
    }

    public static ActivationResult failed(String moduleId, String message, Throwable cause) {
        return new ActivationResult(moduleId, ModuleState.FAILED, message, cause);
    }

    /** @return {@code true} only when the module really started. */
    public boolean isSuccess() {
        return state == ModuleState.ACTIVE;
    }
}
