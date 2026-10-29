package me.majhrs16.suite.api.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable node of a hierarchical command tree.
 *
 * <p>A spec can have children, a handler, or both. The node name and its
 * aliases are matched case-insensitively; the canonical name is used in help
 * and in {@link CommandPath}.</p>
 */
public final class CommandSpec {

    private static final String NAME = "[a-z0-9][a-z0-9_-]*";

    private final String name;
    private final List<String> aliases;
    private final String description;
    private final String permission;
    private final String usage;
    private final List<CommandArgument> arguments;
    private final List<CommandOption> options;
    private final Map<String, CommandSpec> children;
    private final Map<String, CommandSpec> index;
    private final CommandHandler handler;

    private CommandSpec(Builder builder) {
        this.name = builder.name;
        this.aliases = List.copyOf(builder.aliases);
        this.description = builder.description;
        this.permission = builder.permission;
        this.usage = builder.usage;
        this.arguments = List.copyOf(builder.arguments);
        this.options = List.copyOf(builder.options);
        this.children = Collections.unmodifiableMap(new LinkedHashMap<>(builder.children));
        this.handler = builder.handler;

        Map<String, CommandSpec> lookup = new LinkedHashMap<>();
        for (CommandSpec child : children.values()) {
            put(lookup, child.name, child, "child name");
            for (String alias : child.aliases) {
                put(lookup, alias, child, "child alias");
            }
        }
        for (String token : tokens()) {
            if (lookup.containsKey(token)) {
                throw new IllegalArgumentException(
                        "child conflicts with parent command: " + token);
            }
        }
        this.index = Collections.unmodifiableMap(lookup);
    }

    /** Starts a new command spec. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** @return the canonical node name. */
    public String name() {
        return name;
    }

    /** @return aliases for this node. */
    public List<String> aliases() {
        return aliases;
    }

    /** @return the node description, never {@code null}. */
    public String description() {
        return description;
    }

    /** @return the required permission, or {@code null} when public. */
    public String permission() {
        return permission;
    }

    /** @return the usage suffix after the command path, never {@code null}. */
    public String usage() {
        return usage;
    }

    /** @return positional arguments in declaration order. */
    public List<CommandArgument> arguments() {
        return arguments;
    }

    /** @return options in declaration order. */
    public List<CommandOption> options() {
        return options;
    }

    /** @return child specs in declaration order. */
    public List<CommandSpec> children() {
        return List.copyOf(children.values());
    }

    /** @return the executor, or empty for a pure group. */
    public Optional<CommandHandler> handler() {
        return Optional.ofNullable(handler);
    }

    /** @return {@code true} when this node has children. */
    public boolean hasChildren() {
        return !children.isEmpty();
    }

    /** @return the canonical name and every alias. */
    public List<String> tokens() {
        List<String> tokens = new ArrayList<>(aliases.size() + 1);
        tokens.add(name);
        tokens.addAll(aliases);
        return List.copyOf(tokens);
    }

    /**
     * Resolves one direct child by name or alias.
     *
     * @param token user token
     * @return the matching child, if any
     */
    public Optional<CommandSpec> child(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(index.get(token.toLowerCase(Locale.ROOT)));
    }

    private static void put(Map<String, CommandSpec> index, String token,
                            CommandSpec spec, String kind) {
        if (index.putIfAbsent(token, spec) != null) {
            throw new IllegalArgumentException(kind + " is duplicated in command tree: " + token);
        }
    }

    /** Builder for {@link CommandSpec}. */
    public static final class Builder {

        private final String name;
        private final List<String> aliases = new ArrayList<>();
        private final List<CommandArgument> arguments = new ArrayList<>();
        private final List<CommandOption> options = new ArrayList<>();
        private final Map<String, CommandSpec> children = new LinkedHashMap<>();
        private String description = "";
        private String permission;
        private String usage = "";
        private CommandHandler handler;

        private Builder(String name) {
            this.name = normalize(name);
        }

        public Builder alias(String alias) {
            aliases.add(normalize(alias));
            return this;
        }

        public Builder aliases(String... aliases) {
            for (String alias : aliases) {
                alias(alias);
            }
            return this;
        }

        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public Builder permission(String permission) {
            this.permission = permission;
            return this;
        }

        public Builder usage(String usage) {
            this.usage = usage == null ? "" : usage;
            return this;
        }

        public Builder argument(CommandArgument argument) {
            arguments.add(Objects.requireNonNull(argument, "argument"));
            return this;
        }

        public Builder option(CommandOption option) {
            options.add(Objects.requireNonNull(option, "option"));
            return this;
        }

        public Builder child(CommandSpec child) {
            Objects.requireNonNull(child, "child");
            if (children.putIfAbsent(child.name, child) != null) {
                throw new IllegalArgumentException("duplicate child: " + child.name);
            }
            return this;
        }

        public Builder handler(CommandHandler handler) {
            this.handler = handler;
            return this;
        }

        public CommandSpec build() {
            if (children.isEmpty() && handler == null) {
                throw new IllegalArgumentException("command must have a handler or children: " + name);
            }
            if (aliases.contains(name)) {
                throw new IllegalArgumentException("alias equals command name: " + name);
            }
            if (aliases.stream().distinct().count() != aliases.size()) {
                throw new IllegalArgumentException("duplicate alias in command: " + name);
            }
            if (options.stream().map(CommandOption::name).distinct().count() != options.size()) {
                throw new IllegalArgumentException("duplicate option in command: " + name);
            }
            return new CommandSpec(this);
        }

        private static String normalize(String value) {
            Objects.requireNonNull(value, "name");
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!normalized.matches(NAME)) {
                throw new IllegalArgumentException("command name is invalid: " + value);
            }
            return normalized;
        }
    }
}
