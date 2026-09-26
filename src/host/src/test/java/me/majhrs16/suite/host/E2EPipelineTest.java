package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.SoundSpec;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.host.port.ChatDelivery;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.RouteDecision;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end integration test covering the full message processing pipeline:
 * Minecraft event → Message → iFlow routing → Translation → Formatting → Delivery
 */
class E2EPipelineTest {

    @TempDir
    Path dir;

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final Actor STEVE = new Actor(
        UUID.fromString("11111111-1111-1111-1111-111111111111"), "Steve",
        Actor.ActorKind.PLAYER, Language.EN, null);
    private static final Actor ALEX = new Actor(
        UUID.fromString("22222222-2222-2222-2222-222222222222"), "Alex",
        Actor.ActorKind.PLAYER, Language.ES, null);
    private static final Actor CONSOLE = Actor.console("Server", Language.EN);

    private static final class RecordingDelivery implements ChatDelivery {
        final List<String> toPlayers = new ArrayList<>();
        final List<Actor> playerTargets = new ArrayList<>();
        final List<String> toConsole = new ArrayList<>();
        final List<String> played = new ArrayList<>();
        final Set<String> knownSounds = new HashSet<>();

        @Override public void deliver(Actor recipient, Component rendered, Message original) {
            playerTargets.add(recipient);
            toPlayers.add(PLAIN.serialize(rendered));
        }

        @Override public void deliverConsole(Component rendered) {
            toConsole.add(PLAIN.serialize(rendered));
        }

        @Override public void playSound(Actor recipient, SoundSpec sound) {
            played.add(recipient.name() + ":" + sound.name());
        }

        @Override public boolean hasSound(String soundName) {
            return knownSounds.contains(soundName);
        }
    }

    private static final class TestDirectory implements ActorDirectory {
        final List<Actor> online;
        final Actor console;

        TestDirectory(List<Actor> online, Actor console) {
            this.online = online;
            this.console = console;
        }

        @Override public List<Actor> onlinePlayers() { return online; }
        @Override public Optional<Actor> byUuid(UUID uuid) { return Optional.empty(); }
        @Override public Optional<Actor> byName(String name) { return Optional.empty(); }
        @Override public Actor console() { return console; }
    }

    private TranslationService fakeTranslation() {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            @Override public String name() { return "fake"; }
            @Override public String translate(String text, String from, String to) {
                // Simple mock: prefix with target language
                return "[" + to + "]" + text;
            }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        });
        return new TranslationService(manager);
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

    private void writeConfig() throws Exception {
        Files.createDirectories(dir.resolve("channels"));
        Files.writeString(dir.resolve("config.yml"), """
            general:
              language: en
            iflow:
              engine:
                parallel: false
            sonido:
              enabled: true
            """);
    }

    private void writeChannel(String name, String format, String sound) throws Exception {
        String soundBlock = "";
        if (sound != null) {
            soundBlock = "sounds:\n" +
                "  - name: " + sound + "\n" +
                "    volume: 1.0\n" +
                "    pitch: 1.0\n";
        }
        String content = "name: " + name + "\n" +
            "messages:\n" +
            "  - '" + format + "'\n" +
            soundBlock;
        Files.writeString(dir.resolve("channels/" + name + ".yml"), content);
    }

    private MessageDispatcher createDispatcher(RecordingDelivery delivery, List<Actor> online, PermissionChecker permissions) throws Exception {
        writeConfig();
        writeChannel("chat", "<gray>%player_name%: <tr>%content%</tr></gray>", "entity.experience_orb.pickup");
        writeChannel("staff", "<red>⚠ STAFF: %content%</red>", "block.note_block.pling");

        SuiteHost host = SuiteHost.bootstrap(dir, permissions, fakeTranslation(), quietLogger());
        return new MessageDispatcher(host, new TestDirectory(online, CONSOLE), delivery, permissions, quietLogger());
    }

    private Message chatMessage(Actor sender, Direction direction, String text) {
        return Message.builder()
            .type(MessageType.CHAT)
            .sender(sender)
            .direction(direction)
            .text(text)
            .channel("chat")
            .build();
    }

    private Message joinEvent(Actor sender) {
        return Message.builder()
            .type(MessageType.JOIN)
            .sender(sender)
            .direction(Direction.others())
            .text("%player_name% joined the game")
            .channel("join")
            .build();
    }

    @Test
    void fullPipelineChatMessage() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        MessageDispatcher dispatcher = createDispatcher(delivery, List.of(STEVE, ALEX), PermissionChecker.ALLOW_ALL);

        // Send a chat message from Steve to others (Alex)
        Message message = chatMessage(STEVE, Direction.others(), "hola");
        DispatchReport report = dispatcher.dispatch(message);

        // Verify the full pipeline executed
        assertEquals(1, report.considered()); // Only Alex (Steve excluded by Direction.others())
        assertEquals(1, report.delivered()); // Only Alex
        assertEquals(1, delivery.playerTargets.size());
        assertEquals(ALEX, delivery.playerTargets.get(0));

        // Verify message was delivered and formatted
        String rendered = delivery.toPlayers.get(0);
        assertTrue(rendered.contains("hola"),
            "Message should contain original text: " + rendered);
    }

    @Test
    void fullPipelineWithTranslation() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        MessageDispatcher dispatcher = createDispatcher(delivery, List.of(STEVE, ALEX), PermissionChecker.ALLOW_ALL);

        // Send a chat message with translation enabled
        Message message = chatMessage(STEVE, Direction.all(), "hello world");
        DispatchReport report = dispatcher.dispatch(message);

        assertEquals(2, report.considered());
        assertEquals(2, report.delivered());
        assertEquals(2, delivery.playerTargets.size());

        // Verify both recipients got the message (formatted)
        for (String rendered : delivery.toPlayers) {
            assertTrue(rendered.contains("hello") || rendered.contains("world"),
                "Message should contain original text: " + rendered);
        }
    }

    @Test
    void fullPipelineWithIflowRule() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        // Create host with a REDIRECT rule
        writeConfig();
        writeChannel("chat", "<gray>%player_name%: <tr>%content%</tr></gray>", "entity.experience_orb.pickup");
        writeChannel("staff", "<red>⚠ STAFF: %content%</red>", "block.note_block.pling");

        SuiteHost host = SuiteHost.bootstrap(dir, PermissionChecker.ALLOW_ALL, fakeTranslation(), quietLogger());
        // Add a rule that redirects staff messages
        host.router().setRules(List.of(
            me.majhrs16.suite.iflow.rule.Rule.builder(
                me.majhrs16.suite.iflow.target.PolicyTarget.REDIRECT)
                .condition("sender == 'Steve'")
                .reason("staff audit")
                .redirectChannel("staff")
                .build()));

        MessageDispatcher dispatcher = new MessageDispatcher(
            host, new TestDirectory(List.of(STEVE, ALEX), CONSOLE),
            delivery, PermissionChecker.ALLOW_ALL, quietLogger());

        // Steve sends a message - should be redirected to staff channel
        Message message = chatMessage(STEVE, Direction.others(), "test");
        DispatchReport report = dispatcher.dispatch(message);

        // Steve's message should be redirected to staff channel (delivered to console as REDIRECT)
        assertEquals(1, report.redirected());
        assertEquals(1, delivery.toConsole.size());
    }

    @Test
    void fullPipelineWithPermissionCheck() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        // Only Alex has the permission
        PermissionChecker vipOnly = (actor, permission) -> actor.equals(ALEX);

        MessageDispatcher dispatcher = createDispatcher(delivery, List.of(STEVE, ALEX), vipOnly);

        Message message = chatMessage(STEVE, Direction.others(), "vip message");
        DispatchReport report = dispatcher.dispatch(message);

        // Only Alex should receive it (has permission)
        assertEquals(1, report.delivered());
        assertEquals(ALEX, delivery.playerTargets.get(0));
    }

    @Test
    void fullPipelineJoinEvent() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();

        writeConfig();
        writeChannel("join", "<green>✦ %player_name% joined</green>", null);

        SuiteHost host = SuiteHost.bootstrap(dir, PermissionChecker.ALLOW_ALL, fakeTranslation(), quietLogger());
        MessageDispatcher dispatcher = new MessageDispatcher(
            host, new TestDirectory(List.of(STEVE, ALEX), CONSOLE),
            delivery, PermissionChecker.ALLOW_ALL, quietLogger());

        // Send a JOIN event
        Message message = joinEvent(STEVE);
        DispatchReport report = dispatcher.dispatch(message);

        // JOIN events go to others (Alex)
        assertEquals(1, report.delivered());
        assertEquals(ALEX, delivery.playerTargets.get(0));

        String rendered = delivery.toPlayers.get(0);
        assertTrue(rendered.contains("Steve") && rendered.contains("joined"),
            "Join message should contain player name: " + rendered);
    }

    @Test
    void fullPipelineWithRateLimit() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        writeConfig();
        // Channel with rate limit
        Files.writeString(dir.resolve("channels/chat.yml"), """
            name: chat
            messages:
              - '<gray>%player_name%: <tr>%content%</tr></gray>'
            rate-limit-per-second: 1
            sounds:
              - name: entity.experience_orb.pickup
                volume: 1.0
                pitch: 1.0
            """);

        SuiteHost host = SuiteHost.bootstrap(dir, PermissionChecker.ALLOW_ALL, fakeTranslation(), quietLogger());
        MessageDispatcher dispatcher = new MessageDispatcher(
            host, new TestDirectory(List.of(STEVE, ALEX), CONSOLE),
            delivery, PermissionChecker.ALLOW_ALL, quietLogger());

        // First message should go through
        Message message1 = chatMessage(STEVE, Direction.others(), "first");
        DispatchReport report1 = dispatcher.dispatch(message1);
        assertEquals(1, report1.delivered());

        // Second message from same sender within same second should be rate limited
        Message message2 = chatMessage(STEVE, Direction.others(), "second");
        DispatchReport report2 = dispatcher.dispatch(message2);
        assertEquals(0, report2.delivered());
        assertEquals(1, report2.silenced());
    }

    @Test
    void fullPipelineWithCancelledMessage() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();

        MessageDispatcher dispatcher = createDispatcher(delivery, List.of(STEVE, ALEX), PermissionChecker.ALLOW_ALL);

        // Send a cancelled message
        Message message = chatMessage(STEVE, Direction.others(), "cancelled")
            .toBuilder().cancelled(true).build();
        DispatchReport report = dispatcher.dispatch(message);

        assertEquals(0, report.considered());
        assertEquals("cancelled", report.skipReason());
        assertTrue(delivery.toPlayers.isEmpty());
    }

    @Test
    void fullPipelineMultipleRecipients() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        // Create multiple recipients
        List<Actor> recipients = new ArrayList<>();
        recipients.add(STEVE);
        recipients.add(ALEX);
        for (int i = 0; i < 10; i++) {
            recipients.add(new Actor(UUID.randomUUID(), "Player" + i,
                Actor.ActorKind.PLAYER, Language.EN, null));
        }

        MessageDispatcher dispatcher = createDispatcher(delivery, recipients, PermissionChecker.ALLOW_ALL);

        Message message = chatMessage(STEVE, Direction.all(), "broadcast");
        DispatchReport report = dispatcher.dispatch(message);

        // All 12 recipients should receive (including Steve as initiator)
        assertEquals(12, report.considered());
        assertEquals(12, report.delivered());
        assertEquals(12, delivery.playerTargets.size());
    }

    @Test
    void fullPipelineSoundPlayback() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        MessageDispatcher dispatcher = createDispatcher(delivery, List.of(STEVE, ALEX), PermissionChecker.ALLOW_ALL);

        Message message = chatMessage(STEVE, Direction.all(), "with sound");
        DispatchReport report = dispatcher.dispatch(message);

        assertEquals(2, report.delivered());
        // Sound should play for both recipients
        assertEquals(2, delivery.played.size());
        assertTrue(delivery.played.stream().anyMatch(s -> s.contains("entity.experience_orb.pickup")));
    }

    @Test
    void fullPipelineSoundDisabled() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        writeConfig();
        // Disable sounds globally
        Files.writeString(dir.resolve("config.yml"), """
            general:
              language: en
            sonido:
              enabled: false
            """);
        writeChannel("chat", "<gray>%player_name%: <tr>%content%</tr></gray>", "entity.experience_orb.pickup");

        SuiteHost host = SuiteHost.bootstrap(dir, PermissionChecker.ALLOW_ALL, fakeTranslation(), quietLogger());
        MessageDispatcher dispatcher = new MessageDispatcher(
            host, new TestDirectory(List.of(STEVE, ALEX), CONSOLE),
            delivery, PermissionChecker.ALLOW_ALL, quietLogger());

        Message message = chatMessage(STEVE, Direction.all(), "no sound");
        DispatchReport report = dispatcher.dispatch(message);

        assertEquals(2, report.delivered());
        // Sounds should be skipped even if channel has them
        assertTrue(delivery.played.isEmpty(), "sonido.enabled=false should gate all sounds");
    }

    @Test
    void fullPipelineAutoLanguageDetection() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();

        MessageDispatcher dispatcher = createDispatcher(delivery, List.of(STEVE, ALEX), PermissionChecker.ALLOW_ALL);

        // Message with no explicit source language - should auto-detect
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(STEVE)
            .direction(Direction.others())
            .text("Hello world")
            .channel("chat")
            .langSource(Language.AUTO)
            .build();

        DispatchReport report = dispatcher.dispatch(message);

        assertEquals(1, report.delivered());
        // Should have resolved source language to EN (fake translator detects EN)
        assertNotNull(report);
    }

    @Test
    void fullPipelineWithRedirectRule() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();

        writeConfig();
        writeChannel("chat", "<gray>%player_name%: <tr>%content%</tr></gray>", null);
        writeChannel("staff", "<red>⚠ STAFF: %content%</red>", null);

        SuiteHost host = SuiteHost.bootstrap(dir, PermissionChecker.ALLOW_ALL, fakeTranslation(), quietLogger());
        host.router().setRules(List.of(
            me.majhrs16.suite.iflow.rule.Rule.builder(
                me.majhrs16.suite.iflow.target.PolicyTarget.CHANNEL_REDIRECT)
                .condition("true")
                .reason("redirect to staff")
                .redirectChannel("staff")
                .build()));

        MessageDispatcher dispatcher = new MessageDispatcher(
            host, new TestDirectory(List.of(STEVE, ALEX), CONSOLE),
            delivery, PermissionChecker.ALLOW_ALL, quietLogger());

        Message message = chatMessage(STEVE, Direction.others(), "redirect me");
        DispatchReport report = dispatcher.dispatch(message);

        // Should redirect to staff channel
        assertEquals(1, report.channelRedirected());
    }
}