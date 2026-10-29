package me.majhrs16.suite.spigothost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.PlatformPorts;
import me.majhrs16.suite.api.spi.ActorDirectory;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;

import org.bukkit.Location;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class SpigotPlatformPortsTest {

    @Test
    void delegatesDirectoryAndMessageDispatch() {
        Actor alice = actor("Alice", true);
        FakeDirectory directory = new FakeDirectory(alice);
        AtomicReference<Message> delivered = new AtomicReference<>();
        SpigotPlatformPorts ports = ports(directory, delivered::set, new FakeCommandMap());

        assertEquals("spigot", ports.info().name());
        assertEquals("test", ports.info().version());
        assertEquals(Optional.of(alice), ports.findActor("Alice"));
        assertEquals(List.of(alice), ports.onlineActors());

        Message message = Message.builder().text("hello").build();
        ports.deliver(message);
        assertEquals(message, delivered.get());
    }

    @Test
    void checksRealBukkitPermissionsAndConsoleBypass() {
        Actor alice = actor("Alice", true);
        FakeDirectory directory = new FakeDirectory(alice);
        SpigotPlatformPorts ports = ports(directory, message -> { }, new FakeCommandMap());

        assertTrue(ports.hasPermission(Actor.console("CONSOLE", null), "suite.admin"));
        assertTrue(ports.hasPermission(alice, "suite.allowed"));
        assertFalse(ports.hasPermission(alice, "suite.denied"));
        assertTrue(ports.hasPermission(null, null));
        assertFalse(ports.hasPermission(null, "suite.admin"));
    }

    @Test
    void registersExecutesCompletesAndUnregistersRootCommand() {
        FakeCommandMap commandMap = new FakeCommandMap();
        SpigotPlatformPorts ports = ports(new FakeDirectory(actor("Alice", true)),
                message -> { }, commandMap);
        List<String> executed = new ArrayList<>();
        PlatformPorts.Command command = new PlatformPorts.Command() {
            @Override
            public String name() {
                return "suite";
            }

            @Override
            public List<String> aliases() {
                return List.of("txf");
            }

            @Override
            public String description() {
                return "suite root";
            }

            @Override
            public void execute(Actor sender, List<String> args) {
                executed.add(sender.name() + ":" + String.join(" ", args));
            }

            @Override
            public List<String> complete(Actor sender, List<String> args) {
                return List.of("help", "list");
            }
        };

        PlatformPorts.Registration registration = ports.registerCommand(command);
        org.bukkit.command.Command registered = commandMap.getCommand("suite");
        assertNotNull(registered);

        CommandSender console = console();
        assertTrue(registered.execute(console, "suite", new String[] {"list"}));
        assertEquals(List.of("CONSOLE:list"), executed);
        assertEquals(List.of("help", "list"),
                registered.tabComplete(console, "suite", new String[] {"li"}));

        registration.close();
        assertNull(commandMap.getCommand("suite"));
    }

    private static SpigotPlatformPorts ports(ActorDirectory directory,
                                             me.majhrs16.suite.api.spi.MessageDispatch dispatch,
                                             CommandMap commandMap) {
        return new SpigotPlatformPorts(proxy(Plugin.class), proxy(BukkitAudiences.class),
                directory, dispatch, commandMap, "test", "suite");
    }

    private static Actor actor(String name, boolean allowed) {
        Player player = (Player) Proxy.newProxyInstance(
                SpigotPlatformPortsTest.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) {
                        return name;
                    }
                    if (method.getName().equals("getUniqueId")) {
                        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    if (method.getName().equals("hasPermission")) {
                        return allowed && "suite.allowed".equals(args[0]);
                    }
                    if (method.getName().equals("isOnline")) {
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                });
        return new Actor(player.getUniqueId(), name, Actor.ActorKind.PLAYER, Language.AUTO, player);
    }

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(
                SpigotPlatformPortsTest.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) {
                        return "CONSOLE";
                    }
                    if (method.getName().equals("hasPermission")) {
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                SpigotPlatformPortsTest.class.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }

    private static final class FakeDirectory implements ActorDirectory {

        private final Actor player;

        private FakeDirectory(Actor player) {
            this.player = player;
        }

        @Override
        public List<Actor> onlinePlayers() {
            return List.of(player);
        }

        @Override
        public Optional<Actor> byUuid(UUID uuid) {
            return player.uuid().equals(uuid) ? Optional.of(player) : Optional.empty();
        }

        @Override
        public Optional<Actor> byName(String name) {
            return player.name().equals(name) ? Optional.of(player) : Optional.empty();
        }

        @Override
        public Actor console() {
            return Actor.console("CONSOLE", null);
        }
    }

    private static final class FakeCommandMap implements CommandMap {

        private final Map<String, org.bukkit.command.Command> commands = new LinkedHashMap<>();

        @Override
        public void registerAll(String fallbackPrefix, List<org.bukkit.command.Command> commands) {
            for (org.bukkit.command.Command command : commands) {
                register(fallbackPrefix, command);
            }
        }

        @Override
        public boolean register(String fallbackPrefix, String label,
                                org.bukkit.command.Command command) {
            commands.put(label, command);
            return true;
        }

        @Override
        public boolean register(String fallbackPrefix, org.bukkit.command.Command command) {
            commands.put(command.getName(), command);
            return true;
        }

        @Override
        public boolean dispatch(CommandSender sender, String commandLine) throws CommandException {
            return false;
        }

        @Override
        public void clearCommands() {
            commands.clear();
        }

        @Override
        public org.bukkit.command.Command getCommand(String name) {
            return commands.get(name);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias) {
            return List.of();
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, Location location) {
            return List.of();
        }

        @Override
        public Map<String, org.bukkit.command.Command> getKnownCommands() {
            return commands;
        }
    }
}
