package me.majhrs16.suite.api.runtime;

/**
 * Lifecycle state of a module as seen by the runtime.
 *
 * <p>The installed state and the runtime state are deliberately separate: a
 * package present in {@code library/status} is {@code INSTALLED} even if it has
 * never been activated. A failed activation must never be reported as
 * {@code ACTIVE}.</p>
 */
public enum ModuleState {

    /** Files are installed on disk; activation has not been confirmed. */
    INSTALLED,

    /** The activator started successfully and its services are registered. */
    ACTIVE,

    /** Files are installed but {@code start()} failed. */
    FAILED,

    /** Activation or deactivation left resources behind; a restart is required. */
    RESTART_REQUIRED,

    /** The package was removed from the installation. */
    REMOVED
}
