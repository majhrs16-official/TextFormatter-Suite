package me.majhrs16.suite.host;

import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Channel;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipientResolverTest {

    private static final Actor STEVE = new Actor(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "Steve",
            Actor.ActorKind.PLAYER, Language.EN, null);
    private static final Actor ALEX = new Actor(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "Alex",
            Actor.ActorKind.PLAYER, Language.ES, null);

    private final RecipientResolver resolver = new RecipientResolver(
            new Directory(), PermissionChecker.ALLOW_ALL, new NoopLogger());

    @Test
    void specificDirectionKeepsOrderAndRemovesDuplicates() {
        Message message = Message.builder()
                .text("hi")
                .direction(Direction.specific(Channel.CHAT, ALEX, STEVE, ALEX))
                .build();

        assertEquals(List.of(ALEX, STEVE), resolver.resolve(message));
    }

    @Test
    void othersDirectionExcludesTheSender() {
        Message message = Message.builder()
                .text("hi")
                .sender(STEVE)
                .direction(Direction.others())
                .build();

        assertEquals(List.of(ALEX), resolver.resolve(message));
    }

    @Test
    void permissionWithoutQualifierDeliversToNobody() {
        Message message = Message.builder()
                .text("hi")
                .direction(Direction.permission(null))
                .build();

        assertTrue(resolver.resolve(message).isEmpty());
    }

    @Test
    void blankWorldQualifierDeliversToNobody() {
        Message message = Message.builder()
                .text("hi")
                .direction(Direction.world(""))
                .build();

        assertTrue(resolver.resolve(message).isEmpty());
    }

    private static final class Directory implements ActorDirectory {
        @Override public List<Actor> onlinePlayers() { return List.of(STEVE, ALEX); }
        @Override public Optional<Actor> byUuid(UUID uuid) { return Optional.empty(); }
        @Override public Optional<Actor> byName(String name) { return Optional.empty(); }
        @Override public Actor console() { return Actor.console("Server", Language.EN); }
    }

    private static final class NoopLogger implements PluginLogger {
        @Override public void info(String message, Object... args) {}
        @Override public void warn(String message, Object... args) {}
        @Override public void error(String message, Object... args) {}
        @Override public void error(String message, Throwable throwable) {}
        @Override public void debug(String message, Object... args) {}
    }
}
