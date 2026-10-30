package me.majhrs16.suite.inworld;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.PluginLogger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InWorldHandlerTest {

    private InWorldHandler handler;
    private Actor actor;
    private FakePlatform platform;

    @BeforeEach
    void setUp() {
        ChannelRegistry channels = ChannelRegistry.builder()
            .register(Channel.builder("sign.global")
                .signLocation("0,64,0,world")
                .build())
            .register(Channel.builder("container.chest").build())
            .register(Channel.builder("book.read").build())
            .build();
        handler = new InWorldHandler(channels, noopLogger());
        actor = new Actor(UUID.randomUUID(), "Steve",
            Actor.ActorKind.PLAYER, Language.EN, null);
        platform = new FakePlatform();
    }

    @Test
    void resolvesConfiguredSignChannel() {
        assertThat(handler.signChannel("world", 0, 64, 0))
            .contains("sign.global");
        assertThat(handler.signChannel("world", 1, 64, 0)).isEmpty();
        assertThat(handler.signChannel("other", 0, 64, 0)).isEmpty();
    }

    @Test
    void buildsMessagesForNonBlankSignLines() {
        Optional<Message> message = handler.signMessage(actor, "sign.global", "Hello");

        assertThat(message).isPresent();
        assertThat(message.orElseThrow().type()).isEqualTo(MessageType.SIGN);
        assertThat(message.orElseThrow().channel()).isEqualTo("sign.global");
        assertThat(message.orElseThrow().text()).isEqualTo("Hello");
        assertThat(message.orElseThrow().sender()).isSameAs(actor);

        assertThat(handler.signMessage(actor, "sign.global", "  ")).isEmpty();
        assertThat(handler.signMessage(actor, "sign.global", null)).isEmpty();
    }

    @Test
    void ignoresSignMessagesForUnknownChannels() {
        assertThat(handler.signMessage(actor, "sign.missing", "Hello")).isEmpty();
    }

    @Test
    void cachesSignsAndReportsThemAsSuiteSigns() {
        assertThat(handler.isSuiteSign("world", 0, 64, 0)).isTrue();
        assertThat(handler.isSuiteSign("world", 9, 64, 9)).isFalse();

        handler.cacheSign("world", 9, 64, 9, "sign.global",
            new String[]{"Line"});

        assertThat(handler.isSuiteSign("world", 9, 64, 9)).isTrue();
    }

    @Test
    void buildsContainerMessages() {
        Optional<Message> message = handler.containerMessage(
            actor, "container.chest", "world", 1, 2, 3);

        assertThat(message).isPresent();
        assertThat(message.orElseThrow().type()).isEqualTo(MessageType.CONTAINER);
        assertThat(message.orElseThrow().text())
            .isEqualTo("Chest opened at 1, 2, 3");
        assertThat(message.orElseThrow().channel()).isEqualTo("container.chest");
    }

    @Test
    void buildsBookMessagesWithAllPages() {
        Optional<Message> message = handler.bookMessage(
            actor, "book.read", "A title", List.of("Page 1", "Page 2"));

        assertThat(message).isPresent();
        assertThat(message.orElseThrow().type()).isEqualTo(MessageType.BOOK);
        assertThat(message.orElseThrow().text())
            .isEqualTo("Book read: A title\nPage 1\nPage 2");
    }

    @Test
    void delegatesWorldAndRadiusQueriesToThePlatform() {
        platform.worldActors = List.of(actor);
        platform.radiusActors = List.of(actor);

        assertThat(handler.getPlayersInWorld(platform, "world")).containsExactly(actor);
        assertThat(handler.getPlayersInRadius(platform, "world", 0, 64, 0, 10))
            .containsExactly(actor);
        assertThat(platform.lastWorld).isEqualTo("world");
        assertThat(platform.lastRadius).isEqualTo(10);
    }

    @Test
    void delegatesKnownClickActionsToThePlatform() {
        handler.handleClick(platform, actor, "open_url", "https://example.test");
        handler.handleClick(platform, actor, "run_command", "/spawn");
        handler.handleClick(platform, actor, "suggest_command", "/msg");
        handler.handleClick(platform, actor, "change_page", "2");
        handler.handleClick(platform, actor, "copy_to_clipboard", "text");

        assertThat(platform.calls).containsExactly(
            "openUrl:https://example.test",
            "runCommand:/spawn",
            "suggestCommand:/msg",
            "changePage:2",
            "copyToClipboard:text");
    }

    @Test
    void unknownClickActionIsIgnored() {
        handler.handleClick(platform, actor, "unknown", "value");

        assertThat(platform.calls).isEmpty();
    }

    @Test
    void managesGlossaryEntries() {
        handler.addGlossaryEntry("greeting", "Hello");

        assertThat(handler.getHoverText(actor, "greeting")).isEqualTo("Hello");
        assertThat(handler.getGlossaryEntry("greeting")).isEqualTo("Hello");
        assertThat(handler.getHoverText(actor, "missing")).isEmpty();

        handler.clearGlossary();
        assertThat(handler.getGlossaryEntry("greeting")).isNull();
    }

    @Test
    void managesRadiusCache() {
        UUID playerId = UUID.randomUUID();

        handler.updateRadiusCache(playerId, "world", 1, 64, 2, 16);

        assertThat(handler.getRadiusData(playerId)).hasValueSatisfying(data -> {
            assertThat(data.world()).isEqualTo("world");
            assertThat(data.x()).isEqualTo(1);
            assertThat(data.y()).isEqualTo(64);
            assertThat(data.z()).isEqualTo(2);
            assertThat(data.radius()).isEqualTo(16);
        });
        assertThat(handler.getRadiusData(UUID.randomUUID())).isEmpty();
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

    private static final class FakePlatform implements InWorldPlatform {

        private List<Actor> worldActors = List.of();
        private List<Actor> radiusActors = List.of();
        private final List<String> calls = new ArrayList<>();
        private String lastWorld;
        private double lastRadius;

        @Override
        public String format(Message message, String content) {
            return content;
        }

        @Override
        public List<Actor> playersInWorld(String world) {
            lastWorld = world;
            return worldActors;
        }

        @Override
        public List<Actor> playersInRadius(String world, double x, double y,
                                           double z, double radius) {
            lastWorld = world;
            lastRadius = radius;
            return radiusActors;
        }

        @Override public void openUrl(Actor actor, String url) {
            calls.add("openUrl:" + url);
        }

        @Override public void runCommand(Actor actor, String command) {
            calls.add("runCommand:" + command);
        }

        @Override public void suggestCommand(Actor actor, String command) {
            calls.add("suggestCommand:" + command);
        }

        @Override public void changePage(Actor actor, String page) {
            calls.add("changePage:" + page);
        }

        @Override public void copyToClipboard(Actor actor, String text) {
            calls.add("copyToClipboard:" + text);
        }
    }
}
