package me.majhrs16.suite.api.command;

import java.util.List;

/**
 * Neutral registry and dispatcher for the suite command tree.
 *
 * <p>Modules register their own subtrees; the runtime owns the root and the
 * built-in package commands. The platform adapter only translates one
 * invocation into {@link #dispatch(CommandSource, List)}.</p>
 */
public interface CommandRegistry {

    /** @return the configured root. */
    CommandRoot root();

    /**
     * Registers a top-level command subtree.
     *
     * <p>In a module-scoped registry the subtree must be rooted at the module
     * id. Use {@link #register(CommandPath, CommandSpec)} to declare an
     * explicit path outside that default.</p>
     *
     * @param spec subtree to register
     * @return a handle that removes it
     * @throws CommandConflictException when the name or an alias is taken
     */
    CommandRegistration register(CommandSpec spec);

    /**
     * Registers a subtree under an explicit parent path.
     *
     * <p>The path is relative to the configured root and does not include the
     * spec name. An empty path registers the spec as a top-level command. The
     * whole explicit branch is removed when the registration closes.</p>
     *
     * @param path parent path, relative to the root
     * @param spec subtree to register
     * @return a handle that removes it
     * @throws CommandConflictException when the path or an alias is taken
     */
    CommandRegistration register(CommandPath path, CommandSpec spec);

    /** @return top-level commands in registration order. */
    List<CommandSpec> commands();

    /**
     * Dispatches tokens after the platform command name.
     *
     * <p>The implementation consumes any remaining root segments, resolves
     * the deepest matching node and passes the rest as arguments.</p>
     *
     * @param source actor invoking the command
     * @param args   tokens after the first root segment
     * @return the dispatch outcome
     */
    CommandResult dispatch(CommandSource source, List<String> args);

    /**
     * Completes the last token.
     *
     * @param source actor requesting completion
     * @param args   tokens after the first root segment
     * @return matching command names or argument suggestions
     */
    List<String> complete(CommandSource source, List<String> args);
}
