package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.MessageRenderer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFormatterMessageRendererTest {

    @Test
    void rendersMessageContentAsPlainText() {
        MessageRenderer renderer = new TextFormatterMessageRenderer(
            TextFormatters.create(ChannelRegistry.builder().build()));
        Actor actor = new Actor(UUID.randomUUID(), "Steve",
            Actor.ActorKind.PLAYER, Language.EN, null);
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(actor)
            .text("Hello")
            .channel("chat")
            .build();

        String rendered = renderer.render(message, "Hello", actor, Language.EN);

        assertTrue(rendered.contains("Hello"));
    }

    @Test
    void handlesNullContentAndAutoFallback() {
        MessageRenderer renderer = new TextFormatterMessageRenderer(
            TextFormatters.create(ChannelRegistry.builder().build()));
        Actor actor = Actor.console("Console", Language.EN);
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(actor)
            .text("")
            .channel("chat")
            .build();

        String rendered = renderer.render(message, null, actor, Language.AUTO);

        assertTrue(rendered.isEmpty());
    }
}
