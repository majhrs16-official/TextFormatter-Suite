package me.majhrs16.suite.textformatter.template;

import me.majhrs16.suite.api.spi.ExpressionEvaluator;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>optional {@code <expr>} scripting tags are evaluated;</li>
 *   <li>external placeholders are resolved token by token through
 *       {@link PlaceholderResolver} and their values escaped;</li>
 *   <li>{@code %content%} / {@code %ct_messages%} markers are replaced with the
 *       raw message text;</li>
 *   <li>every {@code <tr>...</tr>} span is extracted, translated from
 *       {@code sourceLanguage} to {@code targetLanguage} and re-inserted
 *       escaped;</li>
 *   <li>the resulting string is parsed with MiniMessage.</li>
 * </ol>
 */
public final class TemplateRenderer {

    private static final Pattern EXTERNAL_TOKEN = Pattern.compile("%([A-Za-z0-9_]+)%");

    private static final String OPEN_TR = "<tr>";
    private static final String CLOSE_TR = "</tr>";
    private static final String OPEN_EXPR = "<expr>";
    private static final String CLOSE_EXPR = "</expr>";

    private final TranslationService translation;
    private final PlaceholderResolver placeholders;
    private final ExpressionEvaluator expressions;
    private final PluginLogger logger;
    private final MiniMessage miniMessage;
    private final PlainTextComponentSerializer plainText;

    public TemplateRenderer(TranslationService translation,
                            PlaceholderResolver placeholders,
                            PluginLogger logger) {
        this(translation, placeholders, null, logger);
    }

    public TemplateRenderer(TranslationService translation,
                            PlaceholderResolver placeholders,
                            ExpressionEvaluator expressions,
                            PluginLogger logger) {
        this.translation = Objects.requireNonNull(translation, "translation");
        this.placeholders = placeholders;
        this.expressions = expressions;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.miniMessage = MiniMessage.miniMessage();
        this.plainText = PlainTextComponentSerializer.plainText();
    }

    /** Renders a template as a rich component for a given recipient context. */
    public Component render(Template template, TemplateContext context) {
        String rendered = renderString(template.source(), context);
        return miniMessage.deserialize(rendered);
    }

    /** Renders a template as plain, tag-free text (console, Discord). */
    public String renderPlain(Template template, TemplateContext context) {
        String rendered = renderString(template.source(), context);
        return plainText.serialize(miniMessage.deserialize(rendered));
    }

    /** Exposes the translation service used by this renderer (e.g. detection). */
    public TranslationService translation() {
        return translation;
    }

    private String renderString(String source, TemplateContext context) {
        String resolved = replaceBuiltins(source, context);
        resolved = evaluateExpressions(resolved, context);
        resolved = resolveExternalTokens(resolved, context);
        resolved = replaceContent(resolved, context);
        return translateSpans(resolved, context);
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

    // -- step 2: scripting (optional) ---------------------------------------

    private String evaluateExpressions(String source, TemplateContext context) {
        if (expressions == null) {
            return source;
        }
        StringBuilder result = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            int open = source.indexOf(OPEN_EXPR, index);
            if (open < 0) {
                break;
            }
            int close = source.indexOf(CLOSE_EXPR, open + OPEN_EXPR.length());
            if (close < 0) {
                break;
            }
            String expression = source.substring(open + OPEN_EXPR.length(), close);
            result.append(source, index, open);
            result.append(evaluate(expression, context));
            index = close + CLOSE_EXPR.length();
        }
        result.append(source, index, source.length());
        return result.toString();
    }

    private String evaluate(String expression, TemplateContext context) {
        try {
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("name", context.sender().name());
            bindings.put("uuid", safeUuid(context.sender()));
            bindings.put("content", context.content());
            bindings.put("langSource", context.sourceLanguage().code());
            bindings.put("langTarget", context.targetLanguage().code());
            return MiniEscape.escape(expressions.evaluate(expression, bindings));
        } catch (RuntimeException e) {
            logger.warn("Scripting error: %s", e.getMessage());
            return OPEN_EXPR + expression + CLOSE_EXPR;
        }
    }

    // -- step 3: external placeholders --------------------------------------

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

    // -- step 4: content ---------------------------------------------------

    private String replaceContent(String source, TemplateContext context) {
        return source
            .replace("%content%", MiniEscape.escape(context.content()))
            .replace("%ct_messages%", MiniEscape.escape(context.content()));
    }

    // -- step 5: translatable spans ----------------------------------------

    private String translateSpans(String source, TemplateContext context) {
        List<Span> spans = findSpans(source);
        if (spans.isEmpty()) {
            return source;
        }
        StringBuilder result = new StringBuilder(source.length());
        int cursor = 0;
        for (Span span : spans) {
            result.append(source, cursor, span.start);
            result.append(translateSpan(span.content, context));
            cursor = span.end;
        }
        result.append(source, cursor, source.length());
        return result.toString();
    }

    private String translateSpan(String content, TemplateContext context) {
        if (!context.translate()) {
            return content;
        }
        Language from = context.sourceLanguage();
        Language to = context.targetLanguage();
        if (from == to || to == Language.AUTO) {
            return content;
        }
        if (!translation.isAvailable()) {
            return content;
        }
        return translation.translate(content, from, to);
    }

    private static List<Span> findSpans(String source) {
        List<Span> spans = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            int open = source.indexOf(OPEN_TR, index);
            if (open < 0) {
                break;
            }
            int close = source.toLowerCase().indexOf(CLOSE_TR, open + OPEN_TR.length());
            if (close < 0) {
                break;
            }
            String content = source.substring(open + OPEN_TR.length(), close);
            spans.add(new Span(open, close + CLOSE_TR.length(), content));
            index = close + CLOSE_TR.length();
        }
        return spans;
    }

    private static boolean isContentToken(String token) {
        String name = token.substring(1, token.length() - 1).toLowerCase();
        return name.equals("content") || name.equals("ct_messages");
    }

    private static String safeUuid(Actor actor) {
        return actor.uuid() != null ? actor.uuid().toString() : "";
    }

    private static final class Span {
        private final int start;
        private final int end;
        private final String content;

        private Span(int start, int end, String content) {
            this.start = start;
            this.end = end;
            this.content = content;
        }
    }
}