package me.majhrs16.suite.spigothost;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.PlatformPorts;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.MessageDispatch;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Spigot implementation of {@link PlatformPorts}.
 *
 * <p>This is the only place in the Spigot bootstrap that knows how to map a
 * platform sender to a neutral {@link Actor}, check a Bukkit permission, send
 * feedback and register the single root command. It deliberately contains no
 * feature wiring: messages are handed to the neutral {@link MessageDispatch}
 * supplied by the runtime.</p>
 *
 * <p>Command registration uses the server {@link CommandMap} because the root
 * command is dynamic; it is not declared in {@code plugin.yml}. Closing the
 * returned registration unregisters the command from the map.</p>
 */
public final class SpigotPlatformPorts implements PlatformPorts {

    private final Plugin plugin;
    private final BukkitAudiences audiences;
    private final ActorDirectory directory;
    private final MessageDispatch dispatch;
    private final CommandMap commandMap;
    private final String platformVersion;
    private final String fallbackPrefix;

    /**
     * @param plugin          owning plugin, used to hop to the main thread
     * @param audiences       Adventure audiences for feedback messages
     * @param directory       neutral online-actor view
     * @param dispatch        neutral message entry point
     * @param commandMap      Bukkit command map used to register the root
     * @param platformVersion platform version reported to modules
     * @param fallbackPrefix  command-map fallback prefix (normally the plugin
     *                        name, lower-case)
     */
    public SpigotPlatformPorts(Plugin plugin,
                               BukkitAudiences audiences,
                               ActorDirectory directory,
                               MessageDispatch dispatch,
                               CommandMap commandMap,
                               String platformVersion,
                               String fallbackPrefix) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.audiences = Objects.requireNonNull(audiences, "audiences");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.commandMap = Objects.requireNonNull(commandMap, "commandMap");
        this.platformVersion = Objects.requireNonNull(platformVersion, "platformVersion");
        this.fallbackPrefix = Objects.requireNonNull(fallbackPrefix, "fallbackPrefix");
    }

    @Override
    public PlatformInfo info() {
        return PlatformInfo.current("spigot", platformVersion);
    }

    @Override
    public void deliver(Message message) {
        dispatch.dispatch(Objects.requireNonNull(message, "message"));
    }

    @Override
    public void send(Actor recipient, String message) {
        if (recipient == null || message == null) {
            return;
        }
        if (recipient.isConsole()) {
            onMain(() -> audiences.console().sendMessage(Component.text(message)));
            return;
        }
        Player player = recipient.handle();
        if (player != null) {
            onMain(() -> audiences.player(player).sendMessage(Component.text(message)));
        }
    }

    @Override
    public Optional<Actor> findActor(String name) {
        return directory.byName(name);
    }

    @Override
    public List<Actor> onlineActors() {
        return directory.onlinePlayers();
    }

    @Override
    public boolean hasPermission(Actor actor, String permission) {
        if (permission == null) {
            return true;
        }
        if (actor == null) {
            return false;
        }
        if (actor.isConsole()) {
            return true;
        }
        Player player = actor.handle();
        return player != null && player.hasPermission(permission);
    }

    @Override
    public Registration registerCommand(Command command) {
        Objects.requireNonNull(command, "command");
        SpigotCommand bukkitCommand = new SpigotCommand(command);
        if (!commandMap.register(fallbackPrefix, bukkitCommand)) {
            throw new IllegalStateException("Could not register command /" + command.name());
        }
        return new Registration() {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    bukkitCommand.unregister(commandMap);
                    commandMap.getKnownCommands().entrySet()
                            .removeIf(entry -> entry.getValue() == bukkitCommand);
                }
            }
        };
    }

    private Actor actorOf(CommandSender sender) {
        if (sender instanceof Player player) {
            if (directory instanceof SpigotActorDirectory spigotDirectory) {
                return spigotDirectory.actorOf(player);
            }
            return new Actor(player.getUniqueId(), player.getName(),
                    Actor.ActorKind.PLAYER, Language.AUTO, player);
        }
        return Actor.console(sender.getName(), null);
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Adapter from the neutral command contract to Bukkit's command model. */
    private final class SpigotCommand extends org.bukkit.command.Command {

        private final PlatformPorts.Command delegate;

        private SpigotCommand(PlatformPorts.Command delegate) {
            super(delegate.name(), delegate.description(), "/" + delegate.name(), delegate.aliases());
            this.delegate = delegate;
            setPermission(delegate.permission());
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            delegate.execute(actorOf(sender), List.of(args));
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return delegate.complete(actorOf(sender), List.of(args));
        }
    }
}
