package me.majhrs16.suite.tester;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.DispatchOutcome;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.UserLanguageStore;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TestServiceTest {

    @Test
    void fullSuiteRunsThroughTheNeutralProbe() {
        FakeProbe probe = new FakeProbe();
        TestService service = new TestServiceFactory().create(
            probe, new FakeActors(), new FakeLanguages(), noopLogger());

        List<String> lines = new ArrayList<>();
        service.runFullTestSuite(lines::add);

        assertThat(lines).anyMatch(line -> line.contains("Test Suite Complete"));
        assertThat(lines).noneMatch(line -> line.contains("FAILED TESTS"));
        assertThat(probe.dispatches.get()).isPositive();
        assertThat(service.getProbe()).isSameAs(probe);
    }

    private static final class FakeProbe implements TestProbe {

        private final ChannelRegistry channels = ChannelRegistry.builder()
            .register(Channel.builder("chat.global").build())
            .register(Channel.builder("join").build())
            .register(Channel.builder("quit").build())
            .register(Channel.builder("death").build())
            .register(Channel.builder("advancement").build())
            .build();
        private final Router router = new FakeRouter();
        private final AtomicInteger dispatches = new AtomicInteger();

        @Override public boolean ready() { return true; }
        @Override public ChannelRegistry channels() { return channels; }
        @Override public Router router() { return router; }
        @Override public int discoveredModules() { return 3; }

        @Override
        public DispatchOutcome dispatch(Message message) {
            dispatches.incrementAndGet();
            int delivered = message.direction().kind() == Direction.Kind.SPECIFIC ? 1 : 2;
            return new DispatchOutcome(delivered, 0, 0);
        }

        @Override public void format(Message message) { }
        @Override public String detectLanguage(String text) { return "en"; }
    }

    private static final class FakeRouter implements Router {
        @Override
        public RouteOutcome route(Message message, Actor recipient) {
            return RouteOutcome.unchanged(
                new RouteDecision(PolicyTarget.LOG, null, 0, recipient, message.sender()),
                message);
        }

        @Override public boolean hasPermission(Actor actor, String permission) { return true; }
        @Override public boolean checkEmissionRateLimit(Message message) { return true; }
        @Override public long nanosUntilNextRateLimitWindow(Message message) { return 0; }
    }

    private static final class FakeActors implements ActorDirectory {

        private final List<Actor> players = List.of(
            new Actor(UUID.randomUUID(), "Alice", Actor.ActorKind.PLAYER, Language.EN, null),
            new Actor(UUID.randomUUID(), "Bob", Actor.ActorKind.PLAYER, Language.EN, null),
            new Actor(UUID.randomUUID(), "Carol", Actor.ActorKind.PLAYER, Language.EN, null));
        private final Actor console = Actor.console("console", Language.EN);

        @Override public List<Actor> onlinePlayers() { return players; }

        @Override
        public Optional<Actor> byUuid(UUID uuid) {
            return players.stream().filter(player -> player.uuid().equals(uuid)).findFirst();
        }

        @Override
        public Optional<Actor> byName(String name) {
            return players.stream().filter(player -> player.name().equals(name)).findFirst();
        }

        @Override public Actor console() { return console; }
    }

    private static final class FakeLanguages implements UserLanguageStore {

        private final Map<UUID, String> values = new HashMap<>();

        @Override public Optional<String> languageOf(UUID uuid) {
            return Optional.ofNullable(values.get(uuid));
        }

        @Override public void save(UUID uuid, String value) {
            values.put(uuid, value);
        }
    }

    private static PluginLogger noopLogger() {
        return new PluginLogger() {
            @Override public void info(String message, Object... args) { }
            @Override public void warn(String message, Object... args) { }
            @Override public void error(String message, Object... args) { }
            @Override public void error(String message, Throwable throwable) { }
            @Override public void debug(String message, Object... args) { }
        };
    }
}
