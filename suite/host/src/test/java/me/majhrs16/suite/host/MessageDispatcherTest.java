package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.SoundSpec;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.host.port.ChatDelivery;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.rule.Rule;
import me.majhrs16.suite.iflow.target.PolicyTarget;

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

/**
 * Dispatcher behavior over a real {@link SuiteHost}: direction expansion,
 * routing outcomes pushed into a recording {@link ChatDelivery}.
 */
class MessageDispatcherTest {

    @TempDir
    Path dir;

    private static final PlainTextComponentSerializer PLAIN =
        PlainTextComponentSerializer.plainText();

    private static final Actor STEVE = new Actor(
        UUID.fromString("11111111-1111-1111-1111-111111111111"), "Steve",
        Actor.ActorKind.PLAYER, Language.EN, null);
    private static final Actor ALEX = new Actor(
        UUID.fromString("22222222-2222-2222-2222-222222222222"), "Alex",
        Actor.ActorKind.PLAYER, Language.ES, null);
    private static final Actor CONSOLE = Actor.console("Server", Language.EN);

    /** Delivery double that records every push instead of touching a server. */
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

    private void writeLayout(boolean withSound) throws Exception {
        Files.createDirectories(dir.resolve("channels"));
        Files.writeString(dir.resolve("config.yml"), """
            general:
              language: en
            """);
        String soundBlock = withSound ? """
            sounds:
              - name: entity.experience_orb.pickup
                volume: 1.0
                pitch: 1.0
            """ : "";
        Files.writeString(dir.resolve("channels/chat.yml"), """
            name: chat
            messages:
              - '<gray>%player_name%: <tr>%content%</tr></gray>'
            """ + soundBlock);
    }

    private MessageDispatcher dispatcher(RecordingDelivery delivery,
                                         List<Actor> online,
                                         PermissionChecker permissions) {
        SuiteHost host = SuiteHost.bootstrap(dir, permissions,
            fakeTranslation(), quietLogger());
        return new MessageDispatcher(host, new TestDirectory(online, CONSOLE),
            delivery, permissions, quietLogger());
    }

    private Message chatFrom(Actor sender, Direction direction) {
        return Message.builder()
            .sender(sender)
            .direction(direction)
            .text("hola")
            .channel("chat")
            .build();
    }

    @Test
    void initiatorGoesOnlyToSender() throws Exception {
        writeLayout(false);
        RecordingDelivery delivery = new RecordingDelivery();
        DispatchReport report = dispatcher(delivery, List.of(STEVE, ALEX),
            PermissionChecker.ALLOW_ALL)
            .dispatch(chatFrom(STEVE, Direction.initiator()));

        assertEquals(1, report.considered());
        assertEquals(1, report.delivered());
        assertEquals(List.of(STEVE), delivery.playerTargets);
        assertEquals(1, delivery.toPlayers.size());
        assertTrue(delivery.toConsole.isEmpty());
    }

    @Test
    void othersExcludesSender() throws Exception {
        writeLayout(false);
        RecordingDelivery delivery = new RecordingDelivery();
        DispatchReport report = dispatcher(delivery, List.of(STEVE, ALEX),
            PermissionChecker.ALLOW_ALL)
            .dispatch(chatFrom(STEVE, Direction.others()));

        assertEquals(List.of(ALEX), delivery.playerTargets);
        assertEquals(1, report.delivered());
    }

    @Test
    void allIncludesEveryoneAndDeduplicates() throws Exception {
        writeLayout(false);
        RecordingDelivery delivery = new RecordingDelivery();
        // Directory lists STEVE twice on purpose: expansion must deduplicate.
        DispatchReport report = dispatcher(delivery, List.of(STEVE, ALEX, STEVE),
            PermissionChecker.ALLOW_ALL)
            .dispatch(chatFrom(STEVE, Direction.all()));

        assertEquals(2, report.considered());
        assertEquals(2, delivery.playerTargets.size());
        assertTrue(delivery.playerTargets.contains(STEVE));
        assertTrue(delivery.playerTargets.contains(ALEX));
    }

    @Test
    void consoleDirectionUsesConsoleRecipient() throws Exception {
        writeLayout(false);
        RecordingDelivery delivery = new RecordingDelivery();
        DispatchReport report = dispatcher(delivery, List.of(STEVE),
            PermissionChecker.ALLOW_ALL)
            .dispatch(chatFrom(STEVE, Direction.console()));

        assertEquals(1, report.delivered());
        assertEquals(List.of(CONSOLE), delivery.playerTargets);
    }

    @Test
    void redirectRuleSendsToConsoleInsteadOfPlayers() throws Exception {
        writeLayout(false);
        SuiteHost host = SuiteHost.bootstrap(dir, PermissionChecker.ALLOW_ALL,
            fakeTranslation(), quietLogger());
        host.router().setRules(List.of(
            Rule.builder(PolicyTarget.REDIRECT).reason("audit").build()));
        RecordingDelivery delivery = new RecordingDelivery();
        MessageDispatcher subject = new MessageDispatcher(host,
            new TestDirectory(List.of(STEVE, ALEX), CONSOLE),
            delivery, PermissionChecker.ALLOW_ALL, quietLogger());

        DispatchReport report = subject.dispatch(chatFrom(STEVE, Direction.others()));

        assertEquals(1, report.redirected());
        assertEquals(0, report.delivered());
        assertEquals(1, delivery.toConsole.size());
        assertTrue(delivery.toPlayers.isEmpty());
    }

    @Test
    void dropRuleSilencesEverything() throws Exception {
        writeLayout(false);
        SuiteHost host = SuiteHost.bootstrap(dir, PermissionChecker.ALLOW_ALL,
            fakeTranslation(), quietLogger());
        host.router().setRules(List.of(
            Rule.builder(PolicyTarget.DROP).reason("muted").build()));
        RecordingDelivery delivery = new RecordingDelivery();
        MessageDispatcher subject = new MessageDispatcher(host,
            new TestDirectory(List.of(STEVE, ALEX), CONSOLE),
            delivery, PermissionChecker.ALLOW_ALL, quietLogger());

        DispatchReport report = subject.dispatch(chatFrom(STEVE, Direction.all()));

        assertEquals(2, report.silenced());
        assertEquals(0, report.delivered());
        assertTrue(delivery.toPlayers.isEmpty());
    }

    @Test
    void permissionDirectionFiltersByPermission() throws Exception {
        writeLayout(false);
        PermissionChecker vipOnlyAlex = (actor, permission) ->
            actor.equals(ALEX) && permission.equals("suite.vip");
        RecordingDelivery delivery = new RecordingDelivery();

        DispatchReport report = dispatcher(delivery, List.of(STEVE, ALEX), vipOnlyAlex)
            .dispatch(chatFrom(STEVE, Direction.permission("suite.vip")));

        assertEquals(List.of(ALEX), delivery.playerTargets);
        assertEquals(1, report.delivered());
    }

    @Test
    void specificDirectionDeliversExactlyTheListedActors() throws Exception {
        writeLayout(false);
        RecordingDelivery delivery = new RecordingDelivery();

        DispatchReport report = dispatcher(delivery, List.of(STEVE, ALEX),
            PermissionChecker.ALLOW_ALL)
            .dispatch(chatFrom(STEVE, Direction.specific(
                me.majhrs16.suite.api.message.Channel.CHAT, ALEX)));

        assertEquals(List.of(ALEX), delivery.playerTargets);
    }

    @Test
    void radiusWithoutLocatorDeliversNobodyButDoesNotThrow() throws Exception {
        writeLayout(false);
        RecordingDelivery delivery = new RecordingDelivery();

        DispatchReport report = dispatcher(delivery, List.of(STEVE, ALEX),
            PermissionChecker.ALLOW_ALL)
            .dispatch(chatFrom(STEVE, Direction.radius(32.5)));

        assertEquals(0, report.delivered());
        assertTrue(delivery.toPlayers.isEmpty());
    }

    @Test
    void cancelledMessageIsNoOp() throws Exception {
        writeLayout(false);
        RecordingDelivery delivery = new RecordingDelivery();
        Message cancelled = chatFrom(STEVE, Direction.all()).toBuilder()
            .cancelled(true)
            .build();

        DispatchReport report = dispatcher(delivery, List.of(STEVE, ALEX),
            PermissionChecker.ALLOW_ALL).dispatch(cancelled);

        assertEquals(0, report.considered());
        assertEquals("cancelled", report.skipReason());
        assertTrue(delivery.toPlayers.isEmpty());
    }

    @Test
    void channelSoundsPlayPerRecipientWhenRegistryKnowsThem() throws Exception {
        writeLayout(true);
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        dispatcher(delivery, List.of(STEVE, ALEX), PermissionChecker.ALLOW_ALL)
            .dispatch(chatFrom(STEVE, Direction.all()));

        Set<String> expected = Set.of(
            "Steve:entity.experience_orb.pickup",
            "Alex:entity.experience_orb.pickup");
        assertEquals(expected, new HashSet<>(delivery.played));
    }

    @Test
    void unknownSoundIsSkippedWithoutFailing() throws Exception {
        writeLayout(true);
        RecordingDelivery delivery = new RecordingDelivery();
        // knownSounds intentionally empty -> hasSound(name) == false

        DispatchReport report = dispatcher(delivery, List.of(STEVE),
            PermissionChecker.ALLOW_ALL)
            .dispatch(chatFrom(STEVE, Direction.all()));

        assertEquals(1, report.delivered());
        assertTrue(delivery.played.isEmpty());
    }

    @Test
    void soundDisabledInConfigSilencesAllChannelSounds() throws Exception {
        Files.createDirectories(dir.resolve("channels"));
        Files.writeString(dir.resolve("config.yml"), """
            general:
              language: en
            sonido:
              enabled: false
            """);
        Files.writeString(dir.resolve("channels/chat.yml"), """
            name: chat
            messages:
              - '<gray>%player_name%: <tr>%content%</tr></gray>'
            sounds:
              - name: entity.experience_orb.pickup
                volume: 1.0
                pitch: 1.0
            """);
        RecordingDelivery delivery = new RecordingDelivery();
        delivery.knownSounds.add("entity.experience_orb.pickup");

        DispatchReport report = dispatcher(delivery, List.of(STEVE),
            PermissionChecker.ALLOW_ALL)
            .dispatch(chatFrom(STEVE, Direction.all()));

        assertEquals(1, report.delivered());
        assertTrue(delivery.played.isEmpty(), "sonido.enabled=false must gate sounds");
    }

    @Test
    void worldDirectionFallsBackToEmptyWithoutLocator() throws Exception {
        writeLayout(false);
        RecordingDelivery delivery = new RecordingDelivery();

        DispatchReport report = dispatcher(delivery, List.of(STEVE, ALEX),
            PermissionChecker.ALLOW_ALL)
            .dispatch(chatFrom(STEVE, Direction.world("world_nether")));

        assertEquals(0, report.delivered());
        assertTrue(delivery.toPlayers.isEmpty());
    }

    @Test
    void consoleDirectionDoesNotUseConsoleAuditPort() throws Exception {
        writeLayout(false);
        RecordingDelivery delivery = new RecordingDelivery();

        dispatcher(delivery, List.of(STEVE), PermissionChecker.ALLOW_ALL)
            .dispatch(chatFrom(STEVE, Direction.console()));

        assertTrue(delivery.toConsole.isEmpty(),
            "CONSOLE direction is a normal deliver, not a REDIRECT audit copy");
    }
}
