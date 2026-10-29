package me.majhrs16.suite.runtime;

import java.util.List;
import java.util.Objects;

import me.majhrs16.suite.api.command.CommandRegistry;
import me.majhrs16.suite.api.command.CommandResult;
import me.majhrs16.suite.api.command.CommandRoot;
import me.majhrs16.suite.api.command.CommandSource;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.runtime.PlatformPorts;

/**
 * Platform command that delegates to the neutral command registry.
 *
 * <p>This is the only command the adapter has to register. Every module
 * command is resolved by the registry, so the host never owns a feature tree.</p>
 */
final class RootCommand implements PlatformPorts.Command {

    private final CommandRegistry commands;
    private final PlatformPorts ports;

    RootCommand(CommandRegistry commands, PlatformPorts ports) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.ports = Objects.requireNonNull(ports, "ports");
    }

    @Override
    public String name() {
        return commands.root().first();
    }

    @Override
    public List<String> aliases() {
        CommandRoot root = commands.root();
        return root.aliases().stream()
                .map(alias -> alias.get(0))
                .filter(alias -> !alias.equals(root.first()))
                .distinct()
                .toList();
    }

    @Override
    public String description() {
        return "Gestiona paquetes y módulos de TextFormatter Suite";
    }

    @Override
    public void execute(Actor sender, List<String> args) {
        CommandSource source = new PlatformCommandSource(sender, ports);
        CommandResult result = commands.dispatch(source, args);
        if (result.message() != null) {
            source.reply(result.message());
        }
    }

    @Override
    public List<String> complete(Actor sender, List<String> args) {
        return commands.complete(new PlatformCommandSource(sender, ports), args);
    }
}
