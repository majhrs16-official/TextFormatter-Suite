package me.majhrs16.cht.core.chat;

import me.majhrs16.cht.core.config.ChatSettings;
import me.majhrs16.cht.core.config.FormatGroups;
import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.message.ChatMessage;
import me.majhrs16.cht.core.message.ChatMessageType;
import me.majhrs16.cht.core.message.SoundSpec;
import me.majhrs16.cht.core.platform.ChatDisplay;
import me.majhrs16.cht.core.platform.DirectionResolver;
import me.majhrs16.cht.core.platform.PermissionChecker;
import me.majhrs16.cht.core.platform.PlaceholderResolver;
import me.majhrs16.cht.core.platform.PlayerRegistry;
import me.majhrs16.cht.core.platform.PluginLogger;
import me.majhrs16.cht.core.platform.Scheduler;
import me.majhrs16.cht.core.platform.SilentLogger;
import me.majhrs16.cht.core.player.Channel;
import me.majhrs16.cht.core.player.Subject;
import me.majhrs16.cht.core.rules.RulesEngine;
import me.majhrs16.cht.core.storage.UserStore;
import me.majhrs16.cht.core.template.Template;
import me.majhrs16.cht.core.template.TemplateRenderer;
import me.majhrs16.cht.core.translate.StubTranslator;
import me.majhrs16.cht.core.translate.TranslationService;
import me.majhrs16.cht.core.translate.TranslatorManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRouterTest {

    private static final UUID SENDER_UUID = UUID.randomUUID();
    private static final UUID BOB_UUID = UUID.randomUUID();

    private ChatRouter router;
    private FakeChatDisplay display;
    private FakePlayerRegistry players;
    private FakeUserStore users;

    @BeforeEach
    void setUp() {
        TranslationService translation =
            new TranslationService(new TranslatorManager().add(new StubTranslator()));
        TemplateRenderer renderer = new TemplateRenderer(
            translation, new NoopPlaceholders(), new SilentLogger());

        display = new FakeChatDisplay();
        players = new FakePlayerRegistry();
        users = new FakeUserStore();
        FormatGroups groups;
        try {
            groups = FormatGroups.load(
                new java.io.ByteArrayInputStream(
                    ("chat:\n"
                    + "  messages:\n"
                    + "    formats:\n"
                    + "      - '<tr>%ct_messages%</tr>'\n"
                    + "  sounds:\n"
                    + "    entity.experience_orb.pickup:\n"
                    + "      volume: 1.0\n"
                    + "      pitch: 1.0\n"
                    + "private:\n"
                    + "  messages:\n"
                    + "    formats:\n"
                    + "      - '<gray><tr>%ct_messages%</tr></gray>'\n"
                    ).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }

        ChatSettings settings = ChatSettings.builder()
            .defaultLanguage(Language.ES)
            .build();

        DirectionResolver directions = new DefaultDirectionResolver(
            players, users, new FakePermissions(), settings);
        RulesEngine rules = new RulesEngine(
            java.util.Collections.<RulesEngine.Rule>emptyList(),
            new me.majhrs16.cht.core.scripting.SpelExpressionEvaluator(),
            new SilentLogger());

        router = new ChatRouter(groups, renderer, directions, users, display,
            new ImmediateScheduler(), settings, new FakePermissions(), rules,
            new SilentLogger());
    }

    @Test
    void deliversToEveryOnlinePlayerPlusConsole() {
        players.online = Arrays.asList(
            sender(),
            new Subject(BOB_UUID, "Bob", Subject.SubjectKind.PLAYER, null));

        router.dispatch(message("hola mundo"));

        // 2 players + console
        assertEquals(3, display.sent.size());
        assertTrue(display.sent.stream().anyMatch(
            s -> s.subject.name().equals("Bob")));
        assertTrue(display.sent.stream().anyMatch(
            s -> s.channel == Channel.CONSOLE));
    }

    @Test
    void senderSeesNativeMessageByDefault() {
        players.online = Arrays.asList(sender(), bob());

        router.dispatch(message("hola mundo"));

        String bobText = textOf("Bob");
        String senderText = textOf("Majhrs");
        assertEquals("hello world", bobText);   // translated
        assertEquals("hola mundo", senderText); // native for the sender
    }

    @Test
    void privateChatOnlyReachesTarget() {
        players.online = Arrays.asList(sender(), bob());

        ChatMessage privateMessage = ChatMessage
            .builder(ChatMessageType.PRIVATE_CHAT, sender())
            .target(bob())
            .content("hola")
            .build();
        router.dispatch(privateMessage);

        assertEquals(1, display.sent.size());
        assertEquals("Bob", display.sent.get(0).subject.name());
        assertEquals(Channel.PRIVATE, display.sent.get(0).channel);
    }

    @Test
    void playsSoundWhenSpecDeclaresIt() {
        players.online = Arrays.asList(sender(), bob());
        router.dispatch(message("hola mundo"));
        assertEquals(2, display.played.size());
    }

    private ChatMessage message(String content) {
        return ChatMessage.builder(ChatMessageType.CHAT, sender())
            .sourceLanguage(Language.ES)
            .content(content)
            .build();
    }

    private String textOf(String recipient) {
        String plain = display.sent.stream()
            .filter(s -> s.subject.name().equals(recipient))
            .map(s -> PlainTextComponentSerializer.plainText().serialize(s.component))
            .findFirst()
            .orElseThrow(AssertionError::new);
        return plain.trim();
    }

    private Subject sender() {
        return new Subject(SENDER_UUID, "Majhrs", Subject.SubjectKind.PLAYER, null);
    }

    private Subject bob() {
        return new Subject(BOB_UUID, "Bob", Subject.SubjectKind.PLAYER, null);
    }

    // -- test doubles -------------------------------------------------------

    private static final class FakePlayerRegistry implements PlayerRegistry {
        private List<Subject> online = new ArrayList<>();

        @Override
        public Collection<Subject> onlinePlayers() {
            return online;
        }

        @Override
        public Optional<Subject> playerByName(String name) {
            return online.stream()
                .filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
        }

        @Override
        public Optional<Subject> playerByUuid(UUID uuid) {
            return online.stream().filter(p -> uuid.equals(p.uuid())).findFirst();
        }
    }

    private static final class FakeUserStore implements UserStore {
        @Override public Optional<Language> language(UUID uuid) { return Optional.of(Language.EN); }
        @Override public void setLanguage(UUID uuid, Language language) { }
        @Override public Optional<String> discordLink(UUID uuid) { return Optional.empty(); }
        @Override public void linkDiscord(UUID uuid, String discordId) { }
        @Override public void unlinkDiscord(UUID uuid) { }
        @Override public Optional<UUID> playerBoundToDiscord(String discordId) { return Optional.empty(); }
        @Override public String type() { return "fake"; }
        @Override public void close() { }
    }

    private static final class FakeChatDisplay implements ChatDisplay {
        private final List<Sent> sent = new ArrayList<>();
        private final List<Subject> played = new ArrayList<>();

        @Override
        public void send(Subject recipient, Component message, Channel channel) {
            sent.add(new Sent(recipient, message, channel));
        }

        @Override
        public void playSound(Subject recipient, SoundSpec sound) {
            played.add(recipient);
        }

        @Override public void sendToConsole(String message) { }
        @Override public void dispatchServerCommand(String command) { }
        @Override public void dispatchCommand(Subject actor, String command, Channel channel) { }
    }

    private static final class Sent {
        private final Subject subject;
        private final Component component;
        private final Channel channel;

        private Sent(Subject subject, Component component, Channel channel) {
            this.subject = subject;
            this.component = component;
            this.channel = channel;
        }
    }

    private static final class ImmediateScheduler implements Scheduler {
        @Override public void runOnMainThread(Runnable task) { task.run(); }
        @Override public void runAsync(Runnable task) { task.run(); }
        @Override public void runAsyncLater(Runnable task, long delay, TimeUnit unit) { task.run(); }
        @Override public void runAsyncRepeating(Runnable task, long delay, long period, TimeUnit unit) { }
    }

    private static final class NoopPlaceholders implements PlaceholderResolver {
        @Override public String resolve(Subject subject, String input) { return input; }
        @Override public boolean available() { return false; }
    }

    private static final class FakePermissions implements PermissionChecker {
        @Override public boolean has(Subject subject, String node) { return true; }
    }
}
