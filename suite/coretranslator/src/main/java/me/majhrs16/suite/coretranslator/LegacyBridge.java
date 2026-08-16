package me.majhrs16.suite.coretranslator;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Formats;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.coretranslator.legacy.ChatMessage;
import me.majhrs16.suite.coretranslator.legacy.ChatMessageType;

import java.util.Optional;

/**
 * The single, deprecated bridge between the v1.8 {@link ChatMessage legacy
 * shape} and the v2.1 atomic {@link Message}.
 *
 * <p>The unsupported legacy {@code context} map and per-sender cancellation
 * are intentionally not representable in the atomic model; the bridge drops
 * them (keeping {@code cancelled} and {@code translate}).</p>
 */
@Deprecated
public interface LegacyBridge {

    /** Converts a legacy-shaped message into the v2.1 atomic message. */
    Message toSuite(ChatMessage legacy);

    /** Converts a v2.1 atomic message back into the legacy-shaped message. */
    ChatMessage fromSuite(Message message);

    /** Legacy message kind → atomic type. */
    static MessageType mapType(ChatMessageType type) {
        return switch (type) {
            case CHAT -> MessageType.CHAT;
            case PRIVATE_CHAT -> MessageType.PRIVATE;
            case MENTION -> MessageType.MENTION;
            case JOIN -> MessageType.JOIN;
            case LEAVE -> MessageType.LEAVE;
            case DEATH -> MessageType.DEATH;
            case ADVANCEMENT -> MessageType.ADVANCEMENT;
            case SIGN -> MessageType.SIGN;
            case INTERNAL -> MessageType.INTERNAL;
        };
    }

    /** Atomic type → legacy message kind. */
    static ChatMessageType mapType(MessageType type) {
        return switch (type) {
            case CHAT -> ChatMessageType.CHAT;
            case PRIVATE -> ChatMessageType.PRIVATE_CHAT;
            case MENTION -> ChatMessageType.MENTION;
            case JOIN -> ChatMessageType.JOIN;
            case LEAVE -> ChatMessageType.LEAVE;
            case DEATH -> ChatMessageType.DEATH;
            case ADVANCEMENT -> ChatMessageType.ADVANCEMENT;
            case SIGN -> ChatMessageType.SIGN;
            case INTERNAL, CUSTOM -> ChatMessageType.INTERNAL;
        };
    }

    /**
     * Default implementation of the bridge.
     */
    static LegacyBridge defaultBridge() {
        return new LegacyBridge() {
            @Override
            public Message toSuite(ChatMessage legacy) {
                Language source = legacy.sourceLanguage().orElse(Language.AUTO);
                Direction direction = legacy.target()
                    .map(target -> Direction.specific(
                        me.majhrs16.suite.api.message.Channel.CHAT, target))
                    .orElseGet(Direction::others);

                return Message.builder()
                    .id(legacy.id())
                    .type(mapType(legacy.type()))
                    .sender(legacy.sender())
                    .direction(direction)
                    .messages(Formats.of(legacy.content()))
                    .langSource(source)
                    .translate(legacy.shouldTranslate())
                    .cancelled(legacy.isCancelled())
                    .channel("chat")
                    .build();
            }

            @Override
            public ChatMessage fromSuite(Message message) {
                Optional<Actor> target = message.direction().kind() == Direction.Kind.SPECIFIC
                    ? message.direction().recipients().length > 0
                        ? Optional.of(message.direction().recipients()[0])
                        : Optional.empty()
                    : Optional.empty();

                return ChatMessage.builder(mapType(message.type()), message.sender())
                    .id(message.id())
                    .target(target.orElse(null))
                    .content(message.text())
                    .sourceLanguage(message.langSource() == Language.AUTO ? null : message.langSource())
                    .cancelled(message.isCancelled())
                    .translate(message.shouldTranslate())
                    .build();
            }
        };
    }
}