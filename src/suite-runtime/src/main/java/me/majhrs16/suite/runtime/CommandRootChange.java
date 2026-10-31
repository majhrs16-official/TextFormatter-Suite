package me.majhrs16.suite.runtime;

import java.util.Objects;

import me.majhrs16.suite.api.command.CommandRoot;

/**
 * Result of applying a new command root at runtime.
 *
 * @param status  outcome of the re-registration
 * @param message human-readable explanation, suitable for a command reply
 * @param root    root that is active after the operation
 */
public record CommandRootChange(Status status, String message, CommandRoot root) {

    public CommandRootChange {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(root, "root");
    }

    /** Outcome of a command-root reload. */
    public enum Status {

        /** The file was valid and contains the root that is already active. */
        UNCHANGED,

        /** The new root was applied and the platform command was re-registered. */
        APPLIED,

        /** The platform could not release or restore the root command. */
        RESTART_REQUIRED,

        /** The file was invalid or the new root could not be registered. */
        FAILED
    }
}
