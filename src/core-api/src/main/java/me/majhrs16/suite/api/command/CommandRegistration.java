package me.majhrs16.suite.api.command;

/**
 * Handle returned by {@link CommandRegistry#register(CommandSpec)}.
 *
 * <p>Closing it removes the registered subtree. Closing twice is a no-op.
 * The runtime also removes every registration owned by a module when that
 * module stops, so a module cannot leak commands across reloads.</p>
 */
public interface CommandRegistration extends AutoCloseable {

    /** Removes the command subtree. Idempotent. */
    @Override
    void close();
}
