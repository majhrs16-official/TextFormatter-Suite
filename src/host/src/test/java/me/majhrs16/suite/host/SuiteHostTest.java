package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.iflow.RouteDecision;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.target.PolicyTarget;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: YAML config → registry → translator → router (iFlow) →
 * formatter (TextFormatter). This is the shape of the pipeline a real
 * Spigot/Fabric host will run on every event.
 */
class SuiteHostTest {

    @TempDir
    Path dir;

    private static final PlainTextComponentSerializer PLAIN =
        PlainTextComponentSerializer.plainText();

    private static final Actor STEVE = Actor.unknown("Steve").withLanguage(Language.EN);
    private static final Actor ALEX = Actor.unknown("Alex").withLanguage(Language.ES);

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
        Files.createDirectories(dir.resolve("config/textformatter/channels"));
        Files.createDirectories(dir.resolve("chattranslator/translators"));
        Files.writeString(dir.resolve("config.yml"), """
            general:
              language: en
            """);
        Files.writeString(dir.resolve("config/textformatter/channels/chat.yml"), """
            name: chat
            messages:
              - '<gray>%player_name%: <tr>%content%</tr></gray>'
            """);
        Files.writeString(dir.resolve("chattranslator/config.yml"), """
            default-backend: "fake"
            context-override-enabled: true
            """);
        Files.writeString(dir.resolve("chattranslator/translators/fake.yml"), """
            provider: "fake"
            active: true
            """);
    }

    @Test
    void routesAndRendersEndToEnd() throws Exception {
        writeDefaultLayout();
        SuiteHost host = SuiteHost.bootstrap(dir, PermissionChecker.ALLOW_ALL,
            null, quietLogger());

        Message message = Message.builder()
            .sender(STEVE)
            .direction(Direction.others())
            .text("hola")
            .channel("chat")
            .build();

        RoutingResult result = host.deliver(message, ALEX);

        assertTrue(result.delivered());
        assertEquals("Steve: es: hola", PLAIN.serialize(result.rendered()));
    }

    @Test
    void dropRuleSilencesDelivery() throws Exception {
        writeDefaultLayout();
        SuiteHost host = SuiteHost.bootstrap(dir, PermissionChecker.ALLOW_ALL,
            null, quietLogger());
        PolicyPrograms.setDecision(host, HostServiceRegistry.DECISION_DROP);

        RoutingResult result = host.deliver(
            Message.builder().sender(STEVE).direction(Direction.others())
                .text("hola").channel("chat").build(),
            ALEX);

        assertFalse(result.delivered());
        assertTrue(result.silenced());
        assertEquals(PolicyTarget.DROP, result.decision().target());
    }

    @Test
    void rejectSetsConnectionLostMarkerDecision() throws Exception {
        writeDefaultLayout();
        SuiteHost host = SuiteHost.bootstrap(dir, PermissionChecker.ALLOW_ALL,
            null, quietLogger());
        PolicyPrograms.setDecision(host, HostServiceRegistry.DECISION_REJECT);

        RoutingResult result = host.deliver(
            Message.builder().sender(STEVE).direction(Direction.others())
                .text("hola").channel("chat").build(),
            ALEX);

        RouteDecision decision = result.decision();
        assertTrue(decision.rejected());
        assertTrue(decision.target().isConnectionLostMarker("x" + PolicyTarget.CONNECTION_LOST_MARKER + "y"));
    }

    @Test
    void redirectGoesToConsole() throws Exception {
        writeDefaultLayout();
        SuiteHost host = SuiteHost.bootstrap(dir, PermissionChecker.ALLOW_ALL,
            null, quietLogger());
        PolicyPrograms.setDecision(host, HostServiceRegistry.DECISION_REDIRECT);

        RoutingResult result = host.deliver(
            Message.builder().sender(STEVE).direction(Direction.others())
                .text("hola").channel("chat").build(),
            ALEX);

        assertTrue(result.redirect());
        assertEquals(PolicyTarget.REDIRECT, result.decision().target());
    }

    @Test
    void quickLookEchoDoesNotRecheckSendPermission() throws Exception {
        Files.createDirectories(dir.resolve("config/textformatter/channels"));
        Files.writeString(dir.resolve("config/textformatter/channels/chat.yml"), """
            name: chat
            send-permission: cht.chat.send
            messages:
              - '<gray><tr>%content%</tr></gray>'
            """);
        PermissionChecker onlySenderCanSend = (actor, permission) -> !permission.equals("cht.chat.send");
        SuiteHost host = SuiteHost.bootstrap(dir, onlySenderCanSend,
            null, quietLogger());

        RoutingResult echo = host.deliver(
            Message.builder().sender(STEVE).direction(Direction.initiator())
                .text("hola").channel("chat").build(),
            STEVE);

        assertTrue(echo.delivered(), "sender sees their own echo without send permission");
    }
}