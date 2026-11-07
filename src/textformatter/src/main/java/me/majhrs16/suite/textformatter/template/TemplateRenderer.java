package me.majhrs16.suite.textformatter.template;

import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.message.Actor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders {@link Template templates} into Adventure {@link Component}s.
 *
 * <p>Render pipeline:</p>
 * <ol>
 *   <li>built-in variables ({@code %player_name%}, {@code %lang_source%}, ...)
 *       are replaced with MiniMessage-escaped values;</li>
 *   <li>external placeholders are resolved token by token through
 *       {@link PlaceholderResolver} and their values escaped;</li>
 *   <li>{@code %content%} / {@code %ct_messages%} markers are replaced with the
 *       raw message text;</li>
 *   <li>every {@code <regex match="...">...</regex>} span evaluates its
 *       pre-compiled pattern against the resolved content; the span emits its
 *       content only when the pattern finds a match (anchor it for full
 *       matches);</li>
 *   <li>the resulting string is parsed with MiniMessage.</li>
 * </ol>
 *
 * <p>Translation is not part of the renderer: the {@code translate} pipeline
 * stage transforms the message texts before {@code format} runs this class.</p>
 *
 * <p>Dynamic policy logic belongs to the iFlow VM; templates are deliberately
 * limited to placeholders, MiniMessage markup and the local {@code <regex>}
 * rendering condition from ADR-028.</p>
 */
public final class TemplateRenderer {

    private static final Pattern EXTERNAL_TOKEN = Pattern.compile("%([A-Za-z0-9_]+)%");

    private final PlaceholderResolver placeholders;
    private final MiniMessage miniMessage;
    private final PlainTextComponentSerializer plainText;

    public TemplateRenderer(PlaceholderResolver placeholders, PluginLogger logger) {
        this.placeholders = placeholders;
        Objects.requireNonNull(logger, "logger");
        this.miniMessage = MiniMessage.miniMessage();
        this.plainText = PlainTextComponentSerializer.plainText();
    }

    /** Renders a template as a rich component for a given recipient context. */
    public Component render(Template template, TemplateContext context) {
        String rendered = renderString(template, context);
        return miniMessage.deserialize(rendered);
    }

    /** Renders a template as plain, tag-free text (console, Discord). */
    public String renderPlain(Template template, TemplateContext context) {
        String rendered = renderString(template, context);
        return plainText.serialize(miniMessage.deserialize(rendered));
    }

    private String renderString(Template template, TemplateContext context) {
        return renderRegexSpans(template, context);
    }

    // -- step 4: local regex rendering condition ---------------------------

    private String renderRegexSpans(Template template, TemplateContext context) {
        String source = template.source();
        List<Template.RegexSpan> spans = template.regexSpans();
        if (spans.isEmpty()) {
            return resolve(source, context);
        }

        StringBuilder result = new StringBuilder(source.length());
        int cursor = 0;
        for (Template.RegexSpan span : spans) {
            result.append(resolve(source.substring(cursor, span.start()), context));
            String content = resolve(span.content(), context);
            if (content.length() > Template.MAX_REGEX_CONTENT_LENGTH) {
                throw new IllegalArgumentException("Resolved <regex> content exceeds "
                        + Template.MAX_REGEX_CONTENT_LENGTH + " characters");
            }
            if (span.pattern().matcher(content).find()) {
                result.append(content);
            }
            cursor = span.end();
        }
        result.append(resolve(source.substring(cursor), context));
        return result.toString();
    }

    private String resolve(String source, TemplateContext context) {
        String resolved = replaceBuiltins(source, context);
        resolved = resolveExternalTokens(resolved, context);
        return replaceContent(resolved, context);
    }

    // -- step 1: built-ins -------------------------------------------------

    private String replaceBuiltins(String source, TemplateContext context) {
        String result = source;
        result = result.replace("%player_name%", MiniEscape.escape(context.sender().name()));
        result = result.replace("%player_uuid%", MiniEscape.escape(safeUuid(context.sender())));
        result = result.replace("%lang_source%", MiniEscape.escape(context.sourceLanguage().code()));
        result = result.replace("%lang_target%", MiniEscape.escape(context.targetLanguage().code()));
        for (String key : context.variables().keySet()) {
            result = result.replace("%" + key + "%", MiniEscape.escape(context.variable(key)));
        }
        return result;
    }

    // -- step 2: external placeholders --------------------------------------

    private String resolveExternalTokens(String source, TemplateContext context) {
        if (placeholders == null || !placeholders.available()) {
            return source;
        }
        Matcher matcher = EXTERNAL_TOKEN.matcher(source);
        StringBuilder resolved = new StringBuilder(source.length());
        int last = 0;
        while (matcher.find()) {
            String token = matcher.group();
            if (isContentToken(token)) {
                continue;
            }
            resolved.append(source, last, matcher.start());
            String value = placeholders.resolve(context.sender(), token);
            resolved.append(MiniEscape.escape(value));
            last = matcher.end();
        }
        resolved.append(source, last, source.length());
        return resolved.toString();
    }

    // -- step 3: content ---------------------------------------------------

    private String replaceContent(String source, TemplateContext context) {
        return source
            .replace("%content%", MiniEscape.escape(context.content()))
            .replace("%ct_messages%", MiniEscape.escape(context.content()));
    }

    private static boolean isContentToken(String token) {
        String name = token.substring(1, token.length() - 1).toLowerCase();
        return name.equals("content") || name.equals("ct_messages");
    }

    private static String safeUuid(Actor actor) {
        return actor.uuid() != null ? actor.uuid().toString() : "";
    }
}
