package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Formats;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.textformatter.template.TemplateContext;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTextFormatterTest {

    private static final PlainTextComponentSerializer PLAIN =
        PlainTextComponentSerializer.plainText();

    /** Builds a Formats whose entries are format TEMPLATES, not literal texts. */
    private static Formats templates(String... templates) {
        return new Formats(new String[0], templates);
    }

    @Test
    void formatsWithChannelFormat() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(Channel.builder("chat")
                .messages(templates("<gray>%player_name%: %content%</gray>"))
                .build())
            .build();
        TextFormatter formatter = TextFormatters.create(registry);

        Message message = Message.builder()
            .sender(Actor.unknown("Steve"))
            .direction(me.majhrs16.suite.api.message.Direction.others())
            .text("hola")
            .channel("chat")
            .build();

        TemplateContext context = TemplateContext.builder(message.sender(), Language.EN, Language.ES)
            .translate(false)
            .content(message.text())
            .build();

        Component rendered = formatter.format(message, context);
        assertTrue(PLAIN.serialize(rendered).contains("Steve: hola"));
    }

    @Test
    void channelFormatWinsOverMessageFormat() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(Channel.builder("chat")
                .messages(templates("<red>CHANNEL:%content%</red>"))
                .build())
            .build();
        TextFormatter formatter = TextFormatters.create(registry);

        Message message = Message.builder()
            .sender(Actor.unknown("Steve"))
            .messages(new Formats(new String[]{"hola"}, new String[]{"<blue>MSG:%content%</blue>"}))
            .channel("chat")
            .build();

        TemplateContext context = TemplateContext.builder(message.sender(), Language.EN, Language.ES)
            .translate(false)
            .content(message.text())
            .build();

        assertEquals("CHANNEL:hola", PLAIN.serialize(formatter.format(message, context)));
    }

    @Test
    void resolvesAncestorFormatsThroughRegistry() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(Channel.builder("chat")
                .messages(templates("<gold>%content%</gold>"))
                .build())
            .build();
        TextFormatter formatter = TextFormatters.create(registry);

        Message message = Message.builder()
            .sender(Actor.unknown("Steve"))
            .text("hola")
            .channel("chat.global")
            .build();

        TemplateContext context = TemplateContext.builder(message.sender(), Language.EN, Language.ES)
            .translate(false)
            .content(message.text())
            .build();

        assertEquals("hola", PLAIN.serialize(formatter.format(message, context)));
    }

    @Test
    void tooltipRendersWhenChannelProvidesIt() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(Channel.builder("chat")
                .tooltips(templates("<gray>tooltip:%content%</gray>"))
                .build())
            .build();
        TextFormatter formatter = TextFormatters.create(registry);

        Message message = Message.builder()
            .sender(Actor.unknown("Steve"))
            .text("hola")
            .channel("chat")
            .toolTips(Formats.of("<blue>other</blue>"))
            .build();

        TemplateContext context = TemplateContext.builder(message.sender(), Language.EN, Language.ES)
            .translate(false)
            .content(message.text())
            .build();

        assertEquals("tooltip:hola", PLAIN.serialize(formatter.formatTooltip(message, context)));
    }

    @Test
    void emptyTooltipIsEmptyComponent() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(Channel.builder("chat").build())
            .build();
        TextFormatter formatter = TextFormatters.create(registry);

        Message message = Message.builder()
            .sender(Actor.unknown("Steve"))
            .text("hola")
            .channel("chat")
            .build();

        TemplateContext context = TemplateContext.builder(message.sender(), Language.EN, Language.ES)
            .translate(false)
            .content(message.text())
            .build();

        assertEquals(Component.empty(), formatter.formatTooltip(message, context));
    }
}