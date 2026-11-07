package me.majhrs16.suite.synccommon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.UserLanguageStore;

import org.junit.jupiter.api.Test;

class SyncEventProcessorTest {

    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final Actor SENDER =
            new Actor(SENDER_ID, "Steve", Actor.ActorKind.PLAYER, Language.EN, null);

    @Test
    void chatEmitsInitiatorBroadcastAndConsole() {
        List<Message> messages = new ArrayList<>();
        SyncEventProcessor processor = processor(messages, registry(true, true), languageStore(null));

        processor.chat(SENDER, "hello", true);

        assertEquals(3, messages.size());
        assertEquals(Direction.Kind.INITIATOR, messages.get(0).direction().kind());
        assertEquals(Direction.Kind.OTHERS, messages.get(1).direction().kind());
        assertEquals(Direction.Kind.CONSOLE, messages.get(2).direction().kind());
        assertEquals("chat.global", messages.get(0).channel());
        assertEquals("hello", messages.get(0).text());
        assertTrue(messages.get(0).shouldTranslate());
    }

    @Test
    void showSenderFalseSuppressesOnlyTheEcho() {
        List<Message> messages = new ArrayList<>();
        SyncEventProcessor processor = processor(messages, registry(false, true), languageStore(null));

        processor.chat(SENDER, "hello", false);

        assertEquals(1, messages.size());
        assertEquals(Direction.Kind.OTHERS, messages.get(0).direction().kind());
    }

    @Test
    void languageOffDisablesTranslation() {
        List<Message> messages = new ArrayList<>();
        SyncEventProcessor processor = processor(
                messages, registry(true, true), languageStore(UserLanguageStore.OFF));

        processor.chat(SENDER, "hola", false);

        assertEquals(2, messages.size());
        assertFalse(messages.get(0).shouldTranslate());
    }

    @Test
    void typedEventRequiresItsConventionalChannel() {
        List<Message> messages = new ArrayList<>();
        SyncEventProcessor processor = processor(messages, registry(true, true), languageStore(null));

        assertTrue(processor.typed(MessageType.JOIN, EventRules.CHANNEL_JOIN, SENDER, "Steve"));
        assertFalse(processor.typed(MessageType.LEAVE, EventRules.CHANNEL_QUIT, SENDER, "Steve"));
        assertEquals(1, messages.size());
        assertEquals(MessageType.JOIN, messages.get(0).type());
        assertEquals(Direction.Kind.ALL, messages.get(0).direction().kind());
    }

    @Test
    void channelSelectorSkipsEventChannelsAndFallsBack() {
        ChannelRegistry registry = ChannelRegistry.builder()
                .register(Channel.builder("join").type(Channel.Type.EVENT).build())
                .register(Channel.builder("staff").permission("staff.chat").build())
                .build();

        assertEquals("staff", ChannelSelector.select(registry.all(), permission -> true));
        assertEquals("chat", ChannelSelector.select(registry.all(), permission -> false));
    }

    private static SyncEventProcessor processor(List<Message> messages,
                                                ChannelRegistry registry,
                                                UserLanguageStore languages) {
        MessageDispatch dispatch = new MessageDispatch() {
            @Override
            public void dispatch(Message message) {
                messages.add(message);
            }

            @Override
            public me.majhrs16.suite.api.spi.DispatchOutcome dispatchAndReport(Message message) {
                messages.add(message);
                return me.majhrs16.suite.api.spi.DispatchOutcome.none();
            }
        };
        return new SyncEventProcessor(dispatch, registry, languages,
                (actor, permission) -> true, noopLogger());
    }

    private static ChannelRegistry registry(boolean showSender, boolean includeJoin) {
        ChannelRegistry.Builder builder = ChannelRegistry.builder()
                .register(Channel.builder("chat.global")
                        .type(Channel.Type.CHAT)
                        .showSender(showSender)
                        .build());
        if (includeJoin) {
            builder.register(Channel.builder(EventRules.CHANNEL_JOIN)
                    .type(Channel.Type.EVENT)
                    .build());
        }
        return builder.build();
    }

    private static UserLanguageStore languageStore(String value) {
        return new UserLanguageStore() {
            @Override public Optional<String> languageOf(UUID uuid) {
                return Optional.ofNullable(value);
            }
            @Override public void save(UUID uuid, String ignored) {
            }
        };
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
