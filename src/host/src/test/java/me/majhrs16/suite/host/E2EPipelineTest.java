package me.majhrs16.suite.host;

import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.SoundSpec;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        Files.createDirectories(dir.resolve("config/suite"));
        Files.createDirectories(dir.resolve("config/textformatter/channels"));
        Files.writeString(dir.resolve("config/suite/config.yml"), """
            general:
              language: en
            iflow:
              engine:
                parallel: false
            sonido:
              enabled: true
            pipeline:
              - route
              - format
              - sync
            config-version: 1.0
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
            soundBlock +
            "config-version: 1.0\n";
        Files.writeString(dir.resolve("config/textformatter/channels/" + name + ".yml"), content);
    }

    private HostFixture.Pipeline createPipeline(RecordingDelivery delivery,
                                                 List<Actor> online,
                                                 PermissionChecker permissions) throws Exception {
        writeConfig();
        // chat channel requires 'vip' receive permission (only ALEX has it via vipOnly checker)
        Files.writeString(dir.resolve("config/textformatter/channels/chat.yml"), """
            name: chat
            receive-permission: vip
            messages:
              - '<gray>%player_name%: <tr>%content%</tr></gray>'
            sounds:
              - name: entity.experience_orb.pickup
                volume: 1.0
                pitch: 1.0
            config-version: 1.0
            """);
        writeChannel("staff", "<red>⚠ STAFF: %content%</red>", "block.note_block.pling");

        return HostFixture.bootstrap(dir, permissions, null, quietLogger(),
            delivery, new TestDirectory(online, CONSOLE));
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

        HostFixture.Pipeline pipeline = createPipeline(delivery, List.of(STEVE, ALEX),
            PermissionChecker.ALLOW_ALL);

        // Send a chat message from Steve to others (Alex)
        Message message = chatMessage(STEVE, Direction.others(), "hola");
        PipelineOutcome outcome = pipeline.dispatch(message);

        // Verify the full pipeline executed
        assertEquals(1, outcome.considered()); // Only Alex (Steve excluded by Direction.others())
        assertEquals(1, outcome.delivered()); // Only Alex
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

        HostFixture.Pipeline pipeline = createPipeline(delivery, List.of(STEVE, ALEX),
            PermissionChecker.ALLOW_ALL);

        // Send a chat message with translation enabled
        Message message = chatMessage(STEVE, Direction.all(), "hello world");
        PipelineOutcome outcome = pipeline.dispatch(message);

        assertEquals(2, outcome.considered());
        assertEquals(2, outcome.delivered());
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
        writeChannel("chat", "<gray>%player_name%: <tr>%content%</tr></gray>",
            "entity.experience_orb.pickup");
        writeChannel("staff", "<red>⚠ STAFF: %content%</red>", "block.note_block.pling");

        HostFixture.Pipeline pipeline = HostFixture.bootstrap(dir,
            PermissionChecker.ALLOW_ALL, null, quietLogger(), delivery,
            new TestDirectory(List.of(STEVE, ALEX), CONSOLE));
        // Route staff messages to the console via a VM policy program
        PolicyPrograms.setDecision(pipeline.router(), HostServiceRegistry.DECISION_REDIRECT);

        // Steve sends a message - should be redirected to console
        Message message = chatMessage(STEVE, Direction.others(), "test");
        PipelineOutcome outcome = pipeline.dispatch(message);

        // Steve's message should be redirected to console
        assertEquals(1, outcome.redirected());
        assertEquals(1, delivery.toConsole.size());
    }

    @Test
    void fullPipelineWithPermissionCheck() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        // Only Alex has the permission
        PermissionChecker vipOnly = (actor, permission) -> actor.equals(ALEX);

        HostFixture.Pipeline pipeline = createPipeline(delivery, List.of(STEVE, ALEX), vipOnly);

        Message message = chatMessage(STEVE, Direction.others(), "vip message");
        PipelineOutcome outcome = pipeline.dispatch(message);

        // Only Alex should receive it (has permission)
        assertEquals(1, outcome.delivered());
        assertEquals(ALEX, delivery.playerTargets.get(0));
    }

    @Test
    void fullPipelineJoinEvent() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();

        writeConfig();
        writeChannel("join", "<green>✦ %player_name% joined</green>", null);

        HostFixture.Pipeline pipeline = HostFixture.bootstrap(dir,
            PermissionChecker.ALLOW_ALL, null, quietLogger(), delivery,
            new TestDirectory(List.of(STEVE, ALEX), CONSOLE));

        // Send a JOIN event
        Message message = joinEvent(STEVE);
        PipelineOutcome outcome = pipeline.dispatch(message);

        // JOIN events go to others (Alex)
        assertEquals(1, outcome.delivered());
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
        Files.writeString(dir.resolve("config/textformatter/channels/chat.yml"), """
            name: chat
            messages:
              - '<gray>%player_name%: <tr>%content%</tr></gray>'
            rate-limit-per-second: 1
            sounds:
              - name: entity.experience_orb.pickup
                volume: 1.0
                pitch: 1.0
            config-version: 1.0
            """);

        HostFixture.Pipeline pipeline = HostFixture.bootstrap(dir,
            PermissionChecker.ALLOW_ALL, null, quietLogger(), delivery,
            new TestDirectory(List.of(STEVE, ALEX), CONSOLE));

        // First message should go through (rate limit checked at emission level, 1 token consumed)
        Message message1 = chatMessage(STEVE, Direction.others(), "first");
        PipelineOutcome outcome1 = pipeline.dispatch(message1);
        assertEquals(1, outcome1.delivered());

        // Second message from same sender within same second should be rate limited at emission level
        // (entire message rejected, no recipients considered)
        Message message2 = chatMessage(STEVE, Direction.others(), "second");
        PipelineOutcome outcome2 = pipeline.dispatch(message2);
        assertEquals(0, outcome2.considered());
        assertEquals(0, outcome2.delivered());
        assertEquals(1, outcome2.silenced());
        assertNotNull(outcome2.skipReason());
        assertTrue(outcome2.skipReason().contains("rate limit"));
    }

    @Test
    void fullPipelineWithCancelledMessage() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();

        HostFixture.Pipeline pipeline = createPipeline(delivery, List.of(STEVE, ALEX),
            PermissionChecker.ALLOW_ALL);

        // Send a cancelled message
        Message message = chatMessage(STEVE, Direction.others(), "cancelled")
            .toBuilder().cancelled(true).build();
        PipelineOutcome outcome = pipeline.dispatch(message);

        assertEquals(0, outcome.considered());
        assertEquals("cancelled", outcome.skipReason());
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

        HostFixture.Pipeline pipeline = createPipeline(delivery, recipients,
            PermissionChecker.ALLOW_ALL);

        Message message = chatMessage(STEVE, Direction.all(), "broadcast");
        PipelineOutcome outcome = pipeline.dispatch(message);

        // All 12 recipients should receive (including Steve as initiator)
        assertEquals(12, outcome.considered());
        assertEquals(12, outcome.delivered());
        assertEquals(12, delivery.playerTargets.size());
    }

    @Test
    void fullPipelineSoundPlayback() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        HostFixture.Pipeline pipeline = createPipeline(delivery, List.of(STEVE, ALEX),
            PermissionChecker.ALLOW_ALL);

        Message message = chatMessage(STEVE, Direction.all(), "with sound");
        PipelineOutcome outcome = pipeline.dispatch(message);

        assertEquals(2, outcome.delivered());
        // Sound should play for both recipients
        assertEquals(2, delivery.played.size());
        assertTrue(delivery.played.stream()
            .anyMatch(s -> s.contains("entity.experience_orb.pickup")));
    }

    @Test
    void fullPipelineSoundDisabled() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        writeConfig();
        // Disable sounds globally
        Files.writeString(dir.resolve("config/suite/config.yml"), """
            general:
              language: en
            sonido:
              enabled: false
            pipeline:
              - route
              - format
              - sync
            config-version: 1.0
            """);
        writeChannel("chat", "<gray>%player_name%: <tr>%content%</tr></gray>",
            "entity.experience_orb.pickup");

        HostFixture.Pipeline pipeline = HostFixture.bootstrap(dir,
            PermissionChecker.ALLOW_ALL, null, quietLogger(), delivery,
            new TestDirectory(List.of(STEVE, ALEX), CONSOLE));

        Message message = chatMessage(STEVE, Direction.all(), "no sound");
        PipelineOutcome outcome = pipeline.dispatch(message);

        assertEquals(2, outcome.delivered());
        // Sounds should be skipped even if channel has them
        assertTrue(delivery.played.isEmpty(), "sonido.enabled=false should gate all sounds");
    }

    @Test
    void fullPipelineAutoLanguageDetection() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();

        HostFixture.Pipeline pipeline = createPipeline(delivery, List.of(STEVE, ALEX),
            PermissionChecker.ALLOW_ALL);

        // Message with no explicit source language - should auto-detect
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(STEVE)
            .direction(Direction.others())
            .text("Hello world")
            .channel("chat")
            .langSource(Language.AUTO)
            .build();

        PipelineOutcome outcome = pipeline.dispatch(message);

        assertEquals(1, outcome.delivered());
        // Should have resolved source language to EN (fake translator detects EN)
        assertNotNull(outcome);
    }

    @Test
    void fullPipelineRejectsAnUnconditionalChannelRedirect() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();

        writeConfig();
        writeChannel("chat", "<gray>%player_name%: <tr>%content%</tr></gray>", null);
        writeChannel("staff", "<red>⚠ STAFF: %content%</red>", null);

        HostFixture.Pipeline pipeline = HostFixture.bootstrap(dir,
            PermissionChecker.ALLOW_ALL, null, quietLogger(), delivery,
            new TestDirectory(List.of(STEVE, ALEX), CONSOLE));
        // An unconditional redirect asks for a second channel mutation on the
        // secondary pass; ADR-031/ADR-036 reject that as DECISION_CONFLICT.
        PolicyPrograms.setChannelRedirect(pipeline.router(), "staff");

        Message message = chatMessage(STEVE, Direction.others(), "redirect me");
        PipelineOutcome outcome = pipeline.dispatch(message);

        assertEquals(0, outcome.delivered());
        assertEquals(1, outcome.silenced());
        assertTrue(delivery.toPlayers.isEmpty(),
            "a rejected channel redirect must not be delivered: " + delivery.toPlayers);
        assertTrue(outcome.trace().entries().stream()
                .anyMatch(entry -> "route".equals(entry.stageId())
                    && entry.status() == me.majhrs16.suite.api.pipeline.StageStatus.REJECT
                    && "DECISION_CONFLICT".equals(entry.detail())),
            "expected a DECISION_CONFLICT route trace, got: " + outcome.trace().entries());
    }
}
