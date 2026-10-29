package me.majhrs16.suite.api.command;

/**
 * Raised when two command registrations claim the same name or alias.
 *
 * <p>There is no silent override. A module that wants to replace a command
 * must close the previous registration first.</p>
 */
public final class CommandConflictException extends RuntimeException {

    public CommandConflictException(String message) {
        super(message);
    }
}
