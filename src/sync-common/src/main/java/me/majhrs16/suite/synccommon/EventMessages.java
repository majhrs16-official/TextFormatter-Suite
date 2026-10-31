package me.majhrs16.suite.synccommon;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;

/**
 * Builds the neutral messages emitted by platform event capture.
 *
 * <p>The platform listeners only translate native events into these calls;
 * routing, formatting and delivery remain in the suite pipeline.</p>
 */
public final class EventMessages {

    private EventMessages() {
    }

    /** A chat unit for one audience. */
    public static Message chat(Actor sender, Direction direction, String channel,
                               String text, boolean translate) {
        return Message.builder()
                .type(MessageType.CHAT)
                .sender(sender)
                .direction(direction)
                .translate(translate)
                .text(text == null ? "" : text)
                .channel(channel)
                .build();
    }

    /** A join/quit/death/advancement unit for the whole server. */
    public static Message typed(MessageType type, Actor subject, String channel,
                                String text, boolean translate) {
        return Message.builder()
                .type(type)
                .sender(subject)
                .direction(Direction.all())
                .translate(translate)
                .text(text == null ? "" : text)
                .channel(channel)
                .build();
    }
}
