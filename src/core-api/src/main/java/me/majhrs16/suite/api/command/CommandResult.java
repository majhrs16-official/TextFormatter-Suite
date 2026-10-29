package me.majhrs16.suite.api.command;

import java.util.Objects;

/**
 * Outcome of dispatching a command.
 *
 * <p>{@link #message()} is the text the platform adapter should send when the
 * command was not handled. Successful handlers reply through their
 * {@link CommandContext} and return {@link Status#HANDLED} with no message.</p>
 *
 * @param status  dispatch outcome
 * @param message optional feedback for non-handled outcomes
 */
public record CommandResult(Status status, String message) {

    public CommandResult {
        Objects.requireNonNull(status, "status");
    }

    /** @return {@code true} when a handler ran successfully. */
    public boolean handled() {
        return status == Status.HANDLED;
    }

    public static CommandResult success() {
        return new CommandResult(Status.HANDLED, null);
    }

    public static CommandResult notFound(String message) {
        return new CommandResult(Status.NOT_FOUND, message);
    }

    public static CommandResult denied(String message) {
        return new CommandResult(Status.DENIED, message);
    }

    public static CommandResult usage(String message) {
        return new CommandResult(Status.USAGE, message);
    }

    public static CommandResult error(String message) {
        return new CommandResult(Status.ERROR, message);
    }

    /** Dispatch outcome. */
    public enum Status {
        HANDLED,
        NOT_FOUND,
        DENIED,
        USAGE,
        ERROR
    }
}
