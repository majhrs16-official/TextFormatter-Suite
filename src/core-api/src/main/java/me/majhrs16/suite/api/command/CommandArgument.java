package me.majhrs16.suite.api.command;

import java.util.List;
import java.util.Objects;

/**
 * Declarative description of one positional command argument.
 *
 * <p>The runtime uses the list of arguments for help and tab completion. It
 * does not impose a parser: handlers remain responsible for converting and
 * validating their own values.</p>
 *
 * @param name        argument name shown in help
 * @param required    whether the argument is mandatory
 * @param description short human-readable description
 * @param suggestions static completion candidates
 */
public record CommandArgument(String name, boolean required, String description,
                              List<String> suggestions) {

    private static final String NAME = "[A-Za-z0-9][A-Za-z0-9_-]*";

    public CommandArgument {
        Objects.requireNonNull(name, "name");
        if (!name.matches(NAME)) {
            throw new IllegalArgumentException("argument name is invalid: " + name);
        }
        description = description == null ? "" : description;
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /** @return a required argument without suggestions. */
    public static CommandArgument required(String name) {
        return new CommandArgument(name, true, "", List.of());
    }

    /** @return an optional argument without suggestions. */
    public static CommandArgument optional(String name) {
        return new CommandArgument(name, false, "", List.of());
    }

    /** @return a copy with a description. */
    public CommandArgument describe(String description) {
        return new CommandArgument(name, required, description, suggestions);
    }

    /** @return a copy with static suggestions. */
    public CommandArgument suggest(String... values) {
        return new CommandArgument(name, required, description, List.of(values));
    }
}
