package me.majhrs16.suite.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import me.majhrs16.suite.api.command.CommandConflictException;
import me.majhrs16.suite.api.command.CommandContext;
import me.majhrs16.suite.api.command.CommandOption;
import me.majhrs16.suite.api.command.CommandPath;
import me.majhrs16.suite.api.command.CommandRegistration;
import me.majhrs16.suite.api.command.CommandRegistry;
import me.majhrs16.suite.api.command.CommandResult;
import me.majhrs16.suite.api.command.CommandRoot;
import me.majhrs16.suite.api.command.CommandSpec;
import me.majhrs16.suite.api.command.CommandUsageException;
import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Runtime implementation of the neutral command registry.
 *
 * <p>Every top-level registration records its owner. Closing the registration
 * or deactivating the owning module removes the whole subtree. The registry
 * consumes the root segments after the first one, which lets the platform
 * adapter register only the first literal of a multi-segment root.</p>
 */
public final class RuntimeCommandRegistry implements CommandRegistry {

    private final CommandRoot root;
    private final PluginLogger logger;
    private final Map<String, Registered> commands = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();

    public RuntimeCommandRegistry(CommandRoot root, PluginLogger logger) {
        this.root = Objects.requireNonNull(root, "root");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public CommandRoot root() {
        return root;
    }

    @Override
    public CommandRegistration register(CommandSpec spec) {
        return register("runtime", spec);
    }

    @Override
    public CommandRegistration register(CommandPath path, CommandSpec spec) {
        return register("runtime", path, spec);
    }

    /**
     * Registers a command on behalf of {@code owner}.
     *
     * @param owner module id or {@code runtime}
     * @param spec  command subtree
     * @return a handle that removes the subtree
     */
    public CommandRegistration register(String owner, CommandSpec spec) {
        return register(owner, CommandPath.of(), spec);
    }

    /**
     * Registers a subtree under an explicit parent path.
     *
     * @param owner module that owns the registration
     * @param path  parent path relative to the root
     * @param spec  subtree to register
     * @return a handle that removes the whole explicit branch
     */
    public CommandRegistration register(String owner, CommandPath path, CommandSpec spec) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(spec, "spec");
        CommandSpec rooted = spec;
        for (int i = path.segments().size() - 1; i >= 0; i--) {
            rooted = CommandSpec.builder(path.segments().get(i))
                    .child(rooted)
                    .build();
        }
        return registerTop(owner, rooted);
    }

    private CommandRegistration registerTop(String owner, CommandSpec spec) {
        checkAvailable(spec.name());
        for (String alias : spec.aliases()) {
            checkAvailable(alias);
        }

        Registered registered = new Registered(owner, spec);
        commands.put(spec.name(), registered);
        for (String alias : spec.aliases()) {
            aliases.put(alias, spec.name());
        }
        return () -> remove(owner, spec.name());
    }

    /**
     * @param owner module id
     * @return the module-scoped view of this registry
     */
    public CommandRegistry scope(String owner) {
        return new ScopedRegistry(owner);
    }

    /**
     * Removes every registration owned by {@code owner}.
     *
     * @param owner module id
     */
    public void removeOwner(String owner) {
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, Registered> entry : commands.entrySet()) {
            if (entry.getValue().owner.equals(owner)) {
                removed.add(entry.getKey());
            }
        }
        for (String name : removed) {
            commands.remove(name);
            aliases.entrySet().removeIf(entry -> entry.getValue().equals(name));
        }
    }

    @Override
    public List<CommandSpec> commands() {
        return commands.values().stream().map(Registered::spec).toList();
    }

    @Override
    public CommandResult dispatch(me.majhrs16.suite.api.command.CommandSource source,
                                  List<String> args) {
        Objects.requireNonNull(source, "source");
        List<String> tokens = stripRoot(args);
        if (tokens == null) {
            return CommandResult.notFound("Usa /" + root.display() + " help.");
        }
        if (tokens.isEmpty()) {
            return CommandResult.usage("/" + root.display() + " <comando>");
        }

        String first = tokens.get(0).toLowerCase(Locale.ROOT);
        Registered registered = findRoot(first);
        if (registered == null) {
            return CommandResult.notFound("Subcomando desconocido: " + first
                    + ". Usa /" + root.display() + " help.");
        }

        CommandSpec spec = registered.spec;
        CommandPath path = CommandPath.of(spec.name());
        int index = 1;
        while (index < tokens.size()) {
            Optional<CommandSpec> child = spec.child(tokens.get(index));
            if (child.isEmpty()) {
                break;
            }
            spec = child.get();
            path = path.child(spec.name());
            index++;
        }

        if (spec.handler().isEmpty()) {
            if (index < tokens.size()) {
                return CommandResult.notFound("Subcomando desconocido: "
                        + tokens.get(index) + ". Usa /" + root.display() + " help.");
            }
            return CommandResult.usage(usage(path, spec));
        }
        if (spec.permission() != null && !source.hasPermission(spec.permission())) {
            return CommandResult.denied("No tienes permiso para ejecutar /"
                    + path.display() + ".");
        }

        CommandContext context = new CommandContext(
                root, source, path, tokens.subList(index, tokens.size()), spec.usage());
        try {
            spec.handler().orElseThrow().execute(context);
            return CommandResult.success();
        } catch (CommandUsageException usage) {
            return CommandResult.usage(usage.usage());
        } catch (Exception failure) {
            logger.error("Command failed: /" + path.display(), failure);
            return CommandResult.error("Error: " + failure.getMessage());
        }
    }

    @Override
    public List<String> complete(me.majhrs16.suite.api.command.CommandSource source,
                                 List<String> args) {
        Objects.requireNonNull(source, "source");
        List<String> tokens = stripRoot(args);
        if (tokens == null) {
            return List.of();
        }
        if (tokens.isEmpty()) {
            return filter(suggestions(source, commands.values().stream()
                    .map(Registered::spec).toList()), "");
        }

        String first = tokens.get(0).toLowerCase(Locale.ROOT);
        if (tokens.size() == 1) {
            return filter(suggestions(source, commands.values().stream()
                    .map(Registered::spec).toList()), first);
        }
        Registered registered = findRoot(first);
        if (registered == null) {
            return List.of();
        }

        CommandSpec spec = registered.spec;
        int index = 1;
        while (index < tokens.size() - 1) {
            Optional<CommandSpec> child = spec.child(tokens.get(index));
            if (child.isEmpty()) {
                break;
            }
            spec = child.get();
            index++;
        }

        String partial = tokens.get(tokens.size() - 1);
        if (partial.startsWith("-")) {
            return filter(optionTokens(spec), partial);
        }
        if (tokens.size() >= 2) {
            String previous = tokens.get(tokens.size() - 2);
            if (previous.startsWith("--")) {
                String optionName = previous.substring(2);
                for (CommandOption option : spec.options()) {
                    if (option.name().equals(optionName) && option.requiresValue()) {
                        return filter(option.suggestions(), partial);
                    }
                }
            }
        }
        if (spec.hasChildren()) {
            return filter(suggestions(source, spec.children()), partial);
        }

        int argumentIndex = tokens.size() - index - 1;
        if (argumentIndex >= 0 && argumentIndex < spec.arguments().size()) {
            return filter(spec.arguments().get(argumentIndex).suggestions(), partial);
        }
        return List.of();
    }

    private void checkAvailable(String token) {
        if (commands.containsKey(token) || aliases.containsKey(token)) {
            throw new CommandConflictException("command is already registered: " + token);
        }
    }

    private void remove(String owner, String name) {
        Registered registered = commands.get(name);
        if (registered == null || !registered.owner.equals(owner)) {
            return;
        }
        commands.remove(name);
        aliases.entrySet().removeIf(entry -> entry.getValue().equals(name));
    }

    private Registered findRoot(String token) {
        Registered registered = commands.get(token);
        if (registered != null) {
            return registered;
        }
        String canonical = aliases.get(token);
        return canonical == null ? null : commands.get(canonical);
    }

    private List<String> stripRoot(List<String> args) {
        Objects.requireNonNull(args, "args");
        List<String> tokens = new ArrayList<>(args);
        if (matchesRoot(tokens, root.remaining())) {
            tokens.subList(0, root.remaining().size()).clear();
            return tokens;
        }
        for (List<String> alias : root.aliases()) {
            List<String> remaining = alias.subList(1, alias.size());
            if (matchesRoot(tokens, remaining)) {
                tokens.subList(0, remaining.size()).clear();
                return tokens;
            }
        }
        return root.remaining().isEmpty() ? tokens : null;
    }

    private boolean matchesRoot(List<String> tokens, List<String> remaining) {
        if (tokens.size() < remaining.size()) {
            return false;
        }
        for (int i = 0; i < remaining.size(); i++) {
            if (!tokens.get(i).equalsIgnoreCase(remaining.get(i))) {
                return false;
            }
        }
        return true;
    }

    private String usage(CommandPath path, CommandSpec spec) {
        StringBuilder usage = new StringBuilder("/").append(root.display());
        if (!path.segments().isEmpty()) {
            usage.append(' ').append(path.display());
        }
        if (!spec.usage().isBlank()) {
            usage.append(' ').append(spec.usage());
        }
        return usage.toString();
    }

    private List<String> suggestions(me.majhrs16.suite.api.command.CommandSource source,
                                     List<CommandSpec> specs) {
        Set<String> suggestions = new LinkedHashSet<>();
        for (CommandSpec spec : specs) {
            if (spec.permission() != null && !source.hasPermission(spec.permission())) {
                continue;
            }
            suggestions.add(spec.name());
            suggestions.addAll(spec.aliases());
        }
        return List.copyOf(suggestions);
    }

    private List<String> optionTokens(CommandSpec spec) {
        return spec.options().stream().map(CommandOption::flagToken).toList();
    }

    private List<String> filter(List<String> candidates, String partial) {
        String prefix = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        Set<String> matches = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return List.copyOf(matches);
    }

    private record Registered(String owner, CommandSpec spec) { }

    private final class ScopedRegistry implements CommandRegistry {

        private final String owner;

        private ScopedRegistry(String owner) {
            this.owner = owner;
        }

        @Override
        public CommandRoot root() {
            return RuntimeCommandRegistry.this.root;
        }

        @Override
        public CommandRegistration register(CommandSpec spec) {
            if (!spec.name().equals(owner)) {
                throw new IllegalArgumentException(
                        "module command must be rooted at its namespace: " + owner);
            }
            return RuntimeCommandRegistry.this.register(owner, spec);
        }

        @Override
        public CommandRegistration register(CommandPath path, CommandSpec spec) {
            if (path.segments().isEmpty()) {
                return register(spec);
            }
            return RuntimeCommandRegistry.this.register(owner, path, spec);
        }

        @Override
        public List<CommandSpec> commands() {
            return RuntimeCommandRegistry.this.commands();
        }

        @Override
        public CommandResult dispatch(me.majhrs16.suite.api.command.CommandSource source,
                                      List<String> args) {
            return RuntimeCommandRegistry.this.dispatch(source, args);
        }

        @Override
        public List<String> complete(me.majhrs16.suite.api.command.CommandSource source,
                                     List<String> args) {
            return RuntimeCommandRegistry.this.complete(source, args);
        }
    }
}
