package me.majhrs16.cht.core.message;

import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.player.Subject;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A message entering the routing engine.
 *
 * <p>This is the neutral representation of any event that may produce chat
 * output: player chat, joins/leaves, deaths, achievements, signs, private
 * messages or internal plugin text. Platform adapters map their native events
 * onto this type and hand it to the {@code ChatRouter}.</p>
 *
 * <p>The message is immutable; per-recipient rendering happens inside the
 * router and never mutates the original.</p>
 */
public final class ChatMessage {

    private final UUID id;
    private final ChatMessageType type;
    private final Subject sender;
    private final Subject target;
    private final String content;
    private final Optional<Language> sourceLanguage;
    private final Instant timestamp;
    private final Map<String, String> context;
    private final boolean cancelled;
    private final boolean cancelledForSender;
    private final boolean translate;

    private ChatMessage(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID();
        this.type = Objects.requireNonNull(builder.type, "type");
        this.sender = Objects.requireNonNull(builder.sender, "sender");
        this.target = builder.target;
        this.content = builder.content == null ? "" : builder.content;
        this.sourceLanguage = Optional.ofNullable(builder.sourceLanguage);
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.context = Collections.unmodifiableMap(
            new LinkedHashMap<>(builder.context));
        this.cancelled = builder.cancelled;
        this.cancelledForSender = builder.cancelledForSender;
        this.translate = builder.translate;
    }

    public UUID id() {
        return id;
    }

    public ChatMessageType type() {
        return type;
    }

    public Subject sender() {
        return sender;
    }

    public Optional<Subject> target() {
        return Optional.ofNullable(target);
    }

    /** The raw dynamic text carried by the message (what will be translated). */
    public String content() {
        return content;
    }

    /**
     * The language the content is written in. Empty means the engine should
     * try automatic detection.
     */
    public Optional<Language> sourceLanguage() {
        return sourceLanguage;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public Map<String, String> context() {
        return context;
    }

    public String context(String key) {
        return context.get(key);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isCancelledForSender() {
        return cancelledForSender;
    }

    public boolean shouldTranslate() {
        return translate;
    }

    public static Builder builder(ChatMessageType type, Subject sender) {
        return new Builder(type, sender);
    }

    /** Fluent, immutable-safe builder for {@link ChatMessage}. */
    public static final class Builder {

        private final ChatMessageType type;
        private final Subject sender;
        private UUID id;
        private Subject target;
        private String content;
        private Language sourceLanguage;
        private Instant timestamp;
        private final Map<String, String> context = new LinkedHashMap<>();
        private boolean cancelled;
        private boolean cancelledForSender;
        private boolean translate = true;

        private Builder(ChatMessageType type, Subject sender) {
            this.type = type;
            this.sender = sender;
        }

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder target(Subject target) {
            this.target = target;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder sourceLanguage(Language sourceLanguage) {
            this.sourceLanguage = sourceLanguage;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder context(String key, String value) {
            this.context.put(key, value);
            return this;
        }

        public Builder cancelled(boolean cancelled) {
            this.cancelled = cancelled;
            return this;
        }

        public Builder cancelledForSender(boolean cancelledForSender) {
            this.cancelledForSender = cancelledForSender;
            return this;
        }

        /** When false the engine skips translation for this message. */
        public Builder translate(boolean translate) {
            this.translate = translate;
            return this;
        }

        public ChatMessage build() {
            return new ChatMessage(this);
        }
    }
}