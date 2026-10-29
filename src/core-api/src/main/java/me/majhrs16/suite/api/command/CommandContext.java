package me.majhrs16.suite.api.command;

import java.util.List;
import java.util.Objects;

/**
 * One resolved command invocation.
 *
 * <p>{@link #path()} is relative to {@link #root()}; {@link #args()} contains
 * only the tokens left after the deepest matching node.</p>
 */
public final class CommandContext {

    private final CommandRoot root;
    private final CommandSource source;
    private final CommandPath path;
    private final List<String> args;
    private final String usageSuffix;

    public CommandContext(CommandRoot root, CommandSource source, CommandPath path,
                          List<String> args, String usageSuffix) {
        this.root = Objects.requireNonNull(root, "root");
        this.source = Objects.requireNonNull(source, "source");
        this.path = Objects.requireNonNull(path, "path");
        this.args = List.copyOf(Objects.requireNonNull(args, "args"));
        this.usageSuffix = usageSuffix == null ? "" : usageSuffix;
    }

    /** @return the configured root. */
    public CommandRoot root() {
        return root;
    }

    /** @return the actor and its permission checker. */
    public CommandSource source() {
        return source;
    }

    /** @return the matched path, relative to the root. */
    public CommandPath path() {
        return path;
    }

    /** @return the arguments after the matched path. */
    public List<String> args() {
        return args;
    }

    /**
     * @param index zero-based argument index
     * @return the argument, or {@code null} when absent
     */
    public String arg(int index) {
        return index >= 0 && index < args.size() ? args.get(index) : null;
    }

    /**
     * @param index zero-based argument index
     * @return the argument
     * @throws CommandUsageException when the argument is absent
     */
    public String requireArg(int index) {
        String value = arg(index);
        if (value == null) {
            throw new CommandUsageException(usage());
        }
        return value;
    }

    /**
     * @param minimum minimum number of arguments
     * @return the argument list
     * @throws CommandUsageException when there are fewer arguments
     */
    public List<String> requireArgs(int minimum) {
        if (args.size() < minimum) {
            throw new CommandUsageException(usage());
        }
        return args;
    }

    /** @return the full usage line, including the configured root. */
    public String usage() {
        StringBuilder usage = new StringBuilder("/").append(root.display());
        if (!path.segments().isEmpty()) {
            usage.append(' ').append(path.display());
        }
        if (!usageSuffix.isBlank()) {
            usage.append(' ').append(usageSuffix);
        }
        return usage.toString();
    }

    /** Sends one feedback line to the invoking actor. */
    public void reply(String message) {
        source.reply(message);
    }
}
