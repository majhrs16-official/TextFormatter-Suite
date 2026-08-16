package me.majhrs16.suite.coretranslator;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.coretranslator.legacy.ChatMessage;
import me.majhrs16.suite.coretranslator.legacy.ChatMessageType;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyBridgeTest {

    private static final Actor STEVE = Actor.unknown("Steve");
    private static final Actor ALEX = Actor.unknown("Alex");

    private final LegacyBridge bridge = LegacyBridge.defaultBridge();

    @Test
    void mapsLegacyChatToAtomicChat() {
        ChatMessage legacy = ChatMessage.builder(ChatMessageType.CHAT, STEVE)
            .content("hola")
            .sourceLanguage(Language.EN)
            .build();

        Message suite = bridge.toSuite(legacy);

        assertEquals(MessageType.CHAT, suite.type());
        assertEquals("hola", suite.text());
        assertEquals(Language.EN, suite.langSource());
        assertEquals(STEVE, suite.sender());
        assertEquals(true, suite.shouldTranslate());
        assertEquals(Direction.Kind.OTHERS, suite.direction().kind());
    }

    @Test
    void mapsPrivateChatToSpecificTarget() {
        ChatMessage legacy = ChatMessage.builder(ChatMessageType.PRIVATE_CHAT, STEVE)
            .target(ALEX)
            .content("secreto")
            .build();

        Message suite = bridge.toSuite(legacy);

        assertEquals(MessageType.PRIVATE, suite.type());
        assertEquals(Direction.Kind.SPECIFIC, suite.direction().kind());
        assertEquals(ALEX, suite.direction().recipients()[0]);
    }

    @Test
    void autoSourceWhenLegacyIsEmpty() {
        ChatMessage legacy = ChatMessage.builder(ChatMessageType.CHAT, STEVE)
            .content("hola")
            .build();

        assertEquals(Language.AUTO, bridge.toSuite(legacy).langSource());
    }

    @Test
    void roundTripsBackToLegacy() {
        ChatMessage legacy = ChatMessage.builder(ChatMessageType.CHAT, STEVE)
            .target(ALEX)
            .content("hola")
            .sourceLanguage(Language.EN)
            .cancelled(true)
            .translate(false)
            .build();

        Message suite = bridge.toSuite(legacy);
        ChatMessage back = bridge.fromSuite(suite);

        assertEquals(legacy.type(), back.type());
        assertEquals(legacy.sender(), back.sender());
        assertEquals(Optional.of(ALEX), back.target());
        assertEquals("hola", back.content());
        assertEquals(Optional.of(Language.EN), back.sourceLanguage());
        assertEquals(true, back.isCancelled());
        assertEquals(false, back.shouldTranslate());
    }

    @Test
    void atomicBackToLegacyKeepsContentAndFlags() {
        Message suite = Message.builder()
            .type(MessageType.MENTION)
            .sender(STEVE)
            .direction(Direction.others())
            .text("@Alex mira esto")
            .langSource(Language.ES)
            .translate(true)
            .build();

        ChatMessage legacy = bridge.fromSuite(suite);

        assertEquals(ChatMessageType.MENTION, legacy.type());
        assertEquals("@Alex mira esto", legacy.content());
        assertEquals(Optional.of(Language.ES), legacy.sourceLanguage());
        assertFalse(legacy.target().isPresent());
    }

    @Test
    void preservesIdAcrossConversion() {
        UUID id = UUID.randomUUID();
        ChatMessage legacy = ChatMessage.builder(ChatMessageType.JOIN, STEVE)
            .id(id)
            .content("entró")
            .build();

        assertEquals(id, bridge.toSuite(legacy).id());
    }
}