package me.majhrs16.suite.runtime;

import me.majhrs16.suite.api.runtime.ModuleState;

/**
 * Runtime effect of installing one package.
 *
 * <p>Installation and activation are separate facts. A committed package can
 * have a failed activation; {@link #success()} is independent from
 * {@link #state()} and never implies that the files were rolled back.</p>
 *
 * @param moduleId  module id
 * @param state     resulting runtime state
 * @param success   {@code true} only when the requested runtime change was applied
 * @param upgraded  {@code true} when an active version was replaced
 * @param message   human-readable explanation; {@code null} on success
 * @param cause     underlying failure; {@code null} on success
 */
public record PackageChange(String moduleId, ModuleState state, boolean success,
                            boolean upgraded, String message, Throwable cause) {

    public static PackageChange activated(String moduleId) {
        return new PackageChange(moduleId, ModuleState.ACTIVE, true,
                false, null, null);
    }

    public static PackageChange upgraded(String moduleId) {
        return new PackageChange(moduleId, ModuleState.ACTIVE, true,
                true, null, null);
    }

    public static PackageChange failed(String moduleId, ModuleState state,
                                       String message, Throwable cause) {
        return new PackageChange(moduleId, state, false,
                false, message, cause);
    }

    public static PackageChange from(ActivationResult result) {
        if (result.isSuccess()) {
            return activated(result.moduleId());
        }
        return failed(result.moduleId(), result.state(), result.message(), result.cause());
    }

    public static PackageChange from(UpgradeResult result) {
        return new PackageChange(result.moduleId(), result.state(), result.success(),
                result.success(), result.message(), result.cause());
    }

    /** @return {@code true} only when the requested change was applied. */
    public boolean isSuccess() {
        return success;
    }
}
