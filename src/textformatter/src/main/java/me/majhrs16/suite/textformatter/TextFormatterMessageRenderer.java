package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.render.MessageComponentRenderer;
import me.majhrs16.suite.api.spi.MessageRenderer;
import me.majhrs16.suite.textformatter.template.TemplateContext;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Objects;

/**
 * Adapter over {@link TextFormatter} that exposes both neutral render ports:
 * plain text for in-world lines and test probes, and rich components for the
 * platform delivery pipeline.
 *
 * <p>Registered by the textformatter package so platform adapters can render
 * without importing textformatter types.</p>
 */
public final class TextFormatterMessageRenderer
        implements MessageRenderer, MessageComponentRenderer {

    private final TextFormatter formatter;

    public TextFormatterMessageRenderer(TextFormatter formatter) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
    }

    @Override
    public String render(Message message, String content, Actor recipient,
                         Language defaultLanguage) {
        Objects.requireNonNull(message, "message");
        Language fallback = fallbackLanguage(recipient, defaultLanguage);
        Language source = message.langSource() != null
            && message.langSource() != Language.AUTO
            ? message.langSource()
            : fallback;
        Language target = message.resolvedSourceLanguage() != null
            ? message.resolvedSourceLanguage()
            : source;
        Component rendered = render(message, content, recipient, source, target);
        return PlainTextComponentSerializer.plainText().serialize(rendered);
    }

    @Override
    public Component render(Message message, Actor recipient,
                            Language sourceLanguage, Language targetLanguage) {
        return render(message, recipient, sourceLanguage, targetLanguage, null);
    }

    /**
     * Renders with an explicit translation backend selected by a
     * {@code sync.<backend>} output target.
     *
     * @param translationBackend provider name, or {@code null} for the facade
     *                           default and fallback
     */
    public Component render(Message message, Actor recipient,
                            Language sourceLanguage, Language targetLanguage,
                            String translationBackend) {
        Objects.requireNonNull(message, "message");
        Language source = normalize(sourceLanguage, recipient);
        Language target = normalize(targetLanguage, source);
        return render(message, message.text(), recipient, source, target, translationBackend);
    }

    /**
     * @param backend provider name from a {@code sync.*} target
     * @return whether the translation service knows that provider
     */
    public boolean hasTranslationBackend(String backend) {
        return formatter.renderer().translation().hasProvider(backend);
    }

    private Component render(Message message, String content, Actor recipient,
                             Language source, Language target) {
        return render(message, content, recipient, source, target, null);
    }

    private Component render(Message message, String content, Actor recipient,
                             Language source, Language target, String translationBackend) {
        Actor sender = message.sender() != null ? message.sender() : recipient;
        if (sender == null) {
            sender = Actor.console("Console", source);
        }

        TemplateContext context = TemplateContext.builder(sender, source, target)
            .content(content == null ? "" : content)
            .translate(message.shouldTranslate())
            .translationBackend(translationBackend)
            .build();
        return formatter.format(message, context);
    }

    private static Language fallbackLanguage(Actor recipient, Language defaultLanguage) {
        if (defaultLanguage != null && defaultLanguage != Language.AUTO) {
            return defaultLanguage;
        }
        if (recipient != null && recipient.language() != null
                && recipient.language() != Language.AUTO) {
            return recipient.language();
        }
        return Language.EN;
    }

    private static Language normalize(Language language, Actor recipient) {
        if (language != null && language != Language.AUTO) {
            return language;
        }
        return fallbackLanguage(recipient, null);
    }

    private static Language normalize(Language language, Language fallback) {
        if (language != null && language != Language.AUTO) {
            return language;
        }
        return fallback == null || fallback == Language.AUTO ? Language.EN : fallback;
    }
}
