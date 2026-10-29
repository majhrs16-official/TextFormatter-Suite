package me.majhrs16.suite.api.config;

/**
 * Raised when two modules claim the same configuration namespace.
 *
 * <p>There is no silent override. A module that wants to replace a section
 * must close the previous registration first.</p>
 */
public final class ConfigConflictException extends RuntimeException {

    public ConfigConflictException(String message) {
        super(message);
    }
}
