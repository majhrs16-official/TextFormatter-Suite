package me.majhrs16.suite.runtime;

import me.majhrs16.suite.api.runtime.ModuleState;

/**
 * Outcome of a hot upgrade attempt.
 *
 * <p>There is no automatic rollback of the previous version: the installation
 * commit is independent from runtime activation.</p>
 *
 * @param moduleId        module id
 * @param previousVersion version that was active before the attempt, or
 *                        {@code null} when no version was active
 * @param newVersion      version that was prepared, or {@code null} when the
 *                        new JAR could not be read
 * @param state           resulting runtime state
 * @param success         {@code true} only when the new version is active
 * @param message         human-readable explanation; {@code null} on success
 * @param cause           underlying failure; {@code null} on success
 */
public record UpgradeResult(String moduleId, String previousVersion, String newVersion,
                            ModuleState state, boolean success,
                            String message, Throwable cause) {

    public static UpgradeResult upgraded(String moduleId, String previousVersion, String newVersion) {
        return new UpgradeResult(moduleId, previousVersion, newVersion,
                ModuleState.ACTIVE, true, null, null);
    }

    public static UpgradeResult failed(String moduleId, String previousVersion, String newVersion,
                                       ModuleState state, String message, Throwable cause) {
        return new UpgradeResult(moduleId, previousVersion, newVersion,
                state, false, message, cause);
    }

    public static UpgradeResult restartRequired(String moduleId, String previousVersion,
                                                String newVersion, String message,
                                                Throwable cause) {
        return new UpgradeResult(moduleId, previousVersion, newVersion,
                ModuleState.RESTART_REQUIRED, false, message, cause);
    }

    /** @return {@code true} only when the new version really started. */
    public boolean isSuccess() {
        return success;
    }
}
