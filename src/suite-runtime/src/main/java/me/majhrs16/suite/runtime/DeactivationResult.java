package me.majhrs16.suite.runtime;

import me.majhrs16.suite.api.runtime.ModuleState;

/**
 * Outcome of a deactivation attempt.
 *
 * @param moduleId module id
 * @param state    resulting runtime state; {@link ModuleState#INSTALLED} when
 *                 cleanup was clean, {@link ModuleState#RESTART_REQUIRED} when
 *                 resources could not be released
 * @param message  human-readable explanation; {@code null} on clean shutdown
 * @param cause    underlying failure; {@code null} on clean shutdown
 */
public record DeactivationResult(String moduleId, ModuleState state, String message, Throwable cause) {

    public static DeactivationResult deactivated(String moduleId) {
        return new DeactivationResult(moduleId, ModuleState.INSTALLED, null, null);
    }

    public static DeactivationResult restartRequired(String moduleId, String message, Throwable cause) {
        return new DeactivationResult(moduleId, ModuleState.RESTART_REQUIRED, message, cause);
    }

    /** @return {@code true} when the module was fully released. */
    public boolean isClean() {
        return state == ModuleState.INSTALLED;
    }
}
