package me.majhrs16.suite.textformatter.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

/**
 * An immutable, authored chat template written with the MiniMessage syntax
 * plus the engine's own {@code <regex>...</regex>} spans.
 *
 * <p>Example:</p>
 * <pre>{@code
 *   <gray><bold>%player_name%</bold></gray>
 *   <white>%content%</white>
 * }</pre>
 *
 * <p>A {@code <regex>} span is parsed and its pattern is compiled once, when
 * the template is created. This makes an invalid pattern a configuration error
 * instead of a render-time surprise. The span itself only decides whether its
 * content is rendered; it never executes code, evaluates SpEL or mutates the
 * original message.</p>
 */
public final class Template {

    /**
     * Maximum pattern length in UTF-16 code units. Patterns are authored in
     * configuration, never received from a message.
     */
    public static final int MAX_REGEX_PATTERN_LENGTH = 512;

    /**
     * Maximum evaluated content length in UTF-16 code units. This bounds the
     * input handed to the regex engine and therefore the backtracking cost.
     */
    public static final int MAX_REGEX_CONTENT_LENGTH = 8192;

    /** Maximum number of {@code <regex>} spans in one template. */
    public static final int MAX_REGEX_SPANS = 32;

    private static final Pattern OPEN_REGEX =
            Pattern.compile("<regex(?=\\s|>)", CASE_INSENSITIVE);
    private static final Pattern CLOSE_REGEX =
            Pattern.compile("</regex\\s*>", CASE_INSENSITIVE);
    private static final Pattern MATCH_ATTRIBUTE =
            Pattern.compile("^\\s*match\\s*=\\s*\"([^\"]*)\"\\s*$", CASE_INSENSITIVE);

    private final String source;
    private final List<RegexSpan> regexSpans;

    private Template(String source, List<RegexSpan> regexSpans) {
        this.source = source;
        this.regexSpans = List.copyOf(regexSpans);
    }

    /**
     * Parses a template and compiles every {@code <regex>} pattern.
     *
     * @param source raw template
     * @return immutable parsed template
     * @throws IllegalArgumentException if a {@code <regex>} span is malformed,
     *                                  nested, empty, invalid or over a limit
     */
    public static Template of(String source) {
        Objects.requireNonNull(source, "source");
        return new Template(source, parseRegexSpans(source));
    }

    /** @return the raw template string. */
    public String source() {
        return source;
    }

    public boolean isEmpty() {
        return source.isEmpty();
    }

    /** @return parsed {@code <regex>} spans, in source order. */
    List<RegexSpan> regexSpans() {
        return regexSpans;
    }

    private static List<RegexSpan> parseRegexSpans(String source) {
        List<RegexSpan> spans = new ArrayList<>();
        int cursor = 0;
        while (true) {
            Matcher open = OPEN_REGEX.matcher(source);
            open.region(cursor, source.length());
            if (!open.find()) {
                break;
            }
            int openStart = open.start();
            int openEnd = findOpenTagEnd(source, openStart);
            if (openEnd < 0) {
                throw invalid(source, "unclosed <regex> opening tag");
            }
            if (spans.size() >= MAX_REGEX_SPANS) {
                throw invalid(source, "more than " + MAX_REGEX_SPANS + " <regex> spans");
            }

            String patternText = parseMatchAttribute(source,
                    source.substring(openStart + "<regex".length(), openEnd));
            Matcher close = CLOSE_REGEX.matcher(source);
            close.region(openEnd + 1, source.length());
            if (!close.find()) {
                throw invalid(source, "missing </regex> closing tag");
            }

            String content = source.substring(openEnd + 1, close.start());
            if (OPEN_REGEX.matcher(content).find()) {
                throw invalid(source, "nested <regex> spans are not allowed");
            }
            if (content.length() > MAX_REGEX_CONTENT_LENGTH) {
                throw invalid(source, "content exceeds " + MAX_REGEX_CONTENT_LENGTH
                        + " characters");
            }

            Pattern pattern;
            try {
                pattern = Pattern.compile(patternText);
            } catch (PatternSyntaxException failure) {
                throw invalid(source, "invalid pattern: " + failure.getDescription());
            }
            spans.add(new RegexSpan(openStart, close.end(), content, pattern));
            cursor = close.end();
        }

        rejectUnmatchedClosingTags(source, spans);
        return spans;
    }

    private static String parseMatchAttribute(String source, String attributes) {
        Matcher matcher = MATCH_ATTRIBUTE.matcher(attributes);
        if (!matcher.matches()) {
            throw invalid(source, "expected a single match=\"PATRÓN\" attribute");
        }
        String pattern = decodeXmlEntities(source, matcher.group(1));
        if (pattern.isBlank()) {
            throw invalid(source, "match must not be empty");
        }
        if (pattern.length() > MAX_REGEX_PATTERN_LENGTH) {
            throw invalid(source, "pattern exceeds " + MAX_REGEX_PATTERN_LENGTH
                    + " characters");
        }
        return pattern;
    }

    private static int findOpenTagEnd(String source, int openStart) {
        boolean quoted = false;
        for (int i = openStart + "<regex".length(); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == '>' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static void rejectUnmatchedClosingTags(String source, List<RegexSpan> spans) {
        int cursor = 0;
        for (RegexSpan span : spans) {
            Matcher close = CLOSE_REGEX.matcher(source);
            close.region(cursor, span.start);
            if (close.find()) {
                throw invalid(source, "unmatched </regex> closing tag");
            }
            cursor = span.end();
        }
        Matcher tail = CLOSE_REGEX.matcher(source);
        tail.region(cursor, source.length());
        if (tail.find()) {
            throw invalid(source, "unmatched </regex> closing tag");
        }
    }

    private static String decodeXmlEntities(String source, String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '&') {
                decoded.append(current);
                continue;
            }
            int semicolon = value.indexOf(';', i + 1);
            if (semicolon < 0) {
                throw invalid(source, "malformed XML entity in match attribute");
            }
            String entity = value.substring(i, semicolon + 1);
            switch (entity) {
                case "&lt;" -> decoded.append('<');
                case "&gt;" -> decoded.append('>');
                case "&quot;" -> decoded.append('"');
                case "&apos;" -> decoded.append('\'');
                case "&amp;" -> decoded.append('&');
                default -> throw invalid(source,
                        "malformed XML entity in match attribute: " + entity);
            }
            i = semicolon;
        }
        return decoded.toString();
    }

    private static IllegalArgumentException invalid(String source, String detail) {
        String snippet = source.length() <= 120 ? source : source.substring(0, 117) + "...";
        return new IllegalArgumentException(
                "Invalid <regex> in template [" + snippet + "]: " + detail);
    }

    static final class RegexSpan {
        private final int start;
        private final int end;
        private final String content;
        private final Pattern pattern;

        private RegexSpan(int start, int end, String content, Pattern pattern) {
            this.start = start;
            this.end = end;
            this.content = content;
            this.pattern = pattern;
        }

        int start() {
            return start;
        }

        int end() {
            return end;
        }

        String content() {
            return content;
        }

        Pattern pattern() {
            return pattern;
        }
    }

    @Override
    public String toString() {
        return source;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Template && source.equals(((Template) other).source);
    }

    @Override
    public int hashCode() {
        return source.hashCode();
    }
}
