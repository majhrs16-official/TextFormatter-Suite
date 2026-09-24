package me.majhrs16.suite.synchttp;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageCodecTest {

    private final Message sample = Message.builder()
        .type(me.majhrs16.suite.api.message.MessageType.CHAT)
        .sender(Actor.unknown("Steve"))
        .direction(Direction.others())
        .text("hola mundo")
        .langSource(Language.EN)
        .langTarget(Language.ES)
        .channel("chat")
        .build();

    @Test
    void roundTripsCoreFields() {
        Message decoded = MessageCodec.fromJson(MessageCodec.toJson(sample).toString());

        assertEquals(sample.id(), decoded.id());
        assertEquals(sample.sender().name(), decoded.sender().name());
        assertEquals("hola mundo", decoded.text());
        assertEquals(Language.EN, decoded.langSource());
        assertEquals(Language.ES, decoded.langTarget());
        assertEquals(true, decoded.shouldTranslate());
        assertFalse(decoded.isCancelled());
        assertEquals("chat", decoded.channel());
    }

    @Test
    void preserveShowAndCancelFlags() {
        Message cancelled = sample.toBuilder().cancelled(true).translate(false).build();
        Message decoded = MessageCodec.fromJson(MessageCodec.toJson(cancelled).toString());

        assertTrue(decoded.isCancelled());
        assertFalse(decoded.shouldTranslate());
    }
}