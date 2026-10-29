package me.majhrs16.suite.api.command;

import java.util.List;
import java.util.Objects;

/**
 * Declarative description of one command option.
 *
 * <p>Options are metadata for help and tab completion. Handlers still receive
 * the raw token list in {@link CommandContext#args()} and decide how to parse
 * flags and values.</p>
 *
 * @param name         option name without the {@code --} prefix
 * @param description  short human-readable description
 * @param requiresValue whether the option consumes the following token
 * @param suggestions  static value suggestions when {@code requiresValue} is
 *                     {@code true}
 */
public record CommandOption(String name, String description, boolean requiresValue,
                            List<String> suggestions) {

    private static final String NAME = "[a-z0-9][a-z0-9_-]*";

    public CommandOption {
        Objects.requireNonNull(name, "name");
        if (!name.matches(NAME)) {
            throw new IllegalArgumentException("option name is invalid: " + name);
        }
        description = description == null ? "" : description;
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /** @return a boolean flag. */
    public static CommandOption flag(String name) {
        return new CommandOption(name, "", false, List.of());
    }

    /** @return an option that consumes one value. */
    public static CommandOption valued(String name) {
        return new CommandOption(name, "", true, List.of());
    }

    /** @return a copy with a description. */
    public CommandOption describe(String description) {
        return new CommandOption(name, description, requiresValue, suggestions);
    }

    /** @return a copy with value suggestions. */
    public CommandOption suggest(String... values) {
        return new CommandOption(name, description, requiresValue, List.of(values));
    }

    /** @return the token as typed, including the {@code --} prefix. */
    public String flagToken() {
        return "--" + name;
    }
}
