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
 *       and normally wrapped in a {@code <tr>} span so it gets translated.</li>
 *   <li>{@code sourceLanguage}/{@code targetLanguage} — pair used for
 *       {@code <tr>} translation and exposed as {@code %lang_source%} /
 *       {@code %lang_target%}.</li>
 *   <li>{@code translate} — when false, {@code <tr>} spans are left untouched
 *       (e.g. the sender seeing their own native message).</li>
 * </ul>
 */
public final class TemplateContext {

    private final Actor sender;
    private final String content;
    private final Language sourceLanguage;
    private final Language targetLanguage;
    private final boolean translate;
    private final Map<String, String> variables;

    private TemplateContext(Builder builder) {
        this.sender = Objects.requireNonNull(builder.sender, "sender");
        this.content = builder.content == null ? "" : builder.content;
        this.sourceLanguage = Objects.requireNonNull(builder.sourceLanguage, "sourceLanguage");
        this.targetLanguage = Objects.requireNonNull(builder.targetLanguage, "targetLanguage");
        this.translate = builder.translate;
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

    public boolean translate() {
        return translate;
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
        private boolean translate = true;
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

        public Builder translate(boolean translate) {
            this.translate = translate;
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