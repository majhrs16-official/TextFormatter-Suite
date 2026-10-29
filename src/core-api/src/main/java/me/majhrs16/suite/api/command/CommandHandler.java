package me.majhrs16.suite.api.command;

/**
 * Executes one node of the command tree.
 *
 * <p>Handlers reply through {@link CommandContext#reply(String)}. A handler
 * may throw {@link CommandUsageException} to request a usage message; any
 * other exception is reported by the runtime as a command error.</p>
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * Executes the command.
     *
     * @param context matched path, arguments and source
     * @throws Exception when the command cannot complete
     */
    void execute(CommandContext context) throws Exception;
}
