package me.majhrs16.cht.core.event;

import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.message.Actor;
import me.majhrs16.cht.core.message.Direction;
import me.majhrs16.cht.core.message.Message;
import me.majhrs16.cht.core.message.MessageType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageEventBusTest {

    private static final Actor SENDER = new Actor(
        java.util.UUID.randomUUID(), "Majhrs", Actor.ActorKind.PLAYER, Language.ES, null);

    private static Message message() {
        return Message.builder()
            .type(MessageType.CHAT)
            .sender(SENDER)
            .direction(Direction.others())
            .texts("hola")
            .build();
    }

    @Test
    void passesThroughWhenNoListeners() {
        MessageEventBus bus = new MessageEventBus();
        Message out = bus.fire(message());
        assertNotNull(out);
        assertEquals("hola", out.text());
    }

    @Test
    void canReplaceMessage() {
        MessageEventBus bus = new MessageEventBus();
        bus.register("replace", event -> event.setMessage(
            event.message().toBuilder().texts("nuevo").build()));

        Message out = bus.fire(message());
        assertNotNull(out);
        assertEquals("nuevo", out.text());
    }

    @Test
    void canCancelMessage() {
        MessageEventBus bus = new MessageEventBus();
        bus.register("cancel", event -> event.setCancelled(true));

        assertNull(bus.fire(message()));
    }

    @Test
    void stopsOnCancel() {
        MessageEventBus bus = new MessageEventBus();
        final boolean[] secondRan = { false };
        bus.register("first", event -> event.setCancelled(true));
        bus.register("second", event -> secondRan[0] = true);

        assertNull(bus.fire(message()));
        assertTrue(!secondRan[0], "listener after cancel must not run");
    }

    @Test
    void processedStopsRouting() {
        MessageEventBus bus = new MessageEventBus();
        final boolean[] secondRan = { false };
        bus.register("first", event -> event.setProcessed(true));
        bus.register("second", event -> secondRan[0] = true);

        Message out = bus.fire(message());
        assertNotNull(out);
        assertTrue(!secondRan[0], "listener after processed must not run");
    }
}