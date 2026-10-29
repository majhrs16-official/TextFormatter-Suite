package me.majhrs16.suite.api.command;

/**
 * Signals that a command was invoked with missing or invalid arguments.
 */
public final class CommandUsageException extends RuntimeException {

    private final String usage;

    public CommandUsageException(String usage) {
        super(usage);
        this.usage = usage;
    }

    /** @return the full usage line to show to the actor. */
    public String usage() {
        return usage;
    }
}
