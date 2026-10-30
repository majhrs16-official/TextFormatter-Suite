package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.MessageRenderer;
import me.majhrs16.suite.textformatter.template.TemplateContext;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Objects;

/**
 * Plain-text adapter over {@link TextFormatter}.
 *
 * <p>Registered by the textformatter package so platform adapters can render
 * in-world lines and test-probe output without importing textformatter types.</p>
 */
public final class TextFormatterMessageRenderer implements MessageRenderer {

    private final TextFormatter formatter;

    public TextFormatterMessageRenderer(TextFormatter formatter) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
    }

    @Override
    public String render(Message message, String content, Actor recipient,
                         Language defaultLanguage) {
        Objects.requireNonNull(message, "message");
        Language fallback = defaultLanguage == null || defaultLanguage == Language.AUTO
            ? Language.EN
            : defaultLanguage;
        Language source = message.langSource() != null
            && message.langSource() != Language.AUTO
            ? message.langSource()
            : fallback;
        Language target = message.resolvedSourceLanguage() != null
            ? message.resolvedSourceLanguage()
            : source;
        Actor sender = message.sender() != null ? message.sender() : recipient;
        if (sender == null) {
            sender = Actor.console("Console", fallback);
        }

        TemplateContext context = TemplateContext.builder(sender, source, target)
            .content(content == null ? "" : content)
            .translate(message.shouldTranslate())
            .build();
        Component rendered = formatter.format(message, context);
        return PlainTextComponentSerializer.plainText().serialize(rendered);
    }
}
