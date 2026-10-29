package me.majhrs16.suite.fabrichost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.PlatformPorts;
import me.majhrs16.suite.api.spi.ActorDirectory;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

import org.junit.jupiter.api.Test;

class FabricPlatformPortsTest {

    @Test
    void delegatesDirectoryAndMessageDispatch() {
        Actor alice = actor("Alice");
        FakeDirectory directory = new FakeDirectory(alice);
        AtomicReference<Message> delivered = new AtomicReference<>();
        FabricPlatformPorts ports = new FabricPlatformPorts(
                mock(MinecraftServer.class), directory, delivered::set, "test");

        assertEquals("fabric", ports.info().name());
        assertEquals("test", ports.info().version());
        assertEquals(Optional.of(alice), ports.findActor("Alice"));
        assertEquals(List.of(alice), ports.onlineActors());

        Message message = Message.builder().text("hello").build();
        ports.deliver(message);
        assertEquals(message, delivered.get());
    }

    @Test
    void checksConsolePermissionsAndRejectsUnknownActors() {
        Actor console = Actor.console("CONSOLE", null);
        Actor unknownPlayer = new Actor(UUID.randomUUID(), "Alice",
                Actor.ActorKind.PLAYER, Language.AUTO, null);
        FabricPlatformPorts ports = new FabricPlatformPorts(
                mock(MinecraftServer.class), new FakeDirectory(console), message -> { }, "test");

        assertTrue(ports.hasPermission(console, "suite.admin"));
        assertTrue(ports.hasPermission(null, null));
        assertFalse(ports.hasPermission(null, "suite.admin"));
        assertFalse(ports.hasPermission(unknownPlayer, "suite.admin"));
    }

    @Test
    void queuesCommandsUntilDispatcherBoundAndRemovesOnClose() throws Exception {
        CommandDispatcher<ServerCommandSource> dispatcher = new CommandDispatcher<>();
        FabricPlatformPorts ports = new FabricPlatformPorts(
                mock(MinecraftServer.class), new FakeDirectory(actor("Alice")),
                message -> { }, "test");
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
        assertNull(dispatcher.getRoot().getChild("suite"));

        ports.bindDispatcher(dispatcher);
        assertNotNull(dispatcher.getRoot().getChild("suite"));
        assertNotNull(dispatcher.getRoot().getChild("txf"));

        ServerCommandSource source = mock(ServerCommandSource.class);
        when(source.getPlayer()).thenReturn(null);
        when(source.getName()).thenReturn("CONSOLE");
        assertEquals(1, dispatcher.execute("suite list", source));
        assertEquals(List.of("CONSOLE:list"), executed);

        registration.close();
        assertNull(dispatcher.getRoot().getChild("suite"));
        assertNull(dispatcher.getRoot().getChild("txf"));
    }

    private static Actor actor(String name) {
        return Actor.console(name, null);
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
}
