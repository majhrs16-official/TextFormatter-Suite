package me.majhrs16.suite.textformatter.template;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable view of the data the template engine needs to render a single
 * template for a single recipient.
 *
 * <ul>
 *   <li>{@code sender} — who produced the message; feeds {@code %player_name%}
 *       and external placeholder resolution.</li>
 *   <li>{@code content} — the raw message text, available as {@code %content%}
 *       (already translated by the {@code translate} pipeline stage when that
 *       stage applies).</li>
 *   <li>{@code sourceLanguage}/{@code targetLanguage} — language pair exposed
 *       as {@code %lang_source%} / {@code %lang_target%}.</li>
 * </ul>
 */
public final class TemplateContext {

    private final Actor sender;
    private final String content;
    private final Language sourceLanguage;
    private final Language targetLanguage;
    private final Map<String, String> variables;

    private TemplateContext(Builder builder) {
        this.sender = Objects.requireNonNull(builder.sender, "sender");
        this.content = builder.content == null ? "" : builder.content;
        this.sourceLanguage = Objects.requireNonNull(builder.sourceLanguage, "sourceLanguage");
        this.targetLanguage = Objects.requireNonNull(builder.targetLanguage, "targetLanguage");
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(builder.variables));
    }

    public Actor sender() {
        return sender;
    }

    public String content() {
        return content;
    }

    public Language sourceLanguage() {
        return sourceLanguage;
    }

    public Language targetLanguage() {
        return targetLanguage;
    }

    public String variable(String key) {
        return variables.get(key);
    }

    public Map<String, String> variables() {
        return variables;
    }

    public static Builder builder(Actor sender, Language source, Language target) {
        return new Builder(sender, source, target);
    }

    public static final class Builder {

        private final Actor sender;
        private final Language sourceLanguage;
        private final Language targetLanguage;
        private String content;
        private final Map<String, String> variables = new LinkedHashMap<>();

        private Builder(Actor sender, Language source, Language target) {
            this.sender = sender;
            this.sourceLanguage = source;
            this.targetLanguage = target;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder variable(String key, String value) {
            this.variables.put(key, value);
            return this;
        }

        public TemplateContext build() {
            return new TemplateContext(this);
        }
    }
}
