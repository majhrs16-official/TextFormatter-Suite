package me.majhrs16.suite.fabrichost;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.PlatformPorts;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.MessageDispatch;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric implementation of {@link PlatformPorts}.
 *
 * <p>Fabric does not expose a mutable command registry after startup. The
 * bootstrap therefore binds the Brigadier dispatcher from
 * {@code CommandRegistrationCallback} via {@link #bindDispatcher}. Commands
 * registered before that point are queued; commands registered afterwards are
 * added immediately. Closing a registration removes its root nodes from the
 * dispatcher.</p>
 *
 * <p>Fabric has no per-node permission service in the base API. Permission
 * checks use the same operator-level rule as the rest of the Fabric host
 * (level 2); a future permission provider can replace that rule without
 * changing the neutral command tree.</p>
 */
public final class FabricPlatformPorts implements PlatformPorts {

    private static final Logger LOGGER = LoggerFactory.getLogger("TextFormatterSuite");

    private final MinecraftServer server;
    private final ActorDirectory directory;
    private final MessageDispatch dispatch;
    private final String platformVersion;
    private final AtomicReference<CommandDispatcher<ServerCommandSource>> dispatcher = new AtomicReference<>();
    private final List<FabricRegistration> pending = new CopyOnWriteArrayList<>();

    /**
     * @param server          running server, used to schedule feedback
     * @param directory       neutral online-actor view
     * @param dispatch        neutral message entry point
     * @param platformVersion platform version reported to modules
     */
    public FabricPlatformPorts(MinecraftServer server,
                               ActorDirectory directory,
                               MessageDispatch dispatch,
                               String platformVersion) {
        this.server = Objects.requireNonNull(server, "server");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.platformVersion = Objects.requireNonNull(platformVersion, "platformVersion");
    }

    /**
     * Binds the Brigadier dispatcher created during server startup and flushes
     * commands registered before the bind.
     *
     * @param commandDispatcher live server dispatcher
     */
    public void bindDispatcher(CommandDispatcher<ServerCommandSource> commandDispatcher) {
        Objects.requireNonNull(commandDispatcher, "commandDispatcher");
        if (!dispatcher.compareAndSet(null, commandDispatcher)) {
            if (dispatcher.get() != commandDispatcher) {
                throw new IllegalStateException("Fabric command dispatcher already bound to another instance");
            }
            return;
        }
        for (FabricRegistration registration : pending) {
            if (!registration.closed.get()) {
                register(commandDispatcher, registration);
            }
        }
        pending.clear();
    }

    @Override
    public PlatformInfo info() {
        return PlatformInfo.current("fabric", platformVersion);
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
            LOGGER.info(message);
            return;
        }
        ServerPlayerEntity player = recipient.handle();
        if (player != null) {
            server.execute(() -> player.sendMessage(Text.literal(message)));
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
        ServerPlayerEntity player = actor.handle();
        return player != null && player.hasPermissionLevel(2);
    }

    @Override
    public Registration registerCommand(Command command) {
        Objects.requireNonNull(command, "command");
        FabricRegistration registration = new FabricRegistration(command);
        CommandDispatcher<ServerCommandSource> current = dispatcher.get();
        if (current == null) {
            pending.add(registration);
        } else {
            register(current, registration);
        }
        return registration;
    }

    private void register(CommandDispatcher<ServerCommandSource> commandDispatcher,
                          FabricRegistration registration) {
        PlatformPorts.Command command = registration.command;
        List<String> names = new ArrayList<>();
        names.add(command.name());
        names.addAll(command.aliases());
        for (String name : names) {
            LiteralArgumentBuilder<ServerCommandSource> root = LiteralArgumentBuilder
                    .<ServerCommandSource>literal(name)
                    .requires(source -> hasPermission(actorOf(source), command.permission()))
                    .executes(context -> execute(command, context.getSource(), List.of()));
            root.then(RequiredArgumentBuilder
                    .<ServerCommandSource, String>argument("args", StringArgumentType.greedyString())
                    .suggests((context, builder) -> {
                        String raw = context.getArgument("args", String.class);
                        command.complete(actorOf(context.getSource()), split(raw))
                                .forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(context -> execute(command, context.getSource(),
                            split(context.getArgument("args", String.class)))));
            registration.nodes.add(commandDispatcher.register(root));
        }
    }

    private int execute(PlatformPorts.Command command,
                        ServerCommandSource source,
                        List<String> args) {
        command.execute(actorOf(source), args);
        return 1;
    }

    private Actor actorOf(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            if (directory instanceof FabricActorDirectory fabricDirectory) {
                return fabricDirectory.actorOf(player);
            }
            return new Actor(player.getUuid(), player.getName().getString(),
                    Actor.ActorKind.PLAYER, Language.AUTO, player);
        }
        return Actor.console(source.getName(), null);
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.trim().split("\\s+"));
    }

    private final class FabricRegistration implements Registration {

        private final PlatformPorts.Command command;
        private final List<CommandNode<ServerCommandSource>> nodes = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private FabricRegistration(PlatformPorts.Command command) {
            this.command = command;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            pending.remove(this);
            CommandDispatcher<ServerCommandSource> current = dispatcher.get();
            if (current != null) {
                current.getRoot().getChildren().removeAll(nodes);
            }
        }
    }
}
