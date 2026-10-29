package me.majhrs16.suite.extension;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionContextTest {

    @TempDir
    Path tempDir;

    private TranslationService translation;

    @AfterEach
    void tearDown() {
        if (translation != null) {
            translation.close();
        }
    }

    @Test
    void dispatchesMessagesThroughTheNeutralPort() {
        List<Message> dispatched = new ArrayList<>();
        ExtensionContext context = context(message -> dispatched.add(message));
        Message message = chat("hello");

        context.dispatchMessage(message);

        assertEquals(List.of(message), dispatched);
    }

    @Test
    void checksPermissionsThroughTheNeutralPort() {
        PermissionChecker permissions = (actor, permission) ->
            "staff.chat".equals(permission) && "Alice".equals(actor.name());
        ExtensionContext context = context(message -> { }, permissions);

        Actor alice = actor("Alice");
        Actor bob = actor("Bob");

        assertTrue(context.hasPermission(alice, "staff.chat"));
        assertFalse(context.hasPermission(bob, "staff.chat"));
    }

    @Test
    void findsPlayersByUuidAndName() {
        Actor alice = actor("Alice");
        ActorDirectory actors = new FakeActors(alice);
        ExtensionContext context = context(message -> { }, PermissionChecker.ALLOW_ALL, actors);

        assertEquals(Optional.of(alice), context.findPlayer(alice.uuid().toString()));
        assertEquals(Optional.of(alice), context.findPlayer("Alice"));
        assertTrue(context.findPlayer("missing").isEmpty());
    }

    @Test
    void createsAnIsolatedDataDirectory() throws Exception {
        ExtensionContext context = context(message -> { });

        Path directory = context.dataDirectory();

        assertTrue(Files.isDirectory(directory));
        assertEquals(tempDir.resolve("example"), directory);
        assertTrue(directory.startsWith(tempDir));
    }

    private ExtensionContext context(MessageDispatch dispatch) {
        return context(dispatch, PermissionChecker.ALLOW_ALL);
    }

    private ExtensionContext context(MessageDispatch dispatch, PermissionChecker permissions) {
        return context(dispatch, permissions, new FakeActors());
    }

    private ExtensionContext context(MessageDispatch dispatch, PermissionChecker permissions,
                                     ActorDirectory actors) {
        translation = new TranslationService(null);
        return new ExtensionContext(
            dispatch,
            permissions,
            actors,
            new NoopLogger(),
            translation,
            new FakeLanguages(),
            ChannelRegistry.builder().build(),
            tempDir,
            "example"
        );
    }

    private static Actor actor(String name) {
        return new Actor(UUID.randomUUID(), name, Actor.ActorKind.PLAYER, Language.EN, null);
    }

    private static Message chat(String text) {
        return Message.builder()
            .type(MessageType.CHAT)
            .sender(actor("Alice"))
            .direction(Direction.others())
            .channel("chat.global")
            .text(text)
            .build();
    }

    private static final class FakeActors implements ActorDirectory {

        private final List<Actor> players = new ArrayList<>();

        FakeActors(Actor... players) {
            this.players.addAll(List.of(players));
        }

        @Override
        public List<Actor> onlinePlayers() {
            return List.copyOf(players);
        }

        @Override
        public Optional<Actor> byUuid(UUID uuid) {
            return players.stream().filter(actor -> actor.uuid().equals(uuid)).findFirst();
        }

        @Override
        public Optional<Actor> byName(String name) {
            return players.stream().filter(actor -> actor.name().equals(name)).findFirst();
        }

        @Override
        public Actor console() {
            return new Actor(UUID.randomUUID(), "CONSOLE",
                Actor.ActorKind.CONSOLE, Language.EN, null);
        }
    }

    private static final class FakeLanguages implements UserLanguageStore {

        private final Map<UUID, String> values = new java.util.HashMap<>();

        @Override
        public Optional<String> languageOf(UUID uuid) {
            return Optional.ofNullable(values.get(uuid));
        }

        @Override
        public void save(UUID uuid, String value) {
            values.put(uuid, value);
        }
    }

    private static final class NoopLogger implements PluginLogger {
        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable throwable) { }
        @Override public void debug(String message, Object... args) { }
    }
}
