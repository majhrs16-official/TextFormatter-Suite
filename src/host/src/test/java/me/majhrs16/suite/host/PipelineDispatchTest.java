package me.majhrs16.suite.host;

import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.SoundSpec;
import me.majhrs16.suite.api.pipeline.StageStatus;
import me.majhrs16.suite.api.render.ChatDelivery;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: YAML config → registry → translator → router (iFlow) →
 * formatter (TextFormatter) through the real {@link PipelineEngine}.
 */
class PipelineDispatchTest {

    @TempDir
    Path dir;

    private static final PlainTextComponentSerializer PLAIN =
        PlainTextComponentSerializer.plainText();

    private static final Actor STEVE = Actor.unknown("Steve").withLanguage(Language.EN);
    private static final Actor ALEX = Actor.unknown("Alex").withLanguage(Language.ES);
    private static final Actor CONSOLE = Actor.console("Server", Language.EN);

    private static final class RecordingDelivery implements ChatDelivery {
        final List<String> toPlayers = new ArrayList<>();
        final List<String> toConsole = new ArrayList<>();

        @Override public void deliver(Actor recipient, Component rendered, Message original) {
            toPlayers.add(PLAIN.serialize(rendered));
        }

        @Override public void deliverConsole(Component rendered) {
            toConsole.add(PLAIN.serialize(rendered));
        }

        @Override public void playSound(Actor recipient, SoundSpec sound) { }

        @Override public boolean hasSound(String soundName) { return false; }
    }

    private static final class TestDirectory implements ActorDirectory {
        private final List<Actor> online;

        private TestDirectory(List<Actor> online) {
            this.online = online;
        }

        @Override public List<Actor> onlinePlayers() { return online; }
        @Override public Optional<Actor> byUuid(UUID uuid) { return Optional.empty(); }
        @Override public Optional<Actor> byName(String name) { return Optional.empty(); }
        @Override public Actor console() { return CONSOLE; }
    }

    private TranslationService fakeTranslation() {
        TranslatorManager managers = new TranslatorManager();
        managers.add(new Translator() {
            @Override public String name() { return "fake"; }
            @Override public String translate(String text, String from, String to) {
                return to + ": " + text;
            }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        });
        return new TranslationService(managers);
    }

    private PluginLogger quietLogger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) { }
            @Override public void warn(String m, Object... a) { }
            @Override public void error(String m, Object... a) { }
            @Override public void error(String m, Throwable t) { }
            @Override public void debug(String m, Object... a) { }
        };
    }

    private void writeDefaultLayout() throws Exception {
        Files.createDirectories(dir.resolve("config/suite"));
        Files.createDirectories(dir.resolve("config/textformatter/channels"));
        Files.createDirectories(dir.resolve("config/chattranslator/translators"));
        Files.writeString(dir.resolve("config/suite/config.yml"), """
            general:
              language: en
            pipeline:
              - route
              - format
              - sync
            config-version: 1.0
            """);
        Files.writeString(dir.resolve("config/textformatter/channels/chat.yml"), """
            name: chat
            messages:
              - '<gray>%player_name%: <tr>%content%</tr></gray>'
            config-version: 1.0
            """);
        Files.writeString(dir.resolve("config/chattranslator/config.yml"), """
            default-backend: "fake"
            context-override-enabled: true
            config-version: 1.0
            """);
        Files.writeString(dir.resolve("config/chattranslator/translators/fake.yml"), """
            provider: "fake"
            active: true
            config-version: 1.0
            """);
    }

    private HostFixture.Pipeline pipeline(RecordingDelivery delivery,
                                          List<Actor> online,
                                          PermissionChecker permissions) {
        return HostFixture.bootstrap(dir, permissions, null, quietLogger(),
            delivery, new TestDirectory(online));
    }

    @Test
    void routesAndRendersEndToEnd() throws Exception {
        writeDefaultLayout();
        RecordingDelivery delivery = new RecordingDelivery();
        HostFixture.Pipeline pipeline = pipeline(delivery, List.of(ALEX),
            PermissionChecker.ALLOW_ALL);

        Message message = Message.builder()
            .sender(STEVE)
            .direction(Direction.others())
            .text("hola")
            .channel("chat")
            .build();

        PipelineOutcome outcome = pipeline.dispatch(message);

        assertEquals(1, outcome.delivered());
        assertEquals(List.of("Steve: es: hola"), delivery.toPlayers);
    }

    @Test
    void dropRuleSilencesDelivery() throws Exception {
        writeDefaultLayout();
        RecordingDelivery delivery = new RecordingDelivery();
        HostFixture.Pipeline pipeline = pipeline(delivery, List.of(ALEX),
            PermissionChecker.ALLOW_ALL);
        PolicyPrograms.setDecision(pipeline.router(), HostServiceRegistry.DECISION_DROP);

        PipelineOutcome outcome = pipeline.dispatch(Message.builder()
            .sender(STEVE).direction(Direction.specific(
                me.majhrs16.suite.api.message.Channel.CHAT, ALEX))
            .text("hola").channel("chat").build());

        assertEquals(0, outcome.delivered());
        assertEquals(1, outcome.silenced());
        assertTrue(delivery.toPlayers.isEmpty());
        assertTrue(outcome.trace().entries().stream()
            .anyMatch(entry -> "route".equals(entry.stageId())
                && entry.status() == StageStatus.DROP));
    }

    @Test
    void rejectIsRecordedInTheTrace() throws Exception {
        writeDefaultLayout();
        RecordingDelivery delivery = new RecordingDelivery();
        HostFixture.Pipeline pipeline = pipeline(delivery, List.of(ALEX),
            PermissionChecker.ALLOW_ALL);
        PolicyPrograms.setDecision(pipeline.router(), HostServiceRegistry.DECISION_REJECT);

        PipelineOutcome outcome = pipeline.dispatch(Message.builder()
            .sender(STEVE).direction(Direction.specific(
                me.majhrs16.suite.api.message.Channel.CHAT, ALEX))
            .text("hola").channel("chat").build());

        assertEquals(0, outcome.delivered());
        assertEquals(1, outcome.silenced());
        assertTrue(outcome.trace().entries().stream()
            .anyMatch(entry -> "route".equals(entry.stageId())
                && entry.status() == StageStatus.REJECT));
    }

    @Test
    void redirectGoesToConsole() throws Exception {
        writeDefaultLayout();
        RecordingDelivery delivery = new RecordingDelivery();
        HostFixture.Pipeline pipeline = pipeline(delivery, List.of(ALEX),
            PermissionChecker.ALLOW_ALL);
        PolicyPrograms.setDecision(pipeline.router(), HostServiceRegistry.DECISION_REDIRECT);

        PipelineOutcome outcome = pipeline.dispatch(Message.builder()
            .sender(STEVE).direction(Direction.specific(
                me.majhrs16.suite.api.message.Channel.CHAT, ALEX))
            .text("hola").channel("chat").build());

        assertEquals(1, outcome.redirected());
        assertEquals(1, delivery.toConsole.size());
        assertTrue(delivery.toPlayers.isEmpty());
    }

    @Test
    void quickLookEchoDoesNotRecheckSendPermission() throws Exception {
        Files.createDirectories(dir.resolve("config/textformatter/channels"));
        Files.writeString(dir.resolve("config/textformatter/channels/chat.yml"), """
            name: chat
            send-permission: cht.chat.send
            messages:
              - '<gray><tr>%content%</tr></gray>'
            config-version: 1.0
            """);
        RecordingDelivery delivery = new RecordingDelivery();
        PermissionChecker onlySenderCanSend = (actor, permission) -> !permission.equals("cht.chat.send");
        HostFixture.Pipeline pipeline = pipeline(delivery, List.of(STEVE), onlySenderCanSend);

        PipelineOutcome echo = pipeline.dispatch(
            Message.builder().sender(STEVE).direction(Direction.initiator())
                .text("hola").channel("chat").build());

        assertEquals(1, echo.delivered(), "sender sees their own echo without send permission");
    }
}
